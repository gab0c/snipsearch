"""Dependency-free regression checks for the compact recognizer graph contract."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("quantize", Path(__file__).with_name("quantize-ocr-model.py"))
q = importlib.util.module_from_spec(spec)
spec.loader.exec_module(q)


class CompactConfidenceTest(unittest.TestCase):
    def test_bundled_graph_exports_only_compact_float_confidence_and_int64_tokens(self):
        model = Path(__file__).resolve().parents[1] / "app/src/main/assets/ocr/PP-OCRv6_tiny_rec_infer.onnx"
        graph = next(v for f, w, v, _ in q.parse_fields(model.read_bytes()) if f == 7)
        outputs = [v for f, w, v, _ in q.parse_fields(graph) if f == 12]
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
        nodes = [q.decode_node(v) for f, w, v, _ in q.parse_fields(graph) if f == 1]
        self.assertTrue(any(n[1] == "Softmax" for n in nodes))
        reducer = next(n for n in nodes if n[1] == "ReduceMax")
        # ReduceMax uses an axes attribute before opset 18 (the bundled graph is 13).
        self.assertEqual(1, len(reducer[2]))
        self.assertTrue(any(q.attr_name_and_info(a)[0] == "axes" for a in reducer[4]))


if __name__ == "__main__":
    unittest.main()
