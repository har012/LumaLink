"""Offline LumaLink experiments. Python 3.10+, standard library only.

Simulates packet erasures and the current Kotlin peeling algorithm, not cameras,
QR decoding, phone timing, or authenticated reception.
"""
from __future__ import annotations

import argparse
from collections import deque
import hashlib
import json
import math
from pathlib import Path
import random
import statistics
import time

BLOCK = 420
MAX_BYTES = 10_000_000
MAX_PACKETS = 100_000
MAX_EQUATIONS = 4096


class JavaRandom:
    """java.util.Random's 48-bit LCG, including nextInt rejection sampling."""
    def __init__(self, seed: int):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next_bits(self, bits: int) -> int:
        self.seed = (self.seed * 0x5DEECE66D + 11) & ((1 << 48) - 1)
        return self.seed >> (48 - bits)

    def next_int(self, bound: int) -> int:
        if not 0 < bound <= 0x7FFFFFFF:
            raise ValueError("bound must fit a positive Java Int")
        if bound & (bound - 1) == 0:
            return (bound * self.next_bits(31)) >> 31
        while True:
            bits = self.next_bits(31)
            value = bits % bound
            if bits - value + bound - 1 <= 0x7FFFFFFF:
                return value


def indices(k: int, seq: int) -> list[int]:
    if k < 1 or not 0 <= seq <= 0x7FFFFFFF:
        raise ValueError("invalid block count or sequence")
    if seq < k:
        return [seq]
    rng = JavaRandom(seq ^ 0x4C554D414C494E4B)
    roll = rng.next_int(100)
    degree = min(k, 1 if roll < 45 else 2 if roll < 75 else 3 if roll < 90 else 4 if roll < 97 else 8)
    result: set[int] = set()
    while len(result) < degree:
        result.add(rng.next_int(k))
    return sorted(result)


def packet_hash(packet: dict) -> str:
    fields = []
    for key in ("v", "id", "n", "s", "k", "h", "q", "d"):
        value = str(packet[key])
        # Kotlin String.length counts UTF-16 code units, including surrogate pairs.
        length = len(value.encode("utf-16-le")) // 2
        fields.append(f"{length}:{value}")
    return hashlib.sha256("".join(fields).encode("utf-8")).hexdigest()


class Fountain:
    def __init__(self, data: bytes):
        if not 1 <= len(data) <= MAX_BYTES:
            raise ValueError("file must contain 1..10,000,000 bytes")
        self.data = data
        self.blocks = [int.from_bytes(data[i:i + BLOCK].ljust(BLOCK, b"\0"), "little")
                       for i in range(0, len(data), BLOCK)]

    def symbol(self, seq: int) -> tuple[list[int], int]:
        selected = indices(len(self.blocks), seq)
        value = 0
        for i in selected:
            value ^= self.blocks[i]
        return selected, value


class Peeler:
    """Same equation ordering/eviction as Receiver; integer XOR is a lab optimization.

    Inputs are already validated symbols, so this does not model packet parsing.
    """
    def __init__(self, count: int):
        self.blocks: list[int | None] = [None] * count
        self.equations: list[tuple[set[int], int]] = []
        self.seen: set[int] = set()
        self.solved = 0
        self.peak_equations = 0
        self.evicted = 0

    def accept(self, seq: int, selected: list[int], value: int) -> bool:
        if seq in self.seen or self.solved == len(self.blocks):
            return False
        if len(self.seen) >= MAX_PACKETS:
            raise ValueError("packet limit reached")
        self.seen.add(seq)
        unknown = set(selected)
        for i in list(unknown):
            if self.blocks[i] is not None:
                value ^= self.blocks[i]
                unknown.remove(i)
        if not unknown and value:
            raise ValueError("contradictory packet")
        if unknown:
            if len(self.equations) >= MAX_EQUATIONS:
                self.equations.pop(0)
                self.evicted += 1
            self.equations.append((unknown, value))
            self.peak_equations = max(self.peak_equations, len(self.equations))
        changed = True
        while changed:
            changed = False
            pending = []
            for unknown, value in self.equations:
                for i in list(unknown):
                    if self.blocks[i] is not None:
                        value ^= self.blocks[i]
                        unknown.remove(i)
                if not unknown:
                    if value:
                        raise ValueError("contradictory equations")
                elif len(unknown) == 1:
                    self.blocks[next(iter(unknown))] = value
                    self.solved += 1
                    changed = True
                else:
                    pending.append((unknown, value))
            self.equations = pending
        return True

    def output(self, size: int) -> bytes | None:
        if self.solved != len(self.blocks):
            return None
        return b"".join(value.to_bytes(BLOCK, "little") for value in self.blocks)[:size]


def simulate(data: bytes, seed: int, loss: float, duplicate: float, burst_every: int,
             burst_length: int, late_join: int, reorder: int, max_symbols: int) -> dict:
    rng = random.Random(seed)
    sender = Fountain(data)
    receiver = Peeler(len(sender.blocks))
    queued: list[int] = []
    deliveries: deque[int] = deque()
    emitted = dropped = decoded = 0
    start = time.perf_counter()
    for seq in range(late_join, late_join + max_symbols):
        emitted += 1
        if (burst_every and (seq - late_join) % burst_every < burst_length) or rng.random() < loss:
            dropped += 1
        else:
            queued.append(seq)
        # Flush by emitted-window boundary, including a partially filled final window.
        if emitted % reorder == 0 or emitted == max_symbols:
            rng.shuffle(queued)
            for q in queued:
                deliveries.append(q)
                if rng.random() < duplicate:
                    deliveries.append(q)
            queued.clear()
        while deliveries:
            q = deliveries.popleft()
            selected, value = sender.symbol(q)
            receiver.accept(q, selected, value)
            decoded += 1
            if receiver.solved == len(sender.blocks):
                break
        if receiver.solved == len(sender.blocks) or len(receiver.seen) >= MAX_PACKETS:
            break
    output = receiver.output(len(data))
    verified = output == data and output is not None
    return dict(seed=seed, verified=verified, source_blocks=len(sender.blocks), emitted=emitted,
                dropped=dropped, delivered_including_duplicates=decoded,
                unique_accepted=len(receiver.seen), solved=receiver.solved,
                expansion=len(receiver.seen) / len(sender.blocks),
                peak_pending_equations=receiver.peak_equations, evicted_equations=receiver.evicted,
                simulation_seconds=time.perf_counter() - start)


def capacity(fps: float, payload: int, symbols: int, acceptance: float, expansion: float,
             size: int, target: float) -> dict:
    throughput = fps * payload * symbols * acceptance / expansion
    return dict(modeled_file_bytes_per_second=throughput,
                modeled_transfer_seconds=size / throughput if throughput else None,
                target_bytes_per_second=target, meets_modeled_target=throughput >= target,
                required_payload_bytes_per_display_frame=math.ceil(target * expansion / (fps * acceptance)),
                warning="Capacity model only; no measured phone performance.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    sim = commands.add_parser("simulate", help="seeded erasure-channel experiments")
    sim.add_argument("--size", type=int, default=12_345)
    sim.add_argument("--trials", type=int, default=30)
    sim.add_argument("--seed", type=int, default=0)
    sim.add_argument("--loss", type=float, default=0.3)
    sim.add_argument("--duplicate", type=float, default=0.1)
    sim.add_argument("--burst-every", type=int, default=37)
    sim.add_argument("--burst-length", type=int, default=7)
    sim.add_argument("--late-join", type=int, default=0, help="number of initial transmitted sequences missed")
    sim.add_argument("--reorder", type=int, default=9, help="emitted-symbol reorder window")
    sim.add_argument("--max-symbols", type=int, default=10_000, help="per-trial transmitted-symbol budget")
    sim.add_argument("--output", type=Path)
    cap = commands.add_parser("capacity", help="useful-byte capacity calculation")
    cap.add_argument("--fps", type=float, default=60)
    cap.add_argument("--payload", type=int, default=BLOCK)
    cap.add_argument("--symbols-per-frame", type=int, default=1)
    cap.add_argument("--acceptance", type=float, default=0.8)
    cap.add_argument("--expansion", type=float, default=1.1)
    cap.add_argument("--size", type=int, default=2_000_000)
    cap.add_argument("--target", type=float, default=190_000)
    args = parser.parse_args()
    if args.command == "simulate":
        if not (1 <= args.size <= MAX_BYTES and args.trials >= 1 and 0 <= args.loss <= 1
                and 0 <= args.duplicate <= 1 and args.reorder >= 1 and args.max_symbols >= 1
                and args.late_join >= 0 and args.late_join + args.max_symbols <= 0x80000000
                and args.burst_every >= 0 and args.burst_length >= 0
                and (args.burst_length == 0 or 0 < args.burst_length <= args.burst_every)):
            parser.error("invalid simulation bounds or probabilities")
        data = random.Random(42).randbytes(args.size)
        trials = [simulate(data, args.seed + i, args.loss, args.duplicate, args.burst_every,
                           args.burst_length, args.late_join, args.reorder, args.max_symbols)
                  for i in range(args.trials)]
        successes = [t for t in trials if t["verified"]]
        result = dict(kind="simulation_not_device_benchmark", parameters={k: v for k, v in vars(args).items() if k != "output"},
                      successful_trials=len(successes), failed_trials=len(trials) - len(successes),
                      median_success_expansion=statistics.median(t["expansion"] for t in successes) if successes else None,
                      trials=trials)
        rendered = json.dumps(result, indent=2)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered + "\n", encoding="utf-8")
    else:
        if not (all(math.isfinite(v) for v in (args.fps, args.acceptance, args.expansion, args.target))
                and args.fps > 0 and args.payload > 0 and args.symbols_per_frame > 0
                and 0 < args.acceptance <= 1 and args.expansion >= 1 and args.size > 0 and args.target > 0):
            parser.error("invalid capacity bounds")
        rendered = json.dumps(capacity(args.fps, args.payload, args.symbols_per_frame,
                                      args.acceptance, args.expansion, args.size, args.target), indent=2)
    print(rendered)


if __name__ == "__main__":
    main()
