#!/usr/bin/env python3
"""Convert a stock PP-OCRv4 ONNX model to per-output-channel INT8 weight storage."""

import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

WEIGHT_AXIS = {"Conv": 0, "ConvTranspose": 1, "MatMul": -1, "Gemm": -1}


def quantize(source: Path, output: Path) -> None:
    model = onnx.version_converter.convert_version(onnx.load(source), 13)
    graph = model.graph
    nodes = list(graph.node)
    candidates = {node.input[1]: WEIGHT_AXIS[node.op_type] for node in nodes
                  if node.op_type in WEIGHT_AXIS and len(node.input) >= 2}
    rewritten = []
    for node in nodes:
        name = node.output[0]
        if node.op_type != "Constant" or name not in candidates:
            rewritten.append(node)
            continue
        tensor = next(attr.t for attr in node.attribute if attr.name == "value")
        if tensor.data_type != TensorProto.FLOAT or len(tensor.dims) < 2:
            rewritten.append(node)
            continue
        weight = numpy_helper.to_array(tensor)
        axis = candidates[name] % weight.ndim
        reduction = tuple(i for i in range(weight.ndim) if i != axis)
        scale = np.maximum(np.max(np.abs(weight), axis=reduction) / 127.0, 1e-12)
        broadcast = [1] * weight.ndim
        broadcast[axis] = -1
        quantized = np.round(weight / scale.reshape(broadcast)).clip(-127, 127).astype(np.int8)
        graph.initializer.extend([
            numpy_helper.from_array(quantized, name + "_int8"),
            numpy_helper.from_array(scale, name + "_scale"),
            numpy_helper.from_array(np.zeros(scale.shape, dtype=np.int8), name + "_zero"),
        ])
        rewritten.append(helper.make_node(
            "DequantizeLinear", [name + "_int8", name + "_scale", name + "_zero"], [name], axis=axis,
        ))
    del graph.node[:]
    graph.node.extend(rewritten)
    onnx.checker.check_model(model)
    onnx.save(model, output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    quantize(args.source, args.output)
