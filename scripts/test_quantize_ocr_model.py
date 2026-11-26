"""Dependency-free regression checks for the bundled ONNX OCR graphs."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("quantize", Path(__file__).with_name("quantize-ocr-model.py"))
q = importlib.util.module_from_spec(spec)
spec.loader.exec_module(q)

OCR_ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/ocr"


def _parse_graph(model_path: Path):
    graph = next(v for f, w, v, _ in q.parse_fields(model_path.read_bytes()) if f == 7)
    fields = q.parse_fields(graph)
    nodes = [q.decode_node(v) for f, w, v, _ in fields if f == 1]
    outputs = [v for f, w, v, _ in fields if f == 12]
    value_infos = [v for f, w, v, _ in fields if f == 13]
    return nodes, outputs, value_infos


class BundledOcrModelContractTest(unittest.TestCase):
    def test_bundled_graph_exports_only_compact_float_confidence_and_int64_tokens(self):
        nodes, outputs, _ = _parse_graph(OCR_ASSETS / "PP-OCRv6_tiny_rec_infer.onnx")
        self.assertEqual(2, len(outputs))
        types = []
        for output in outputs:
            typ = next(v for f, w, v, _ in q.parse_fields(output) if f == 2)
            tensor = next(v for f, w, v, _ in q.parse_fields(typ) if f == 1)
            fields = q.parse_fields(tensor)
            types.append(next(v for f, w, v, _ in fields if f == 1))
            shape = next(v for f, w, v, _ in fields if f == 2)
            self.assertEqual(2, sum(f == 1 for f, w, v, _ in q.parse_fields(shape)))
        self.assertEqual([q.TENSOR_FLOAT, q.TENSOR_INT64], types)
        ops = {n[1] for n in nodes}
        self.assertIn("Softmax", ops)
        self.assertIn("ArgMax", ops)
        self.assertIn("DynamicQuantizeLinear", ops)
        self.assertIn("MatMulInteger", ops)
        reducer = next(n for n in nodes if n[1] == "ReduceMax")
        # ReduceMax uses an axes attribute before opset 18 (the bundled graph is 13).
        self.assertEqual(1, len(reducer[2]))
        self.assertTrue(any(q.attr_name_and_info(a)[0] == "axes" for a in reducer[4]))

    def test_bundled_models_have_clean_optimized_topology(self):
        det_nodes, _, det_vis = _parse_graph(OCR_ASSETS / "PP-OCRv6_tiny_det_infer.onnx")
        rec_nodes, _, rec_vis = _parse_graph(OCR_ASSETS / "PP-OCRv6_tiny_rec_infer.onnx")
        self.assertEqual(0, len(det_vis))
        self.assertEqual(0, len(rec_vis))

        for nodes in (det_nodes, rec_nodes):
            ops = [n[1] for n in nodes]
            self.assertNotIn("Identity", ops)
            self.assertNotIn("BatchNormalization", ops)
            # No adjacent inverse Transpose pairs remain.
            producers = {out: n for n in nodes for out in n[3]}
            for _, op, ins, _, attrs in nodes:
                if op != "Transpose" or len(ins) != 1:
                    continue
                prev = producers.get(ins[0])
                if prev is None or prev[1] != "Transpose":
                    continue
                p2 = next((q.attr_name_and_info(a)[2] for a in attrs if q.attr_name_and_info(a)[0] == "perm"), [])
                p1 = next((q.attr_name_and_info(a)[2] for a in prev[4] if q.attr_name_and_info(a)[0] == "perm"), [])
                self.assertFalse(p1 and len(p1) == len(p2) and [p1[k] for k in p2] == list(range(len(p1))))

        conv_transposes = [n for n in det_nodes if n[1] == "ConvTranspose"]
        self.assertTrue(conv_transposes)
        self.assertTrue(all(len(n[2]) == 3 for n in conv_transposes))


if __name__ == "__main__":
    unittest.main()
