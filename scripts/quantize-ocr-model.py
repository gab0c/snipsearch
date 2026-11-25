#!/usr/bin/env python3
"""Prepare and INT8-weight-pack PP-OCR ONNX models and dictionaries using Python stdlib."""

from __future__ import annotations

import argparse
from array import array
from pathlib import Path
import struct
import sys

WEIGHT_AXIS = {"Conv": 0, "ConvTranspose": 1, "MatMul": -1, "Gemm": -1}

TENSOR_FLOAT = 1
TENSOR_INT8 = 3
TENSOR_INT64 = 7

ATTR_INT = 2


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


def make_int_attr(name: str, value: int) -> bytes:
    return (
        encode_field_bytes(1, name.encode("utf-8"))
        + encode_field_varint(3, value)
        + encode_field_varint(20, ATTR_INT)
    )


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


def rewrite_value_info_to_argmax(vi_bytes: bytes, drop_axis: int) -> bytes:
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
                    tensor_type_parts.append(encode_field_varint(1, TENSOR_INT64))
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


def transform_model(
    source: Path,
    output: Path,
    quantize_weights: bool,
    replace_softmax_with_argmax: bool,
    prune_dicts: tuple[Path, Path] | None,
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

    # Step A: Optional CJK class pruning (before weight quantization so linear_1 scales match pruned class count).
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

    # Step B: Replace trailing Softmax with ArgMax(axis, keepdims=0).
    if replace_softmax_with_argmax:
        output_names = {
            next(vv.decode("utf-8") for vf, vw, vv, _ in parse_fields(o) if vf == 1)
            for o in outputs
        }
        rewritten_nodes = []
        replaced_axes: dict[str, int] = {}
        for nb in nodes:
            name, op, ins, outs, attrs = decode_node(nb)
            if op == "Softmax" and len(outs) == 1 and outs[0] in output_names:
                axis = 1
                for ab in attrs:
                    an, ai, _, _ = attr_name_and_info(ab)
                    if an == "axis" and ai is not None:
                        axis = ai
                replaced_axes[outs[0]] = axis
                rewritten_nodes.append(
                    make_node(
                        "ArgMax",
                        ins,
                        outs,
                        [make_int_attr("axis", axis), make_int_attr("keepdims", 0)],
                        name=name.replace("Softmax", "ArgMax") if name else "",
                    )
                )
            else:
                rewritten_nodes.append(nb)
        if not replaced_axes:
            raise ValueError("No output Softmax node found to replace with ArgMax")
        nodes = rewritten_nodes
        new_outputs = []
        for ob in outputs:
            oname = next(vv.decode("utf-8") for vf, vw, vv, _ in parse_fields(ob) if vf == 1)
            if oname in replaced_axes:
                new_outputs.append(rewrite_value_info_to_argmax(ob, replaced_axes[oname]))
            else:
                new_outputs.append(ob)
        outputs = new_outputs

    # Step C: Per-output-channel INT8 weight storage + DequantizeLinear (opset >= 13).
    target_opset = current_opset
    if quantize_weights:
        target_opset = max(current_opset, 13)
        if current_opset < 13:
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
            nodes = upgraded_nodes
            initializers.extend(extra_axes_inits)

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

    # Rebuild GraphProto preserving field order semantics.
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
    rebuilt_model = []
    for f, w, v, raw in model_fields:
        if f == 1 and w == 0 and quantize_weights and isinstance(v, int):
            rebuilt_model.append(encode_field_varint(1, max(v, 7)))
        elif f == 7 and w == 2:
            rebuilt_model.append(encode_field_bytes(7, new_graph_bytes))
        elif f == 8 and w == 2 and quantize_weights:
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
        "--replace-softmax-with-argmax",
        action="store_true",
        help="Replace output Softmax with INT64 ArgMax(keepdims=0) for greedy CTC decoding.",
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
    )


if __name__ == "__main__":
    main()
