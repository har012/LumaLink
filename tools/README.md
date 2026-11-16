# Python optical lab

The Android app remains Kotlin. This Python 3.10+ desktop toolkit supports offline
fountain experiments and capacity analysis. It has no third-party dependencies.
Python does not run inside the Android app and is not required on either phone.

From the repository root:

```sh
python -m unittest discover -s tools -v
python tools/optical_lab.py simulate --trials 30 --output .dist/simulation.json
python tools/optical_lab.py capacity
```

## Fountain experiments

```sh
# Late join after the 30 systematic blocks of a 12,345-byte file.
python tools/optical_lab.py simulate --size 12345 --late-join 30 --trials 100 --max-symbols 2000

# Larger transfer; a finite budget can produce a valid reported failure.
python tools/optical_lab.py simulate --size 2000000 --trials 1 --max-symbols 10000 --output .dist/2mb.json

# Perfect channel, no bursts or duplication.
python tools/optical_lab.py simulate --loss 0 --duplicate 0 --burst-every 0 --burst-length 0 --reorder 1
```

Parameters control random packet loss, periodic burst loss, duplicates, emitted-
symbol reorder windows, missed starting sequences, seeds and per-trial budgets.
The final partial reorder window is flushed. Each trial reconstructs actual bytes
and compares them exactly. Failures are retained in the JSON, never omitted from
the success rate. Median expansion includes only successful trials and is null
when all trials fail. Simulation failures do not cause a nonzero process exit;
invalid CLI arguments do. Python's seeded channel RNG is separate from Java's
protocol RNG; seeds do not reproduce the Kotlin regression test's channel schedule.

Results report emitted symbols since the receiver joins, dropped symbols,
delivered duplicates, unique accepted symbols, recovered blocks, pending-equation
peak, evictions, and wall-clock simulator runtime. Expansion is accepted/source
symbols, not emitted/source symbols. Reorder-window batching can count emitted
symbols that were still queued at completion. The model stops at the Kotlin
100,000 unique-packet bound and evicts at 4,096 pending equations.

`Fountain`, `Peeler`, `indices` and `simulate` can be imported for algorithm
experiments. The baseline uses the Kotlin degree distribution, Java Random
sequence selection, equation ordering and bounded eviction policy. The Python
decoder uses integer XOR instead of byte loops, so its runtime and memory are
**not predictions of Android decoder runtime or memory**. It takes validated
symbols; JSON parsing and Android session validation remain covered by Kotlin tests.

## Capacity analysis

```sh
python tools/optical_lab.py capacity --fps 60 --payload 420 --acceptance 1 --expansion 1
python tools/optical_lab.py capacity --fps 60 --payload 2200 --symbols-per-frame 2 --acceptance 0.8 --expansion 1.1
```

The model uses `FPS * symbols/frame * payload * acceptance / expansion` and
decimal bytes. `--size` defaults to 2,000,000 and `--target` to 190,000 bytes/s.
Acceptance is the unique accepted fraction, not camera FPS. The second command
is a hypothetical multi-QR capacity experiment, not an implemented app mode.
It does not check QR encodability, exposure, display transitions or actual optical
acceptance. Camera/display hardware measurements are still required.

## Cross-language checks

`fixtures/protocol-v2.json` contains deterministic bytes, systematic and repair
payloads, index sets, full packet hashes, a Unicode/non-BMP filename and the maximum
Java Int sequence. Both `ProtocolInteropTest.kt` and Python tests independently
verify the same fixture against their implementations. To deliberately regenerate
vectors, run `python tools/make_vectors.py`, review the diff and rerun both suites.
Normal tests never rewrite fixtures. Do not change the protocol RNG to Python's
`random.Random`; that would break compatibility with Android.

GitHub Actions runs the Python tests and uploads a default simulation JSON artifact.
See ../docs/benchmark-protocol.md for physical-device validation.
