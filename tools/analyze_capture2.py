import json
from collections import Counter

# Deeper analysis of interesting entries
pdu_targets = Counter()
lrrp = []
ars = []
sndcp = []
regroup_add = []
regroup_del = []
lsd_values = Counter()
retry_count = 0
pdu_ok_count = 0

for line in open('logs/p25_data_capture.jsonl', 'r'):
    line = line.strip()
    if not line:
        continue
    try:
        obj = json.loads(line, strict=False)
    except json.JSONDecodeError:
        continue

    typ = obj.get('type', '')
    cls = obj.get('class', '')
    sap = obj.get('sap', '')
    det = obj.get('details', '')
    to_id = obj.get('to', '')
    from_id = obj.get('from', '')

    # PDU responses
    if cls == 'ResponseMessage':
        if to_id:
            pdu_targets[to_id] += 1
        if 'SELECTIVE RETRY' in det:
            retry_count += 1
        elif 'ALL BLOCKS' in det:
            pdu_ok_count += 1

    # LRRP location packets
    if 'LRRP' in det:
        lrrp.append(obj)

    # ARS registration
    if 'ARS' in det:
        ars.append(obj)

    # SNDCP
    if typ == 'SNDCP':
        sndcp.append(obj)

    # Regroup
    if sap == 'MOTOROLA_OSP_GROUP_REGROUP_ADD':
        regroup_add.append(obj)
    elif sap == 'MOTOROLA_OSP_GROUP_REGROUP_DELETE':
        regroup_del.append(obj)

    # LSD
    if typ == 'LSD':
        lsd_values[obj.get('hex', '')] += 1

print('=== PDU DATA CHANNEL ACTIVITY ===')
print(f'  PDU Responses (ALL BLOCKS OK): {pdu_ok_count}')
print(f'  PDU Selective Retries (errors): {retry_count}')
print(f'  Retry rate: {retry_count/(pdu_ok_count+retry_count)*100:.1f}%' if (pdu_ok_count+retry_count) > 0 else '')
print()

print('=== TOP PDU TARGETS (subscriber radios) ===')
for target, count in pdu_targets.most_common(15):
    print(f'  Radio {target}: {count} PDU responses')

print()
print(f'=== LRRP LOCATION REQUESTS: {len(lrrp)} ===')
for obj in lrrp[:5]:
    print(f'  to={obj.get("to","")} details={obj.get("details","")[:120]}')

print()
print(f'=== ARS REGISTRATIONS: {len(ars)} ===')
for obj in ars[:5]:
    print(f'  to={obj.get("to","")} details={obj.get("details","")[:120]}')

print()
print(f'=== SNDCP PACKETS: {len(sndcp)} ===')
for obj in sndcp[:5]:
    print(f'  sap={obj.get("sap","")} to={obj.get("to","")} details={obj.get("details","")[:150]}')

print()
print(f'=== GROUP REGROUP ADD: {len(regroup_add)} ===')
for obj in regroup_add[:10]:
    print(f'  from={obj.get("from","")} to={obj.get("to","")} details={obj.get("details","")[:150]}')

print()
print(f'=== GROUP REGROUP DELETE: {len(regroup_del)} ===')
for obj in regroup_del[:5]:
    print(f'  details={obj.get("details","")[:150]}')

print()
print(f'=== LSD VALUES: {len(lsd_values)} unique ===')
for val, count in lsd_values.most_common(10):
    print(f'  {val}: {count}x')
