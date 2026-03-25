import json
from collections import Counter

types = Counter()
sap_counter = Counter()
total = 0
errors = 0
interesting = []

for line in open('logs/p25_data_capture.jsonl', 'r'):
    line = line.strip()
    if not line:
        continue
    total += 1
    try:
        obj = json.loads(line, strict=False)
    except json.JSONDecodeError:
        errors += 1
        continue

    typ = obj.get('type', '?')
    cls = obj.get('class', '?')
    sap = obj.get('sap', '')
    types[f'{typ}/{cls}'] += 1
    if sap:
        sap_counter[sap] += 1

    # Collect non-noise entries
    if sap not in ('MOTOROLA_OSP_SYSTEM_LOADING', 'MOTOROLA_OSP_TDMA_DATA_CHANNEL', ''):
        interesting.append(obj)
    elif typ in ('PDU_PACKET', 'SNDCP', 'LSD'):
        interesting.append(obj)

print(f'=== TOTAL LINES: {total}, PARSE ERRORS: {errors} ===')
print()
print('=== MESSAGE TYPE BREAKDOWN ===')
for k, v in types.most_common(20):
    pct = v / total * 100
    print(f'  {k}: {v} ({pct:.1f}%)')

print()
print('=== SAP/OPCODE BREAKDOWN ===')
for k, v in sap_counter.most_common(20):
    print(f'  {k}: {v}')

print()
print(f'=== INTERESTING ENTRIES: {len(interesting)} ===')
for obj in interesting[:30]:
    typ = obj.get('type', '')
    cls = obj.get('class', '')
    sap = obj.get('sap', '')
    frm = obj.get('from', '')
    to = obj.get('to', '')
    proto = obj.get('proto', '')
    strings = obj.get('strings', [])
    det = obj.get('details', '')[:150]
    hex_val = obj.get('hex', '')[:60]
    ts = obj.get('ts', '')
    print(f'  [{typ}] cls={cls} sap={sap}')
    print(f'    from={frm} to={to} proto={proto}')
    print(f'    hex={hex_val}')
    if strings:
        print(f'    strings={strings}')
    print(f'    details={det}')
    print()
