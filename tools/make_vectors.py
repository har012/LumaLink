"""Generate shared vectors; Kotlin and Python tests independently verify them."""
import base64
import hashlib
import json
from pathlib import Path
from optical_lab import Fountain, packet_hash

data = bytes((i * 73 + 19) % 256 for i in range(821))
sender = Fountain(data)
vectors = []
for seq in [0, 1, 3, 17, 99, 2147483647, 2]:
    selected, value = sender.symbol(seq)
    packet = dict(v=2, id="12345678-1234-1234-1234-123456789abc", n="image-光-🚀.bin",
                  s=len(data), k=len(sender.blocks), h=hashlib.sha256(data).hexdigest(),
                  q=seq, d=base64.urlsafe_b64encode(value.to_bytes(420, "little")).decode())
    packet["c"] = packet_hash(packet)
    vectors.append(dict(indices=selected, packet=packet))
path = Path(__file__).parent / "fixtures/protocol-v2.json"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(dict(file_base64=base64.b64encode(data).decode(), vectors=vectors),
                           ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
