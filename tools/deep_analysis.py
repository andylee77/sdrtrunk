import json
from collections import Counter, defaultdict

lrrp = []
ars = []
sndcp = []
regroup_add = []
regroup_del = []
lsd_entries = []
pdu_packets = []
all_entries = []

for line in open('logs/p25_data_capture.jsonl', 'r'):
    line = line.strip()
    if not line:
        continue
    try:
        obj = json.loads(line, strict=False)
    except json.JSONDecodeError:
        continue
    
    all_entries.append(obj)
    typ = obj.get('type', '')
    cls = obj.get('class', '')
    sap = obj.get('sap', '')
    det = obj.get('details', '')

    if 'LRRP' in det:
        lrrp.append(obj)
    if 'ARS' in det:
        ars.append(obj)
    if typ == 'SNDCP':
        sndcp.append(obj)
    if sap == 'MOTOROLA_OSP_GROUP_REGROUP_ADD':
        regroup_add.append(obj)
    elif sap == 'MOTOROLA_OSP_GROUP_REGROUP_DELETE':
        regroup_del.append(obj)
    if typ == 'LSD':
        lsd_entries.append(obj)
    if cls == 'PacketMessage':
        pdu_packets.append(obj)

print('=' * 80)
print('DEEP DATA ANALYSIS')
print('=' * 80)

# === LRRP / GPS ===
print(f'\n{"="*80}')
print(f'LRRP LOCATION DATA — {len(lrrp)} entries')
print(f'{"="*80}')
lrrp_targets = defaultdict(list)
for obj in lrrp:
    det = obj.get('details', '')
    to_id = obj.get('to', '')
    # Extract the LLID
    llid = ''
    if 'LLID:' in det:
        llid = det.split('LLID:')[1].split(' ')[0]
    lrrp_targets[llid].append(det)

for llid, dets in lrrp_targets.items():
    print(f'\n  Radio LLID:{llid} — {len(dets)} LRRP packets')
    # Show unique request types
    types = Counter()
    for d in dets:
        if 'TRIGGERED LOCATION START' in d:
            types['TRIGGERED LOCATION START REQUEST'] += 1
        elif 'TRIGGERED LOCATION STOP' in d:
            types['TRIGGERED LOCATION STOP'] += 1
        elif 'IMMEDIATE LOCATION' in d:
            types['IMMEDIATE LOCATION REQUEST'] += 1
        elif 'LOCATION REPORT' in d:
            types['LOCATION REPORT (response)'] += 1
        else:
            types['OTHER LRRP'] += 1
    for t, c in types.most_common():
        print(f'    {t}: {c}')
    # Show first 3 full details
    for d in dets[:3]:
        print(f'    > {d[:200]}')

# === ALL PDU IP PACKETS ===
print(f'\n{"="*80}')
print(f'ALL IP PACKET DATA — {len(pdu_packets)} entries')
print(f'{"="*80}')
packet_types = Counter()
for obj in pdu_packets:
    det = obj.get('details', '')
    if 'LRRP' in det:
        packet_types['LRRP (Location)'] += 1
    elif 'ARS' in det:
        packet_types['ARS (Registration)'] += 1
    elif 'XCMP' in det or 'TMS' in det:
        packet_types['TMS/XCMP (Text Message)'] += 1
    else:
        packet_types['Other IP Packet'] += 1
        
for t, c in packet_types.most_common():
    print(f'  {t}: {c}')

print('\n  --- Sample non-LRRP/ARS packets ---')
for obj in pdu_packets:
    det = obj.get('details', '')
    if 'LRRP' not in det and 'ARS' not in det:
        print(f'  from={obj.get("from","")} to={obj.get("to","")}')
        print(f'  proto={obj.get("proto","")} hex={obj.get("hex","")[:80]}')
        print(f'  details={det[:200]}')
        print()

# === ARS ===
print(f'\n{"="*80}')
print(f'ARS REGISTRATION DATA — {len(ars)} entries')
print(f'{"="*80}')
ars_radios = defaultdict(list)
for obj in ars:
    det = obj.get('details', '')
    llid = ''
    if 'LLID:' in det:
        llid = det.split('LLID:')[1].split(' ')[0]
    ars_radios[llid].append(det)
for llid, dets in ars_radios.items():
    print(f'  Radio LLID:{llid} — {len(dets)} ARS packets')
    for d in dets[:2]:
        print(f'    > {d[:200]}')

# === SNDCP ===
print(f'\n{"="*80}')
print(f'SNDCP DATA SESSIONS — {len(sndcp)} entries')
print(f'{"="*80}')
for obj in sndcp:
    det = obj.get('details', '')
    to_id = obj.get('to', '')
    print(f'  Radio:{to_id}')
    print(f'    {det[:200]}')
    print()

# === PATCH GROUP ===
print(f'\n{"="*80}')
print(f'PATCH GROUP REGROUP — {len(regroup_add)} adds, {len(regroup_del)} deletes')
print(f'{"="*80}')
# Extract full details
patch_groups = defaultdict(lambda: {'adds': [], 'deletes': []})
for obj in regroup_add:
    det = obj.get('details', '')
    to_id = obj.get('to', '')
    hex_val = obj.get('hex', '')
    patch_groups[to_id]['adds'].append({'details': det, 'hex': hex_val, 'ts': obj.get('ts', '')})
for obj in regroup_del:
    det = obj.get('details', '')
    to_id = obj.get('to', '')
    hex_val = obj.get('hex', '')
    patch_groups[to_id]['deletes'].append({'details': det, 'hex': hex_val, 'ts': obj.get('ts', '')})

for pg, data in patch_groups.items():
    print(f'\n  Patch Group: {pg}')
    print(f'    Adds: {len(data["adds"])}, Deletes: {len(data["deletes"])}')
    if data['adds']:
        print(f'    Sample ADD detail: {data["adds"][0]["details"][:200]}')
        if data['adds'][0]['hex']:
            print(f'    ADD hex: {data["adds"][0]["hex"][:80]}')
    if data['deletes']:
        print(f'    Sample DELETE detail: {data["deletes"][0]["details"][:200]}')
        if data['deletes'][0]['hex']:
            print(f'    DELETE hex: {data["deletes"][0]["hex"][:80]}')

# === LSD ===
print(f'\n{"="*80}')
print(f'LOW SPEED DATA (LSD) — {len(lsd_entries)} entries, from voice frames')
print(f'{"="*80}')
lsd_values = Counter()
lsd_by_call = defaultdict(list)
for obj in lsd_entries:
    hex_val = obj.get('hex', '')
    from_id = obj.get('from', '')
    to_id = obj.get('to', '')
    cls = obj.get('class', '')
    lsd_values[hex_val] += 1
    key = f'{from_id}->{to_id}'
    lsd_by_call[key].append({'hex': hex_val, 'cls': cls, 'ts': obj.get('ts', '')})

print('\n  All LSD hex values:')
for val, count in lsd_values.most_common():
    # Decode the 2-byte LSD
    try:
        b = bytes.fromhex(val)
        b0 = b[0]
        b1 = b[1] if len(b) > 1 else 0
    except:
        b0 = b1 = 0
    print(f'    {val} ({count}x) — byte0=0x{b0:02X} byte1=0x{b1:02X} decimal={b0*256+b1}')

print('\n  LSD by call (from->to):')
for call, entries in lsd_by_call.items():
    print(f'    {call}: {len(entries)} LSD frames')
    for e in entries[:5]:
        print(f'      {e["cls"]} hex={e["hex"]}')
