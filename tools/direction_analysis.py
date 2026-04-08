import struct, json, glob, os
from datetime import datetime

logs_dir = r'c:\Users\Andy\Projects\SDRTrunk\sdrtrunk\logs'
records = []
for f in sorted(glob.glob(os.path.join(logs_dir, 'p25_data_*.jsonl'))):
    for line in open(f, 'r', encoding='utf-8', errors='replace'):
        line = line.strip()
        if line:
            try: records.append(json.loads(line))
            except: pass

def h2b(s):
    if not s: return b''
    try: return bytes.fromhex(s.replace(' ', ''))
    except: return b''

def parse_pkt(data):
    if len(data) < 30 or data[0] != 0x51: return None
    ip = data[2:]
    if (ip[0] >> 4) != 4: return None
    src = '.'.join(str(b) for b in ip[12:16])
    dst = '.'.join(str(b) for b in ip[16:20])
    sp = struct.unpack('>H', ip[20:22])[0]
    dp = struct.unpack('>H', ip[22:24])[0]
    return src, dst, sp, dp, ip[28:]

out = []
out.append(f"Loaded {len(records)} records")
out.append("")

infra = 0
radio = 0
lrrp_targets = set()
ars_targets = set()
xcmp_targets = set()
for r in records:
    d = h2b(r.get('hex', ''))
    p = parse_pkt(d)
    if not p: continue
    src, dst, sp, dp, app = p
    if src.startswith('10.51.') or src.startswith('192.168.'):
        infra += 1
    elif src.startswith('10.71.'):
        radio += 1
    to_id = r.get('to', '').split(',')[0]
    if r.get('proto') == 'LRRP' or r.get('type') == 'LRRP':
        lrrp_targets.add(to_id)
    if r.get('proto') == 'ARS':
        ars_targets.add(to_id)
    if r.get('proto') == 'XCMP':
        xcmp_targets.add(to_id)

out.append("TRAFFIC DIRECTION:")
out.append(f"  Infrastructure -> Radio: {infra}")
out.append(f"  Radio -> Infrastructure: {radio}")
out.append("")
out.append(f"LRRP targets ({len(lrrp_targets)} radios): {sorted(lrrp_targets)}")
out.append(f"ARS targets ({len(ars_targets)} radios): {sorted(list(ars_targets)[:10])}...")
out.append(f"XCMP targets ({len(xcmp_targets)} radios): {sorted(list(xcmp_targets)[:10])}...")
out.append("")

if radio == 0:
    out.append("*** CRITICAL FINDING: ALL IP packets are outbound (infra->radio) ***")
    out.append("*** No inbound (radio->infra) packets captured ***")
    out.append("*** GPS LRRP RESPONSES from radios are NOT in our corpus ***")
    out.append("*** We only have the REQUESTS being sent TO radios ***")
    out.append("")

# Decode LRRP app payloads
out.append("=== LRRP APP-LAYER DECODE ===")
lrrp_count = 0
for r in records:
    if r.get('proto') != 'LRRP' and r.get('type') != 'LRRP': continue
    d = h2b(r.get('hex', ''))
    p = parse_pkt(d)
    if not p: continue
    src, dst, sp, dp, app = p
    lrrp_count += 1
    if lrrp_count > 6: continue  # show first 6
    # Strip trailing AA padding
    end = len(app)
    while end > 0 and app[end-1] == 0xAA:
        end -= 1
    # Also strip 4-byte CRC at end
    app_clean = app[:max(end-4, 0)] if end > 4 else app[:end]
    ts = datetime.fromtimestamp(r['ts']/1000).strftime('%H:%M:%S') if r.get('ts') else '?'
    to_id = r.get('to', '').split(',')[0]
    out.append(f"  {ts} {src}:{sp} -> {dst}:{dp} radio={to_id}")
    out.append(f"    app (stripped): {app_clean.hex()}")
    if len(app_clean) >= 1:
        mt = app_clean[0]
        types = {0x07: 'TRIGGERED_LOC_START_REQ', 0x09: 'TRIGGERED_LOC_STOP_REQ'}
        out.append(f"    msg_type=0x{mt:02X} ({types.get(mt, 'UNKNOWN')})")
    out.append(f"    bytes: {' '.join(f'{b:02X}' for b in app_clean)}")

out.append(f"  Total LRRP with IP payload: {lrrp_count}")

# ARS decode
out.append("")
out.append("=== ARS APP-LAYER DECODE ===")
ars_count = 0
for r in records:
    if r.get('proto') != 'ARS': continue
    d = h2b(r.get('hex', ''))
    p = parse_pkt(d)
    if not p: continue
    src, dst, sp, dp, app = p
    ars_count += 1
    if ars_count > 6: continue
    end = len(app)
    while end > 0 and app[end-1] == 0xAA:
        end -= 1
    app_clean = app[:max(end-4, 0)] if end > 4 else app[:end]
    ts = datetime.fromtimestamp(r['ts']/1000).strftime('%H:%M:%S') if r.get('ts') else '?'
    to_id = r.get('to', '').split(',')[0]
    out.append(f"  {ts} {src}:{sp} -> {dst}:{dp} radio={to_id}")
    out.append(f"    app (stripped): {app_clean.hex()}")
    out.append(f"    bytes: {' '.join(f'{b:02X}' for b in app_clean)}")

out.append(f"  Total ARS with IP payload: {ars_count}")

# XCMP decode
out.append("")
out.append("=== XCMP APP-LAYER DECODE ===")
xcmp_count = 0
for r in records:
    if r.get('proto') != 'XCMP': continue
    d = h2b(r.get('hex', ''))
    p = parse_pkt(d)
    if not p: continue
    src, dst, sp, dp, app = p
    xcmp_count += 1
    if xcmp_count > 6: continue
    end = len(app)
    while end > 0 and app[end-1] == 0xAA:
        end -= 1
    app_clean = app[:max(end-4, 0)] if end > 4 else app[:end]
    ts = datetime.fromtimestamp(r['ts']/1000).strftime('%H:%M:%S') if r.get('ts') else '?'
    to_id = r.get('to', '').split(',')[0]
    out.append(f"  {ts} {src}:{sp} -> {dst}:{dp} radio={to_id}")
    out.append(f"    app (stripped): {app_clean.hex()}")
    out.append(f"    bytes: {' '.join(f'{b:02X}' for b in app_clean)}")
    # XCMP: first byte version, then 2-byte opcode
    if len(app_clean) >= 3:
        ver = app_clean[0]
        opc = struct.unpack('>H', app_clean[1:3])[0]
        out.append(f"    XCMP version={ver} opcode=0x{opc:04X}")

out.append(f"  Total XCMP with IP payload: {xcmp_count}")

# SNDCP IP addresses assigned
out.append("")
out.append("=== SNDCP: ASSIGNED IP ADDRESSES ===")
sndcp_ips = {}
for r in records:
    if r.get('type') != 'SNDCP': continue
    d = h2b(r.get('hex', ''))
    if len(d) < 8: continue
    # SNDCP Activate Accept: byte 0 high nibble = PDU type
    pdu_type = (d[0] >> 4) & 0xF
    if pdu_type == 0:  # Activate TDS Context (type=0 in our data = accept)
        to_id = r.get('to', '')
        if len(d) >= 8:
            # IP is typically at bytes 3-6 or 4-7
            for offset in [3, 4, 5]:
                if offset + 4 <= len(d):
                    candidate = '.'.join(str(b) for b in d[offset:offset+4])
                    if candidate.startswith('10.71.') or candidate.startswith('10.'):
                        sndcp_ips[to_id] = candidate
                        break

out.append(f"Radios with assigned IPs: {len(sndcp_ips)}")
for rid, ip in sorted(sndcp_ips.items())[:20]:
    out.append(f"  Radio {rid} -> IP {ip}")

# Print all
print('\n'.join(out))
