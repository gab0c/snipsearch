#!/usr/bin/env python3
"""Prepare and INT8-weight-pack PP-OCR ONNX models and dictionaries using Python stdlib."""

from __future__ import annotations

import argparse
from array import array
import math
from pathlib import Path
import struct
import sys

WEIGHT_AXIS = {"Conv": 0, "ConvTranspose": 1, "MatMul": -1, "Gemm": -1}

TENSOR_FLOAT = 1
TENSOR_UINT8 = 2
TENSOR_INT8 = 3
TENSOR_INT32 = 6
TENSOR_INT64 = 7

ATTR_FLOAT = 1
ATTR_INT = 2
ATTR_INTS = 7


def read_varint(buf: bytes | memoryview, pos: int) -> tuple[int, int]:
    val = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        val |= (b & 0x7F) << shift
        if not (b & 0x80):
            return val, pos
        shift += 7


def encode_varint(val: int) -> bytes:
    if val < 0:
        val &= (1 << 64) - 1
    out = bytearray()
    while True:
        byte = val & 0x7F
        val >>= 7
        if val:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def decode_signed_varint(val: int) -> int:
    return val - (1 << 64) if val >= (1 << 63) else val


def encode_field_varint(field: int, val: int) -> bytes:
    return encode_varint((field << 3) | 0) + encode_varint(val)


def encode_field_bytes(field: int, payload: bytes) -> bytes:
    return encode_varint((field << 3) | 2) + encode_varint(len(payload)) + payload


def parse_fields(buf: bytes | memoryview) -> list[tuple[int, int, int | bytes, bytes]]:
    fields = []
    pos = 0
    n = len(buf)
    while pos < n:
        start = pos
        tag, pos = read_varint(buf, pos)
        field = tag >> 3
        wire = tag & 7
        if wire == 0:
            val, pos = read_varint(buf, pos)
            fields.append((field, wire, val, bytes(buf[start:pos])))
        elif wire == 1:
            end = pos + 8
            fields.append((field, wire, bytes(buf[pos:end]), bytes(buf[start:end])))
            pos = end
        elif wire == 2:
            ln, pos = read_varint(buf, pos)
            end = pos + ln
            fields.append((field, wire, bytes(buf[pos:end]), bytes(buf[start:end])))
            pos = end
        elif wire == 5:
            end = pos + 4
            fields.append((field, wire, bytes(buf[pos:end]), bytes(buf[start:end])))
            pos = end
        else:
            raise ValueError(f"Unsupported protobuf wire type {wire} at offset {start}")
    return fields


def decode_repeated_int64(fields: list[tuple[int, int, int | bytes, bytes]], target_field: int) -> list[int]:
    out: list[int] = []
    for f, w, v, _ in fields:
        if f != target_field:
            continue
        if w == 0:
            assert isinstance(v, int)
            out.append(decode_signed_varint(v))
        elif w == 2:
            assert isinstance(v, bytes)
            p = 0
            while p < len(v):
                item, p = read_varint(v, p)
                out.append(decode_signed_varint(item))
    return out


def decode_tensor(tensor_bytes: bytes) -> tuple[str, list[int], int, array | None]:
    fields = parse_fields(tensor_bytes)
    dims = decode_repeated_int64(fields, 1)
    data_type = 0
    name = ""
    raw_data: bytes | None = None
    float_parts: list[float] = []
    for f, w, v, _ in fields:
        if f == 2 and w == 0:
            assert isinstance(v, int)
            data_type = v
        elif f == 8 and w == 2:
            assert isinstance(v, bytes)
            name = v.decode("utf-8")
        elif f == 9 and w == 2:
            assert isinstance(v, bytes)
            raw_data = v
        elif f == 4 and data_type in (0, TENSOR_FLOAT):
            if w == 5:
                assert isinstance(v, bytes)
                float_parts.append(struct.unpack("<f", v)[0])
            elif w == 2:
                assert isinstance(v, bytes)
                arr = array("f")
                arr.frombytes(v)
                if sys.byteorder != "little":
                    arr.byteswap()
                float_parts.extend(arr)
        elif f == 13:
            raise ValueError(f"External tensor data not supported for {name}")
    if data_type != TENSOR_FLOAT:
        return name, dims, data_type, None
    values = array("f")
    if raw_data is not None:
        values.frombytes(raw_data)
        if sys.byteorder != "little":
            values.byteswap()
    elif float_parts:
        values.extend(float_parts)
    else:
        return name, dims, data_type, None
    return name, dims, data_type, values


def encode_tensor(name: str, dims: list[int], data_type: int, raw_bytes: bytes) -> bytes:
    parts = [encode_field_varint(1, d) for d in dims]
    parts.append(encode_field_varint(2, data_type))
    parts.append(encode_field_bytes(8, name.encode("utf-8")))
    parts.append(encode_field_bytes(9, raw_bytes))
    return b"".join(parts)


def encode_field_fixed32(field: int, payload: bytes) -> bytes:
    assert len(payload) == 4
    return encode_varint((field << 3) | 5) + payload


def decode_tensor_raw(tensor_bytes: bytes) -> tuple[str, list[int], int, bytes]:
    fields = parse_fields(tensor_bytes)
    dims = decode_repeated_int64(fields, 1)
    data_type = 0
    name = ""
    raw_data: bytes | None = None
    float_parts: list[float] = []
    int64_parts: list[int] = []
    int32_parts: list[int] = []
    for f, w, v, _ in fields:
        if f == 2 and w == 0:
            assert isinstance(v, int)
            data_type = v
        elif f == 8 and w == 2:
            assert isinstance(v, bytes)
            name = v.decode("utf-8")
        elif f == 9 and w == 2:
            assert isinstance(v, bytes)
            raw_data = v
        elif f == 4:
            if w == 5:
                assert isinstance(v, bytes)
                float_parts.append(struct.unpack("<f", v)[0])
            elif w == 2:
                assert isinstance(v, bytes)
                arr = array("f")
                arr.frombytes(v)
                if sys.byteorder != "little":
                    arr.byteswap()
                float_parts.extend(arr)
        elif f == 5:
            if w == 0:
                assert isinstance(v, int)
                int32_parts.append(decode_signed_varint(v))
            elif w == 2:
                assert isinstance(v, bytes)
                p = 0
                while p < len(v):
                    item, p = read_varint(v, p)
                    int32_parts.append(decode_signed_varint(item))
        elif f == 7:
            if w == 0:
                assert isinstance(v, int)
                int64_parts.append(decode_signed_varint(v))
            elif w == 2:
                assert isinstance(v, bytes)
                p = 0
                while p < len(v):
                    item, p = read_varint(v, p)
                    int64_parts.append(decode_signed_varint(item))
        elif f == 13:
            raise ValueError(f"External tensor data not supported for {name}")
    if raw_data is not None:
        return name, dims, data_type, raw_data
    if data_type == TENSOR_FLOAT and float_parts:
        return name, dims, data_type, pack_float32_le(float_parts)
    if data_type == TENSOR_INT64 and int64_parts:
        return name, dims, data_type, pack_int64_le(int64_parts)
    if data_type == TENSOR_INT32 and int32_parts:
        return name, dims, data_type, struct.pack(f"<{len(int32_parts)}i", *int32_parts)
    return name, dims, data_type, b""


def make_int_attr(name: str, value: int) -> bytes:
    return (
        encode_field_bytes(1, name.encode("utf-8"))
        + encode_field_varint(3, value)
        + encode_field_varint(20, ATTR_INT)
    )


def make_float_attr(name: str, value: float) -> bytes:
    return (
        encode_field_bytes(1, name.encode("utf-8"))
        + encode_field_fixed32(2, struct.pack("<f", value))
        + encode_field_varint(20, ATTR_FLOAT)
    )


def make_ints_attr(name: str, values: list[int]) -> bytes:
    return (
        encode_field_bytes(1, name.encode("utf-8"))
        + encode_field_bytes(8, b"".join(encode_varint(v) for v in values))
        + encode_field_varint(20, ATTR_INTS)
    )


def attr_float_value(attr_bytes: bytes) -> tuple[str, float | None]:
    name = ""
    f_val: float | None = None
    for f, w, v, _ in parse_fields(attr_bytes):
        if f == 1 and w == 2:
            assert isinstance(v, bytes)
            name = v.decode("utf-8")
        elif f == 2 and w == 5:
            assert isinstance(v, bytes)
            f_val = struct.unpack("<f", v)[0]
    return name, f_val


def make_node(op_type: str, inputs: list[str], outputs: list[str], attrs: list[bytes], name: str = "") -> bytes:
    parts = [encode_field_bytes(1, item.encode("utf-8")) for item in inputs]
    parts.extend(encode_field_bytes(2, item.encode("utf-8")) for item in outputs)
    if name:
        parts.append(encode_field_bytes(3, name.encode("utf-8")))
    parts.append(encode_field_bytes(4, op_type.encode("utf-8")))
    parts.extend(encode_field_bytes(5, attr) for attr in attrs)
    return b"".join(parts)


def decode_node(node_bytes: bytes) -> tuple[str, str, list[str], list[str], list[bytes]]:
    inputs: list[str] = []
    outputs: list[str] = []
    name = ""
    op_type = ""
    attrs: list[bytes] = []
    for f, w, v, _ in parse_fields(node_bytes):
        if f == 1 and w == 2:
            assert isinstance(v, bytes)
            inputs.append(v.decode("utf-8"))
        elif f == 2 and w == 2:
            assert isinstance(v, bytes)
            outputs.append(v.decode("utf-8"))
        elif f == 3 and w == 2:
            assert isinstance(v, bytes)
            name = v.decode("utf-8")
        elif f == 4 and w == 2:
            assert isinstance(v, bytes)
            op_type = v.decode("utf-8")
        elif f == 5 and w == 2:
            assert isinstance(v, bytes)
            attrs.append(v)
    return name, op_type, inputs, outputs, attrs


def attr_name_and_info(attr_bytes: bytes) -> tuple[str, int | None, list[int], bytes | None]:
    fields = parse_fields(attr_bytes)
    name = ""
    i_val: int | None = None
    t_val: bytes | None = None
    for f, w, v, _ in fields:
        if f == 1 and w == 2:
            assert isinstance(v, bytes)
            name = v.decode("utf-8")
        elif f == 3 and w == 0:
            assert isinstance(v, int)
            i_val = decode_signed_varint(v)
        elif f == 5 and w == 2:
            assert isinstance(v, bytes)
            t_val = v
    ints_val = decode_repeated_int64(fields, 8)
    return name, i_val, ints_val, t_val


def rewrite_value_info_to_argmax(vi_bytes: bytes, drop_axis: int, element_type: int = TENSOR_INT64) -> bytes:
    out_parts = []
    for f, w, v, raw in parse_fields(vi_bytes):
        if f != 2 or w != 2:
            out_parts.append(raw)
            continue
        assert isinstance(v, bytes)
        type_parts = []
        for tf, tw, tv, traw in parse_fields(v):
            if tf != 1 or tw != 2:
                type_parts.append(traw)
                continue
            assert isinstance(tv, bytes)
            tensor_type_parts = []
            for ttf, ttw, ttv, ttraw in parse_fields(tv):
                if ttf == 1 and ttw == 0:
                    tensor_type_parts.append(encode_field_varint(1, element_type))
                elif ttf == 2 and ttw == 2:
                    assert isinstance(ttv, bytes)
                    dims_raw = [sraw for sf, sw, sv, sraw in parse_fields(ttv) if sf == 1]
                    axis = drop_axis + len(dims_raw) if drop_axis < 0 else drop_axis
                    if not (0 <= axis < len(dims_raw)):
                        raise ValueError(f"ArgMax axis {drop_axis} invalid for {len(dims_raw)}D output")
                    kept_dims = b"".join(d for idx, d in enumerate(dims_raw) if idx != axis)
                    other_shape_fields = b"".join(
                        sraw for sf, sw, sv, sraw in parse_fields(ttv) if sf != 1
                    )
                    tensor_type_parts.append(encode_field_bytes(2, kept_dims + other_shape_fields))
                else:
                    tensor_type_parts.append(ttraw)
            type_parts.append(encode_field_bytes(1, b"".join(tensor_type_parts)))
        out_parts.append(encode_field_bytes(2, b"".join(type_parts)))
    return b"".join(out_parts)


def pack_float32_le(values: list[float] | array) -> bytes:
    arr = array("f", values)
    if sys.byteorder != "little":
        arr.byteswap()
    return arr.tobytes()


def pack_int8_le(values: list[int] | array) -> bytes:
    arr = array("b", values)
    return arr.tobytes()


def pack_int64_le(values: list[int]) -> bytes:
    return struct.pack(f"<{len(values)}q", *values)


def decode_float_list(raw_bytes: bytes) -> list[float]:
    count = len(raw_bytes) // 4
    return list(struct.unpack(f"<{count}f", raw_bytes))


def decode_int64_list(raw_bytes: bytes) -> list[int]:
    count = len(raw_bytes) // 8
    return list(struct.unpack(f"<{count}q", raw_bytes))


def optimize_graph_structure(
    nodes: list[bytes],
    initializers: list[bytes],
    outputs: list[bytes],
) -> tuple[list[bytes], list[bytes], list[bytes]]:
    decoded = [list(decode_node(n)) for n in nodes]
    init_map: dict[str, tuple[list[int], int, bytes]] = {}
    for ib in initializers:
        tname, dims, dt, raw = decode_tensor_raw(ib)
        init_map[tname] = (dims, dt, raw)

    graph_out_names = [
        next(vv.decode("utf-8") for vf, vw, vv, _ in parse_fields(o) if vf == 1)
        for o in outputs
    ]

    def rebuild_node(entry: list) -> bytes:
        return make_node(entry[1], entry[2], entry[3], entry[4], name=entry[0])

    # 1. Eliminate Identity nodes by aliasing outputs to resolved inputs.
    alias: dict[str, str] = {}

    def resolve(t: str) -> str:
        while t in alias:
            t = alias[t]
        return t

    no_id: list[list] = []
    for nm, op, ins, outs, attrs in decoded:
        ins = [resolve(x) for x in ins]
        if op == "Identity" and len(ins) == 1 and len(outs) == 1:
            if outs[0] in graph_out_names and ins[0] not in graph_out_names:
                # Preserve graph output tensor name by renaming producer if unique, else keep Identity.
                renamed = False
                for p in reversed(no_id):
                    if ins[0] in p[3]:
                        p[3] = [outs[0] if x == ins[0] else x for x in p[3]]
                        for qnode in no_id:
                            qnode[2] = [outs[0] if x == ins[0] else x for x in qnode[2]]
                        alias = {k: (outs[0] if v == ins[0] else v) for k, v in alias.items()}
                        alias[ins[0]] = outs[0]
                        renamed = True
                        break
                if renamed:
                    continue
            else:
                alias[outs[0]] = ins[0]
                continue
        no_id.append([nm, op, ins, outs, attrs])
    decoded = no_id

    def count_uses(node_list: list[list]) -> tuple[dict[str, int], dict[str, list[int]]]:
        uses: dict[str, int] = {o: 1 for o in graph_out_names}
        consumers: dict[str, list[int]] = {}
        for idx, entry in enumerate(node_list):
            for x in entry[2]:
                if x:
                    uses[x] = uses.get(x, 0) + 1
                    consumers.setdefault(x, []).append(idx)
        return uses, consumers

    # 2. Cancel back-to-back inverse Transpose pairs.
    changed = True
    while changed:
        changed = False
        uses, consumers = count_uses(decoded)
        skip: set[int] = set()
        for idx, (nm, op, ins, outs, attrs) in enumerate(decoded):
            if idx in skip or op != "Transpose" or len(ins) != 1 or len(outs) != 1:
                continue
            y = outs[0]
            if uses.get(y, 0) != 1:
                continue
            cidxs = consumers.get(y, [])
            if len(cidxs) != 1:
                continue
            cidx = cidxs[0]
            cnm, cop, cins, couts, cattrs = decoded[cidx]
            if cop != "Transpose":
                continue
            p1 = next((attr_name_and_info(a)[2] for a in attrs if attr_name_and_info(a)[0] == "perm"), [])
            p2 = next((attr_name_and_info(a)[2] for a in cattrs if attr_name_and_info(a)[0] == "perm"), [])
            if p1 and len(p1) == len(p2) and [p1[k] for k in p2] == list(range(len(p1))):
                old_out = couts[0]
                if old_out in graph_out_names:
                    continue
                src = ins[0]
                skip.add(idx)
                skip.add(cidx)
                for node in decoded:
                    node[2] = [src if x == old_out else x for x in node[2]]
                changed = True
                break
        if skip:
            decoded = [n for i, n in enumerate(decoded) if i not in skip]

    # 3. Fold Conv / ConvTranspose + constant bias Add into the 3rd Conv/ConvTranspose input.
    uses, consumers = count_uses(decoded)
    skip = set()
    for idx, entry in enumerate(decoded):
        if idx in skip:
            continue
        nm, op, ins, outs, attrs = entry
        if op not in ("Conv", "ConvTranspose") or len(ins) != 2 or len(outs) != 1:
            continue
        if uses.get(outs[0], 0) != 1:
            continue
        cidx = consumers.get(outs[0], [-1])[0]
        if cidx < 0 or decoded[cidx][1] != "Add":
            continue
        c_ins = decoded[cidx][2]
        other = [x for x in c_ins if x != outs[0]]
        if len(other) != 1 or other[0] not in init_map:
            continue
        w_info = init_map.get(ins[1])
        b_info = init_map.get(other[0])
        if w_info is None or b_info is None or b_info[1] != TENSOR_FLOAT:
            continue
        w_dims = w_info[0]
        group = 1
        for a in attrs:
            an, ai, _, _ = attr_name_and_info(a)
            if an == "group" and ai is not None:
                group = ai
        out_channels = w_dims[0] if op == "Conv" else w_dims[1] * group
        b_dims, _, b_raw = b_info
        b_numel = math.prod(b_dims) if b_dims else 1
        if b_numel != out_channels:
            continue
        if len(b_dims) == 4 and (b_dims[0] != 1 or b_dims[1] != out_channels or b_dims[2] != 1 or b_dims[3] != 1):
            continue
        if len(b_dims) not in (1, 4):
            continue
        flat_bias_name = f"{outs[0]}__folded_bias"
        while flat_bias_name in init_map:
            flat_bias_name += "_"
        init_map[flat_bias_name] = ([out_channels], TENSOR_FLOAT, b_raw)
        entry[2] = [ins[0], ins[1], flat_bias_name]
        entry[3] = [decoded[cidx][3][0]]
        skip.add(cidx)
    if skip:
        decoded = [n for i, n in enumerate(decoded) if i not in skip]

    # 4. Cancel direct Squeeze(axes) -> Unsqueeze(axes) pairs and bypass 4D->3D->4D around HardSwish.
    changed = True
    while changed:
        changed = False
        uses, consumers = count_uses(decoded)
        for idx, (nm, op, ins, outs, attrs) in enumerate(decoded):
            if op != "Squeeze" or len(ins) != 2 or len(outs) != 1:
                continue
            axes_info = init_map.get(ins[1])
            if axes_info is None or axes_info[1] != TENSOR_INT64:
                continue
            axes1 = decode_int64_list(axes_info[2])
            s_out = outs[0]
            cidxs = consumers.get(s_out, [])
            # Case A: direct Squeeze -> Unsqueeze
            if uses.get(s_out, 0) == 1 and len(cidxs) == 1 and decoded[cidxs[0]][1] == "Unsqueeze":
                u_out = decoded[cidxs[0]][3][0]
                if u_out in graph_out_names:
                    continue
                u_ins = decoded[cidxs[0]][2]
                u_axes = init_map.get(u_ins[1]) if len(u_ins) == 2 else None
                if u_axes and u_axes[1] == TENSOR_INT64 and decode_int64_list(u_axes[2]) == axes1:
                    src = ins[0]
                    decoded = [n for i, n in enumerate(decoded) if i not in (idx, cidxs[0])]
                    for node in decoded:
                        node[2] = [src if x == u_out else x for x in node[2]]
                    changed = True
                    break

    # 5. Fold BatchNormalization into preceding Conv (direct or across a single Squeeze(axes=[2])).
    uses, consumers = count_uses(decoded)
    skip = set()
    # Keep track of weight initializers to avoid mutating shared weights in place.
    init_use_counts: dict[str, int] = {}
    for entry in decoded:
        for x in entry[2]:
            if x in init_map:
                init_use_counts[x] = init_use_counts.get(x, 0) + 1

    for idx, entry in enumerate(decoded):
        if idx in skip:
            continue
        nm, op, ins, outs, attrs = entry
        if op != "Conv" or len(outs) != 1 or ins[1] not in init_map:
            continue
        w_dims, w_dt, w_raw = init_map[ins[1]]
        if w_dt != TENSOR_FLOAT or len(w_dims) != 4 or init_use_counts.get(ins[1], 0) != 1:
            continue
        conv_out = outs[0]
        if uses.get(conv_out, 0) != 1:
            continue
        c1_idx = consumers[conv_out][0]
        sq_idx: int | None = None
        if decoded[c1_idx][1] == "BatchNormalization":
            bn_idx = c1_idx
        elif decoded[c1_idx][1] == "Squeeze" and uses.get(decoded[c1_idx][3][0], 0) == 1:
            sq_out = decoded[c1_idx][3][0]
            c2_idx = consumers[sq_out][0]
            if decoded[c2_idx][1] != "BatchNormalization":
                continue
            sq_idx = c1_idx
            bn_idx = c2_idx
        else:
            continue

        bn_ins = decoded[bn_idx][2]
        bn_outs = decoded[bn_idx][3]
        bn_attrs = decoded[bn_idx][4]
        if len(bn_ins) < 5 or any(x not in init_map for x in bn_ins[1:5]):
            continue
        oc = w_dims[0]
        gamma_d, gamma_dt, gamma_raw = init_map[bn_ins[1]]
        beta_d, beta_dt, beta_raw = init_map[bn_ins[2]]
        mean_d, mean_dt, mean_raw = init_map[bn_ins[3]]
        var_d, var_dt, var_raw = init_map[bn_ins[4]]
        if not all(dt == TENSOR_FLOAT and d == [oc] for d, dt in (
            (gamma_d, gamma_dt), (beta_d, beta_dt), (mean_d, mean_dt), (var_d, var_dt)
        )):
            continue
        eps = 1e-5
        for a in bn_attrs:
            an, fv = attr_float_value(a)
            if an == "epsilon" and fv is not None:
                eps = fv

        gamma = decode_float_list(gamma_raw)
        beta = decode_float_list(beta_raw)
        mean = decode_float_list(mean_raw)
        var = decode_float_list(var_raw)
        w_vals = decode_float_list(w_raw)
        if len(ins) == 3:
            b_dims, b_dt, b_raw = init_map[ins[2]]
            if b_dt != TENSOR_FLOAT or b_dims != [oc]:
                continue
            b_vals = decode_float_list(b_raw)
        else:
            b_vals = [0.0] * oc

        inner = len(w_vals) // oc
        new_w = [0.0] * len(w_vals)
        new_b = [0.0] * oc
        for c in range(oc):
            scale = gamma[c] / math.sqrt(var[c] + eps)
            base = c * inner
            for j in range(inner):
                new_w[base + j] = w_vals[base + j] * scale
            new_b[c] = (b_vals[c] - mean[c]) * scale + beta[c]

        init_map[ins[1]] = (w_dims, TENSOR_FLOAT, pack_float32_le(new_w))
        bias_name = ins[2] if len(ins) == 3 else f"{conv_out}__bn_bias"
        while len(ins) < 3 and bias_name in init_map:
            bias_name += "_"
        init_map[bias_name] = ([oc], TENSOR_FLOAT, pack_float32_le(new_b))
        if len(ins) < 3:
            entry[2] = [ins[0], ins[1], bias_name]
        if sq_idx is None:
            entry[3] = [bn_outs[0]]
            skip.add(bn_idx)
        else:
            # Keep Squeeze after Conv so Conv stays 4D and BN disappears.
            decoded[sq_idx][3] = [bn_outs[0]]
            skip.add(bn_idx)
    if skip:
        decoded = [n for i, n in enumerate(decoded) if i not in skip]

    # 6. Keep Conv+HardSwish (HardSigmoid + Mul) in 4D by deferring Squeeze(axes=[2]) until after Mul.
    changed = True
    while changed:
        changed = False
        uses, consumers = count_uses(decoded)
        for idx, entry in enumerate(decoded):
            nm, op, ins, outs, attrs = entry
            if op != "Squeeze" or len(ins) != 2 or len(outs) != 1:
                continue
            s_out = outs[0]
            if uses.get(s_out, 0) != 2:
                continue
            cidxs = consumers.get(s_out, [])
            if len(cidxs) != 2:
                continue
            hs_idx, mul_idx = cidxs[0], cidxs[1]
            if decoded[hs_idx][1] != "HardSigmoid" or decoded[mul_idx][1] != "Mul":
                continue
            hs_out = decoded[hs_idx][3][0]
            if uses.get(hs_out, 0) != 1 or hs_out not in decoded[mul_idx][2]:
                continue
            mul_out = decoded[mul_idx][3][0]
            # Verify Mul is only consumed once and either feeds Unsqueeze(same axes) or a 3D consumer.
            if uses.get(mul_out, 0) != 1:
                continue
            mc_idx = consumers[mul_out][0]
            axes_info = init_map.get(ins[1])
            mc_node = decoded[mc_idx]
            if mc_node[1] == "Unsqueeze" and len(mc_node[2]) == 2 and mc_node[3][0] not in graph_out_names:
                u_axes = init_map.get(mc_node[2][1])
                if axes_info and u_axes and decode_int64_list(axes_info[2]) == decode_int64_list(u_axes[2]):
                    # Bypass Squeeze and Unsqueeze completely around HardSwish!
                    decoded[hs_idx][2] = [ins[0]]
                    decoded[mul_idx][2] = [hs_out if x == s_out else ins[0] for x in decoded[mul_idx][2]]
                    u_out = mc_node[3][0]
                    decoded = [n for i, n in enumerate(decoded) if i not in (idx, mc_idx)]
                    for node in decoded:
                        node[2] = [mul_out if x == u_out else x for x in node[2]]
                    changed = True
                    break
            elif mc_node[1] == "Transpose":
                # Move Squeeze after Mul so Conv -> HardSwish stays 4D before Transpose.
                decoded[hs_idx][2] = [ins[0]]
                decoded[mul_idx][2] = [hs_out if x == s_out else ins[0] for x in decoded[mul_idx][2]]
                four_d_mul_out = f"{mul_out}__4d"
                decoded[mul_idx][3] = [four_d_mul_out]
                entry[2] = [four_d_mul_out, ins[1]]
                entry[3] = [mul_out]
                # Ensure topological order: Squeeze node must sit right after Mul.
                sq_node = decoded.pop(idx)
                new_mul_idx = next(i for i, n in enumerate(decoded) if n[3] == [four_d_mul_out])
                decoded.insert(new_mul_idx + 1, sq_node)
                changed = True
                break

    # 7. Prune unreferenced initializers.
    referenced = {x for entry in decoded for x in entry[2]}
    new_inits = [
        encode_tensor(tname, dims, dt, raw)
        for tname, (dims, dt, raw) in init_map.items()
        if tname in referenced
    ]
    return [rebuild_node(e) for e in decoded], new_inits, outputs


def quantize_weight_per_channel(
    name: str, dims: list[int], values: array, axis: int,
) -> tuple[bytes, bytes, bytes]:
    ndim = len(dims)
    norm_axis = axis % ndim
    outer = 1
    for d in dims[:norm_axis]:
        outer *= d
    axis_len = dims[norm_axis]
    inner = 1
    for d in dims[norm_axis + 1 :]:
        inner *= d

    max_abs = [0.0] * axis_len
    stride_outer = axis_len * inner
    for o in range(outer):
        base_o = o * stride_outer
        for a in range(axis_len):
            base_a = base_o + a * inner
            best = max_abs[a]
            for i in range(inner):
                v = abs(values[base_a + i])
                if v > best:
                    best = v
            max_abs[a] = best

    scales = [max(m / 127.0, 1e-12) for m in max_abs]
    # Round-trip scales through IEEE-754 float32 before quantizing so weights align cleanly.
    scales_f32 = list(struct.unpack(f"<{axis_len}f", pack_float32_le(scales)))

    q = array("b", [0]) * len(values)
    for o in range(outer):
        base_o = o * stride_outer
        for a in range(axis_len):
            base_a = base_o + a * inner
            inv = 1.0 / scales_f32[a]
            for i in range(inner):
                r = int(round(values[base_a + i] * inv))
                if r > 127:
                    r = 127
                elif r < -127:
                    r = -127
                q[base_a + i] = r

    zeros = [0] * axis_len
    init_q = encode_tensor(f"{name}_int8", dims, TENSOR_INT8, pack_int8_le(q))
    init_s = encode_tensor(f"{name}_scale", [axis_len], TENSOR_FLOAT, pack_float32_le(scales_f32))
    init_z = encode_tensor(f"{name}_zero", [axis_len], TENSOR_INT8, pack_int8_le(zeros))
    return init_q, init_s, init_z


def is_cjk_char(ch: str) -> bool:
    cp = ord(ch)
    return (
        0x4E00 <= cp <= 0x9FFF
        or 0x3400 <= cp <= 0x4DBF
        or 0xF900 <= cp <= 0xFAFF
        or 0x2E80 <= cp <= 0x2FDF
        or 0x3000 <= cp <= 0x31BF
        or 0xFF00 <= cp <= 0xFFEF
    )


def extract_dict_from_yml(yml_path: Path) -> list[str]:
    text = yml_path.read_text(encoding="utf-8")
    marker = "character_dict:"
    idx = text.find(marker)
    if idx < 0:
        raise ValueError(f"Missing character_dict in {yml_path}")
    chars: list[str] = []
    for line in text[idx:].splitlines()[1:]:
        if not line.startswith("  - "):
            break
        raw = line[4:]
        if len(raw) >= 2 and raw[0] == "'" and raw[-1] == "'":
            val = raw[1:-1].replace("''", "'")
        elif len(raw) >= 2 and raw[0] == '"' and raw[-1] == '"':
            val = raw[1:-1].replace('\\"', '"').replace("\\\\", "\\")
        else:
            val = raw
        if len(val) != 1:
            raise ValueError(f"Expected single-character entry, got {val!r}")
        chars.append(val)
    if not chars:
        raise ValueError(f"Empty character_dict in {yml_path}")
    return chars


def upgrade_squeeze_opset_13(
    nodes: list[bytes],
    initializers: list[bytes],
) -> tuple[list[bytes], list[bytes]]:
    upgraded_nodes = []
    extra_axes_inits: list[bytes] = []
    axes_counter = 0
    for nb in nodes:
        name, op, ins, outs, attrs = decode_node(nb)
        if op in ("Squeeze", "Unsqueeze") and len(ins) == 1:
            kept_attrs = []
            axes_list: list[int] | None = None
            for ab in attrs:
                an, _, ints_val, _ = attr_name_and_info(ab)
                if an == "axes" and ints_val:
                    axes_list = ints_val
                else:
                    kept_attrs.append(ab)
            if axes_list is not None:
                axes_name = f"__opset13_axes_{axes_counter}"
                axes_counter += 1
                extra_axes_inits.append(
                    encode_tensor(axes_name, [len(axes_list)], TENSOR_INT64, pack_int64_le(axes_list))
                )
                upgraded_nodes.append(make_node(op, [ins[0], axes_name], outs, kept_attrs, name=name))
                continue
        upgraded_nodes.append(nb)
    return upgraded_nodes, initializers + extra_axes_inits


def transform_model(
    source: Path,
    output: Path,
    quantize_weights: bool,
    replace_softmax_with_argmax: bool,
    prune_dicts: tuple[Path, Path] | None,
    optimize_graph: bool = False,
    confidence_mode: str = "softmax",
    dynamic_quant_matmul: bool = False,
) -> None:
    model_fields = parse_fields(source.read_bytes())
    graph_bytes = next((v for f, w, v, _ in model_fields if f == 7 and w == 2), None)
    if not isinstance(graph_bytes, bytes):
        raise ValueError("Missing graph in ONNX model")

    current_opset = 0
    for f, w, v, _ in model_fields:
        if f == 8 and w == 2:
            assert isinstance(v, bytes)
            op_fields = parse_fields(v)
            dom = next((ov.decode("utf-8") for of, ow, ov, _ in op_fields if of == 1 and ow == 2), "")
            if dom == "":
                ver = next((ov for of, ow, ov, _ in op_fields if of == 2 and ow == 0), 0)
                assert isinstance(ver, int)
                current_opset = max(current_opset, ver)

    graph_fields = parse_fields(graph_bytes)
    nodes = [v for f, w, v, _ in graph_fields if f == 1 and w == 2]
    initializers = [v for f, w, v, _ in graph_fields if f == 5 and w == 2]
    outputs = [v for f, w, v, _ in graph_fields if f == 12 and w == 2]

    # Step A: Optional CJK class pruning (before folding/quantization so head weight shapes match raw graph).
    if prune_dicts is not None:
        dict_in_path, dict_out_path = prune_dicts
        orig_chars = dict_in_path.read_text(encoding="utf-8").splitlines()
        total_classes = len(orig_chars) + 2  # class 0 = blank, final class = ' '
        keep_dict_pairs = [(i, ch) for i, ch in enumerate(orig_chars) if not is_cjk_char(ch)]
        keep_classes = [0] + [i + 1 for i, _ in keep_dict_pairs] + [len(orig_chars) + 1]
        dict_out_path.parent.mkdir(parents=True, exist_ok=True)
        dict_out_path.write_text("\n".join(ch for _, ch in keep_dict_pairs) + "\n", encoding="utf-8")

        head_weight_name: str | None = None
        head_bias_name: str | None = None
        decoded_nodes = [decode_node(n) for n in nodes]
        # Match final projection weight/bias shapes against total class count.
        init_shapes: dict[str, list[int]] = {}
        for ib in initializers:
            tname, dims, _, _ = decode_tensor(ib)
            init_shapes[tname] = dims
        for _, op, ins, outs, _ in decoded_nodes:
            if op == "MatMul" and len(ins) >= 2 and init_shapes.get(ins[1], [])[-1:] == [total_classes]:
                head_weight_name = ins[1]
            elif op == "Add" and len(ins) >= 2 and init_shapes.get(ins[1]) == [total_classes]:
                head_bias_name = ins[1]
        if head_weight_name is None or head_bias_name is None:
            raise ValueError(
                f"Could not find classification head weight/bias of class count {total_classes}"
            )
        new_inits = []
        for ib in initializers:
            tname, dims, dt, vals = decode_tensor(ib)
            if tname == head_weight_name:
                assert vals is not None and len(dims) == 2 and dims[1] == total_classes
                rows, cols = dims
                sliced = array("f")
                for r in range(rows):
                    base = r * cols
                    for c in keep_classes:
                        sliced.append(vals[base + c])
                new_inits.append(
                    encode_tensor(tname, [rows, len(keep_classes)], TENSOR_FLOAT, pack_float32_le(sliced))
                )
            elif tname == head_bias_name:
                assert vals is not None and dims == [total_classes]
                sliced = array("f", (vals[c] for c in keep_classes))
                new_inits.append(encode_tensor(tname, [len(keep_classes)], TENSOR_FLOAT, pack_float32_le(sliced)))
            else:
                new_inits.append(ib)
        initializers = new_inits

    needs_opset_13 = optimize_graph or quantize_weights or dynamic_quant_matmul
    target_opset = max(current_opset, 13) if needs_opset_13 else current_opset
    if needs_opset_13 and current_opset < 13:
        nodes, initializers = upgrade_squeeze_opset_13(nodes, initializers)

    if optimize_graph:
        nodes, initializers, outputs = optimize_graph_structure(nodes, initializers, outputs)

    # Step B: Emit compact per-step confidence and token IDs without transferring full vocabulary logits.
    if replace_softmax_with_argmax:
        output_names = {
            next(vv.decode("utf-8") for vf, vw, vv, _ in parse_fields(o) if vf == 1)
            for o in outputs
        }
        rewritten_nodes = []
        replaced_axes: dict[str, int] = {}
        extra_confidence_initializers: list[bytes] = []
        topk_helper_names: tuple[str, str, str] | None = None
        for nb in nodes:
            name, op, ins, outs, attrs = decode_node(nb)
            if op == "Softmax" and len(outs) == 1 and outs[0] in output_names:
                axis = 1
                for ab in attrs:
                    an, ai, _, _ = attr_name_and_info(ab)
                    if an == "axis" and ai is not None:
                        axis = ai
                token_output = outs[0] + "_argmax_tokens"
                confidence_output = outs[0] + "_max_probability"
                replaced_axes[outs[0]] = axis
                if confidence_mode == "topk":
                    if topk_helper_names is None:
                        k_name = "__topk_k"
                        idx0_name = "__topk_idx0"
                        idx1_name = "__topk_idx1"
                        extra_confidence_initializers.extend([
                            encode_tensor(k_name, [1], TENSOR_INT64, pack_int64_le([2])),
                            encode_tensor(idx0_name, [], TENSOR_INT64, pack_int64_le([0])),
                            encode_tensor(idx1_name, [], TENSOR_INT64, pack_int64_le([1])),
                        ])
                        topk_helper_names = (k_name, idx0_name, idx1_name)
                    k_name, idx0_name, idx1_name = topk_helper_names
                    prefix = f"{outs[0]}__topk"
                    topk_vals = f"{prefix}_vals"
                    topk_idxs = f"{prefix}_idxs"
                    top1_val = f"{prefix}_top1"
                    top2_val = f"{prefix}_top2"
                    margin = f"{prefix}_margin"
                    rewritten_nodes.extend([
                        make_node(
                            "TopK",
                            [ins[0], k_name],
                            [topk_vals, topk_idxs],
                            [make_int_attr("axis", axis), make_int_attr("largest", 1), make_int_attr("sorted", 1)],
                            name=name.replace("Softmax", "TopK") if name else "",
                        ),
                        make_node(
                            "Gather",
                            [topk_idxs, idx0_name],
                            [token_output],
                            [make_int_attr("axis", axis)],
                            name=name.replace("Softmax", "ArgMaxGather") if name else "",
                        ),
                        make_node(
                            "Gather",
                            [topk_vals, idx0_name],
                            [top1_val],
                            [make_int_attr("axis", axis)],
                            name=name.replace("Softmax", "Top1Gather") if name else "",
                        ),
                        make_node(
                            "Gather",
                            [topk_vals, idx1_name],
                            [top2_val],
                            [make_int_attr("axis", axis)],
                            name=name.replace("Softmax", "Top2Gather") if name else "",
                        ),
                        make_node(
                            "Sub",
                            [top1_val, top2_val],
                            [margin],
                            [],
                            name=name.replace("Softmax", "MarginSub") if name else "",
                        ),
                        make_node(
                            "Sigmoid",
                            [margin],
                            [confidence_output],
                            [],
                            name=name.replace("Softmax", "MarginSigmoid") if name else "",
                        ),
                    ])
                else:
                    rewritten_nodes.append(nb)
                    rewritten_nodes.append(make_node(
                        "ArgMax", ins, [token_output],
                        [make_int_attr("axis", axis), make_int_attr("keepdims", 0)],
                        name=name.replace("Softmax", "ArgMax") if name else "",
                    ))
                    if target_opset >= 18:
                        axes_name = f"__confidence_axes_{len(extra_confidence_initializers)}"
                        extra_confidence_initializers.append(
                            encode_tensor(axes_name, [1], TENSOR_INT64, pack_int64_le([axis]))
                        )
                        reduce_max_inputs = [outs[0], axes_name]
                        reduce_attrs = [make_int_attr("keepdims", 0)]
                    else:
                        reduce_max_inputs = [outs[0]]
                        reduce_attrs = [make_int_attr("keepdims", 0), make_ints_attr("axes", [axis])]
                    rewritten_nodes.append(make_node(
                        "ReduceMax", reduce_max_inputs, [confidence_output], reduce_attrs,
                        name=name.replace("Softmax", "Confidence") if name else "",
                    ))
            else:
                rewritten_nodes.append(nb)
        if not replaced_axes:
            raise ValueError("No output Softmax node found to replace with ArgMax")
        nodes = rewritten_nodes
        initializers.extend(extra_confidence_initializers)
        new_outputs = []
        for ob in outputs:
            oname = next(vv.decode("utf-8") for vf, vw, vv, _ in parse_fields(ob) if vf == 1)
            if oname in replaced_axes:
                # Original output name now carries compact confidence [1,T].
                confidence_ob = rewrite_value_info_to_argmax(ob, replaced_axes[oname], TENSOR_FLOAT)
                confidence_ob = b"".join(
                    encode_field_bytes(1, (oname + "_max_probability").encode("utf-8"))
                    if f == 1 and w == 2 else raw
                    for f, w, v, raw in parse_fields(confidence_ob)
                )
                new_outputs.append(confidence_ob)
                token_name = oname + "_argmax_tokens"
                token_info = []
                for f, w, v, raw in parse_fields(ob):
                    if f != 1 or w != 2:
                        token_info.append(raw)
                    else:
                        token_info.append(encode_field_bytes(1, token_name.encode("utf-8")))
                # Retarget type shape/type from Softmax [1,T,C] float to ArgMax [1,T] int64.
                token_ob = b"".join(token_info)
                token_ob = rewrite_value_info_to_argmax(token_ob, replaced_axes[oname])
                new_outputs.append(token_ob)
            else:
                new_outputs.append(ob)
        outputs = new_outputs

    # Step B2: Optional dynamic INT8 MatMul lowering before Conv/Gemm DequantizeLinear packing.
    if dynamic_quant_matmul:
        init_decoded: dict[str, tuple[list[int], int, array | None]] = {}
        for ib in initializers:
            tname, dims, dt, vals = decode_tensor(ib)
            init_decoded[tname] = (dims, dt, vals)
        init_use_counts: dict[str, int] = {}
        for nb in nodes:
            _, _, ins, _, _ = decode_node(nb)
            for x in ins:
                if x in init_decoded:
                    init_use_counts[x] = init_use_counts.get(x, 0) + 1

        dq_quantized: dict[str, tuple[bytes, bytes, bytes]] = {}
        removed_dq_inits: set[str] = set()
        rewritten_nodes = []
        dq_counter = 0
        for nb in nodes:
            name, op, ins, outs, _ = decode_node(nb)
            if op == "MatMul" and len(ins) == 2 and len(outs) == 1 and ins[1] in init_decoded:
                wname = ins[1]
                dims, dt, vals = init_decoded[wname]
                if dt == TENSOR_FLOAT and len(dims) == 2 and vals is not None:
                    if wname not in dq_quantized:
                        init_q, init_s, _ = quantize_weight_per_channel(wname, dims, vals, -1)
                        init_z = encode_tensor(f"{wname}_dqzero", [], TENSOR_INT8, b"\x00")
                        dq_quantized[wname] = (init_q, init_s, init_z)
                        if init_use_counts.get(wname, 0) <= 1:
                            removed_dq_inits.add(wname)
                    else:
                        # Decrement if used by another MatMul; keep original initializer if any non-MatMul uses it.
                        pass
                    prefix = f"{outs[0]}__dq_{dq_counter}"
                    dq_counter += 1
                    rewritten_nodes.extend([
                        make_node(
                            "DynamicQuantizeLinear",
                            [ins[0]],
                            [f"{prefix}_u8", f"{prefix}_xs", f"{prefix}_xz"],
                            [],
                            name=f"{name}__dql" if name else "",
                        ),
                        make_node(
                            "MatMulInteger",
                            [f"{prefix}_u8", f"{wname}_int8", f"{prefix}_xz", f"{wname}_dqzero"],
                            [f"{prefix}_i32"],
                            [],
                            name=f"{name}__mmi" if name else "",
                        ),
                        make_node(
                            "Cast",
                            [f"{prefix}_i32"],
                            [f"{prefix}_f32"],
                            [make_int_attr("to", TENSOR_FLOAT)],
                            name=f"{name}__cast" if name else "",
                        ),
                        make_node(
                            "Mul",
                            [f"{prefix}_xs", f"{wname}_scale"],
                            [f"{prefix}_scale"],
                            [],
                            name=f"{name}__mul_scale" if name else "",
                        ),
                        make_node(
                            "Mul",
                            [f"{prefix}_f32", f"{prefix}_scale"],
                            outs,
                            [],
                            name=name,
                        ),
                    ])
                    continue
            rewritten_nodes.append(nb)

        if dq_quantized:
            nodes = rewritten_nodes
            remaining_uses = {
                x
                for nb in nodes
                for x in decode_node(nb)[2]
                if x in init_decoded
            }
            kept_inits = [
                ib
                for ib in initializers
                if decode_tensor_raw(ib)[0] not in removed_dq_inits or decode_tensor_raw(ib)[0] in remaining_uses
            ]
            for iq, isc, iz in dq_quantized.values():
                kept_inits.extend([iq, isc, iz])
            initializers = kept_inits

    # Step C: Per-output-channel INT8 weight storage + DequantizeLinear (opset >= 13).
    if quantize_weights:

        candidates: dict[str, int] = {}
        for nb in nodes:
            _, op, ins, _, _ = decode_node(nb)
            if op in WEIGHT_AXIS and len(ins) >= 2:
                candidates.setdefault(ins[1], WEIGHT_AXIS[op])

        init_by_name: dict[str, bytes] = {}
        for ib in initializers:
            tname, _, _, _ = decode_tensor(ib)
            init_by_name[tname] = ib

        # Handle both Constant-wrapped weights (ch_PP-OCRv4 style) and direct initializers (PP-OCRv6 style).
        quantized_inits: dict[str, tuple[bytes, bytes, bytes]] = {}
        deq_emitted: set[str] = set()
        rewritten_nodes = []
        removed_inits: set[str] = set()

        for tname, ib in list(init_by_name.items()):
            if tname in candidates:
                _, dims, dt, vals = decode_tensor(ib)
                if dt == TENSOR_FLOAT and len(dims) >= 2 and vals is not None:
                    quantized_inits[tname] = quantize_weight_per_channel(
                        tname, dims, vals, candidates[tname]
                    )
                    removed_inits.add(tname)

        for nb in nodes:
            name, op, ins, outs, attrs = decode_node(nb)
            if op == "Constant" and len(outs) == 1 and outs[0] in candidates:
                wname = outs[0]
                t_val = None
                for ab in attrs:
                    an, _, _, tv = attr_name_and_info(ab)
                    if an == "value":
                        t_val = tv
                        break
                if t_val is not None:
                    _, dims, dt, vals = decode_tensor(t_val)
                    if dt == TENSOR_FLOAT and len(dims) >= 2 and vals is not None:
                        axis = candidates[wname] % len(dims)
                        iq, isc, iz = quantize_weight_per_channel(wname, dims, vals, axis)
                        initializers.extend([iq, isc, iz])
                        rewritten_nodes.append(
                            make_node(
                                "DequantizeLinear",
                                [f"{wname}_int8", f"{wname}_scale", f"{wname}_zero"],
                                [wname],
                                [make_int_attr("axis", axis)],
                            )
                        )
                        deq_emitted.add(wname)
                        continue
            if op in WEIGHT_AXIS and len(ins) >= 2 and ins[1] in quantized_inits and ins[1] not in deq_emitted:
                wname = ins[1]
                _, dims, _, _ = decode_tensor(init_by_name[wname])
                axis = candidates[wname] % len(dims)
                rewritten_nodes.append(
                    make_node(
                        "DequantizeLinear",
                        [f"{wname}_int8", f"{wname}_scale", f"{wname}_zero"],
                        [wname],
                        [make_int_attr("axis", axis)],
                    )
                )
                deq_emitted.add(wname)
            rewritten_nodes.append(nb)

        nodes = rewritten_nodes
        kept_inits = []
        for ib in initializers:
            tname, _, _, _ = decode_tensor(ib)
            if tname not in removed_inits:
                kept_inits.append(ib)
        for tname, (iq, isc, iz) in quantized_inits.items():
            if tname in deq_emitted:
                kept_inits.extend([iq, isc, iz])
            else:
                kept_inits.append(init_by_name[tname])
        initializers = kept_inits

    # Rebuild GraphProto preserving field order semantics (drop stale value_info when graph topology or outputs changed).
    strip_value_info = optimize_graph or replace_softmax_with_argmax or dynamic_quant_matmul or (prune_dicts is not None)
    rebuilt_graph = []
    nodes_written = False
    inits_written = False
    outputs_written = False
    for f, w, v, raw in graph_fields:
        if f == 1:
            if not nodes_written:
                rebuilt_graph.extend(encode_field_bytes(1, n) for n in nodes)
                nodes_written = True
        elif f == 5:
            if not inits_written:
                rebuilt_graph.extend(encode_field_bytes(5, i) for i in initializers)
                inits_written = True
        elif f == 12:
            if not outputs_written:
                rebuilt_graph.extend(encode_field_bytes(12, o) for o in outputs)
                outputs_written = True
        elif f == 13 and strip_value_info:
            continue
        else:
            rebuilt_graph.append(raw)
    if not nodes_written:
        rebuilt_graph.extend(encode_field_bytes(1, n) for n in nodes)
    if not inits_written:
        rebuilt_graph.extend(encode_field_bytes(5, i) for i in initializers)
    if not outputs_written:
        rebuilt_graph.extend(encode_field_bytes(12, o) for o in outputs)
    new_graph_bytes = b"".join(rebuilt_graph)

    # Rebuild ModelProto.
    upgrade_model_header = (target_opset > current_opset) or quantize_weights or dynamic_quant_matmul
    rebuilt_model = []
    for f, w, v, raw in model_fields:
        if f == 1 and w == 0 and upgrade_model_header and isinstance(v, int):
            rebuilt_model.append(encode_field_varint(1, max(v, 7)))
        elif f == 7 and w == 2:
            rebuilt_model.append(encode_field_bytes(7, new_graph_bytes))
        elif f == 8 and w == 2 and upgrade_model_header:
            assert isinstance(v, bytes)
            op_fields = parse_fields(v)
            dom = next((ov.decode("utf-8") for of, ow, ov, _ in op_fields if of == 1 and ow == 2), "")
            if dom == "":
                op_bytes = encode_field_bytes(1, b"") + encode_field_varint(2, target_opset)
                rebuilt_model.append(encode_field_bytes(8, op_bytes))
            else:
                rebuilt_model.append(raw)
        else:
            rebuilt_model.append(raw)

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(b"".join(rebuilt_model))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument(
        "--quantize-weights",
        dest="quantize_weights",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Store 2D+ Conv/MatMul weights as per-output-channel INT8 with DequantizeLinear.",
    )
    parser.add_argument(
        "--optimize-graph",
        action="store_true",
        help="Eliminate Identity, cancel redundant Transpose/Squeeze pairs, fold Conv/ConvTranspose biases and BatchNorm.",
    )
    parser.add_argument(
        "--replace-softmax-with-argmax",
        action="store_true",
        help="Emit INT64 ArgMax tokens and compact per-step confidence for greedy CTC decoding.",
    )
    parser.add_argument(
        "--confidence-mode",
        choices=["softmax", "topk"],
        default="softmax",
        help="Compute compact confidence via Softmax+ReduceMax or TopK top-2 logit margin.",
    )
    parser.add_argument(
        "--dynamic-quant-matmul",
        action="store_true",
        help="Rewrite constant-weight FP32 MatMul ops to DynamicQuantizeLinear + MatMulInteger.",
    )
    parser.add_argument(
        "--prune-non-cjk",
        nargs=2,
        type=Path,
        metavar=("DICT_IN", "DICT_OUT"),
        help="Slice final classifier head and dictionary to exclude CJK/kana/fullwidth blocks.",
    )
    parser.add_argument(
        "--extract-dict",
        nargs=2,
        type=Path,
        metavar=("INFERENCE_YML", "DICT_OUT"),
        help="Extract character_dict entries from an official PaddleOCR inference.yml file.",
    )
    args = parser.parse_args()

    if args.extract_dict is not None:
        yml_in, dict_out = args.extract_dict
        chars = extract_dict_from_yml(yml_in)
        dict_out.parent.mkdir(parents=True, exist_ok=True)
        dict_out.write_text("\n".join(chars) + "\n", encoding="utf-8")
        print(f"Wrote {len(chars)} characters to {dict_out}")
        if args.source is None and args.output is None:
            return

    if args.source is None or args.output is None:
        parser.error("source and output paths are required when transforming a model")

    transform_model(
        source=args.source,
        output=args.output,
        quantize_weights=args.quantize_weights,
        replace_softmax_with_argmax=args.replace_softmax_with_argmax,
        prune_dicts=tuple(args.prune_non_cjk) if args.prune_non_cjk else None,
        optimize_graph=args.optimize_graph,
        confidence_mode=args.confidence_mode,
        dynamic_quant_matmul=args.dynamic_quant_matmul,
    )


if __name__ == "__main__":
    main()
