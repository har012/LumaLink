import base64
import json
from pathlib import Path
import unittest

from optical_lab import Fountain, JavaRandom, Peeler, capacity, indices, packet_hash, simulate


class OpticalLabTest(unittest.TestCase):
    def test_java_random_reference(self):
        # Published Java Random(0).nextInt() first result's unsigned upper bits.
        self.assertEqual(JavaRandom(0).next_bits(32), 3139482720)

    def test_shared_protocol_vectors(self):
        fixture = json.loads((Path(__file__).parent / "fixtures/protocol-v2.json").read_text(encoding="utf-8"))
        data = base64.b64decode(fixture["file_base64"])
        sender = Fountain(data)
        receiver = Peeler(len(sender.blocks))
        for vector in fixture["vectors"]:
            packet = vector["packet"]
            selected, value = sender.symbol(packet["q"])
            self.assertEqual(selected, vector["indices"])
            self.assertEqual(value.to_bytes(420, "little"), base64.urlsafe_b64decode(packet["d"]))
            self.assertEqual(packet_hash(packet), packet["c"])
            receiver.accept(packet["q"], selected, value)
        self.assertEqual(receiver.output(len(data)), data)

    def test_loss_late_join_and_duplicates_reconstruct_exact_bytes(self):
        data = bytes(range(256)) * 20
        for seed in range(10):
            result = simulate(data, seed, .3, .4, 37, 7, 13, 9, 2000)
            self.assertTrue(result["verified"], result)

    def test_total_loss_is_reported_as_failure(self):
        result = simulate(b"abc", 0, 1, 0, 0, 0, 0, 9, 20)
        self.assertFalse(result["verified"])
        self.assertEqual(result["dropped"], 20)
        self.assertEqual(result["unique_accepted"], 0)

    def test_partial_reorder_window_flushes(self):
        result = simulate(b"abc", 0, 0, 0, 0, 0, 0, 9, 1)
        self.assertTrue(result["verified"])

    def test_duplicate_and_contradiction(self):
        receiver = Peeler(2)
        self.assertTrue(receiver.accept(0, [0], 1))
        self.assertFalse(receiver.accept(0, [0], 1))
        with self.assertRaisesRegex(ValueError, "contradictory"):
            receiver.accept(2, [0], 2)

    def test_capacity_units_and_target(self):
        result = capacity(60, 420, 1, 1, 1, 2_000_000, 190_000)
        self.assertEqual(result["modeled_file_bytes_per_second"], 25200)
        self.assertAlmostEqual(result["modeled_transfer_seconds"], 79.365079365)
        self.assertFalse(result["meets_modeled_target"])
        self.assertEqual(capacity(60, 420, 1, .8, 1.1, 2_000_000, 190_000)
                         ["required_payload_bytes_per_display_frame"], 4355)

    def test_systematic_and_java_int_bounds(self):
        self.assertEqual(indices(3, 2), [2])
        with self.assertRaises(ValueError):
            indices(3, 2**31)


if __name__ == "__main__":
    unittest.main()
