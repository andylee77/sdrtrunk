#!/usr/bin/env python3
"""Extract and analyze stripped data payloads from DATCH timeslots."""
import json
from collections import defaultdict

records = []
with open('logs/p25_data_Clay_County_20260325.jsonl', 'r', encoding='utf-8', errors='replace') as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
            if rec.get('type') == 'DATCH_RAW':
                records.append(rec)
        except json.JSONDecodeError:
            pass

records.sort(key=lambda r: r.get('ts', 0))
sessions = []
cur = []
for r in records:
    if cur and r['ts'] - cur[-1]['ts'] > 2000:
        sessions.append(cur)
        cur = []
    cur.append(r)
if cur:
    sessions.append(cur)

session = sessions[0]
all_bytes = [bytes.fromhex(r['hex'].replace(' ', '')) for r in session]

data_fams = defaultdict(list)
for i, b in enumerate(all_bytes):
    fam = b[1:3].hex()
    if fam not in ['ec27', 'b684']:
        data_fams[fam].append((i, b))

print('=== DATA FAMILY PAYLOAD ANALYSIS (Session 1) ===')
print()

for fam, entries in sorted(data_fams.items(), key=lambda x: -len(x[1])):
    print(f'Family {fam} ({len(entries)} slots):')
    for idx, b in entries[:3]:
        payload = bytearray()
        for j in range(40):
            if j not in [0, 9, 30, 39]:
                payload.append(b[j])
        ts = 'TS1' if 'TS1' in session[idx].get('sap', '') else 'TS2'
        print(f'  [{idx:4d}] {ts} raw:     {b.hex(" ")}')
        print(f'         stripped: {bytes(payload).hex(" ")}')
    if len(entries) >= 2:
        p0 = bytearray()
        p1 = bytearray()
        for j in range(40):
            if j not in [0, 9, 30, 39]:
                p0.append(entries[0][1][j])
                p1.append(entries[1][1][j])
        xor = bytes(a ^ b for a, b in zip(p0, p1))
        diff = sum(1 for x in xor if x != 0)
        print(f'  Intra-fam XOR: {diff}/{len(xor)} bytes differ')
    print()

# Burst 2 analysis
print('=== BURST 2 (slots 41-68) CONCATENATED ===')
burst = []
for i in range(35, 70):
    if i < len(all_bytes):
        fam = all_bytes[i][1:3].hex()
        if fam not in ['ec27', 'b684']:
            burst.append((i, all_bytes[i]))

concat = bytearray()
for idx, b in burst:
    for j in range(40):
        if j not in [0, 9, 30, 39]:
            concat.append(b[j])

print(f'Burst slots: {[i for i, _ in burst]}')
print(f'Concat payload ({len(concat)} bytes):')
for off in range(0, len(concat), 36):
    chunk = concat[off:off + 36]
    print(f'  +{off:3d}: {bytes(chunk).hex(" ")}')

print()
print(f'0x45 at: {[i for i, v in enumerate(concat) if v == 0x45]}')
print(f'0x60 at: {[i for i, v in enumerate(concat) if v == 0x60]}')
print(f'Zero bytes: {sum(1 for v in concat if v == 0)}')
