"""Deep decode of P25 data capture hex payloads — actually parse the binary protocols."""
import json, glob, os, struct, sys
from collections import Counter, defaultdict
from datetime import datetime

logs_dir = r'c:\Users\Andy\Projects\SDRTrunk\sdrtrunk\logs'
files = sorted(glob.glob(os.path.join(logs_dir, 'p25_data_*.jsonl')))

records = []
for f in files:
    with open(f, 'r', encoding='utf-8', errors='replace') as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
                records.append(rec)
            except:
                pass

print(f"Loaded {len(records):,} records from {len(files)} files\n")

def hex_to_bytes(hex_str):
    """Convert hex string (with or without spaces) to bytes."""
    if not hex_str:
        return b''
    hex_str = hex_str.replace(' ', '').replace('\n', '')
    try:
        return bytes.fromhex(hex_str)
    except:
        return b''

def decode_ip_header(data):
    """Parse IPv4 header."""
    if len(data) < 20:
        return None
    version_ihl = data[0]
    version = (version_ihl >> 4) & 0xF
    ihl = (version_ihl & 0xF) * 4
    if version != 4 or ihl < 20:
        return None
    total_len = struct.unpack('>H', data[2:4])[0]
    protocol = data[9]
    src_ip = '.'.join(str(b) for b in data[12:16])
    dst_ip = '.'.join(str(b) for b in data[16:20])
    proto_names = {1: 'ICMP', 6: 'TCP', 17: 'UDP'}
    return {
        'version': version, 'ihl': ihl, 'total_len': total_len,
        'protocol': proto_names.get(protocol, f'proto_{protocol}'),
        'protocol_num': protocol,
        'src': src_ip, 'dst': dst_ip,
        'payload': data[ihl:]
    }

def decode_udp(data):
    """Parse UDP header."""
    if len(data) < 8:
        return None
    src_port, dst_port, length, checksum = struct.unpack('>HHHH', data[:8])
    return {
        'src_port': src_port, 'dst_port': dst_port,
        'length': length, 'payload': data[8:]
    }

def decode_lrrp(data):
    """Try to decode LRRP (Location Request/Response Protocol) payload."""
    results = []
    if len(data) < 4:
        return results
    # LRRP messages start with a request/response ID
    # Common LRRP TLV parsing
    offset = 0
    while offset < len(data) - 2:
        tag = data[offset]
        if offset + 1 >= len(data):
            break
        length = data[offset + 1]
        if offset + 2 + length > len(data):
            break
        value = data[offset+2:offset+2+length]
        
        # Tag 0x22 = Latitude (4 bytes, signed, scaled)
        if tag == 0x22 and length >= 4:
            lat_raw = struct.unpack('>i', value[:4])[0]
            lat = lat_raw * (180.0 / 0x7FFFFFFF)
            results.append(('latitude', lat))
        # Tag 0x23 = Longitude (4 bytes, signed, scaled)
        elif tag == 0x23 and length >= 4:
            lon_raw = struct.unpack('>i', value[:4])[0]
            lon = lon_raw * (360.0 / 0xFFFFFFFF)
            results.append(('longitude', lon))
        # Tag 0x24 = Altitude
        elif tag == 0x24 and length >= 2:
            alt = struct.unpack('>H', value[:2])[0]
            results.append(('altitude_m', alt))
        # Tag 0x26 = Speed (horizontal)
        elif tag == 0x26 and length >= 2:
            speed_raw = struct.unpack('>H', value[:2])[0]
            results.append(('speed_raw', speed_raw))
        # Tag 0x27 = Heading/Direction
        elif tag == 0x27 and length >= 2:
            heading_raw = struct.unpack('>H', value[:2])[0]
            heading = heading_raw * (360.0 / 65536.0)
            results.append(('heading', heading))
        else:
            results.append((f'tag_0x{tag:02X}', value.hex()))
        
        offset += 2 + length
    return results

def decode_xcmp(data):
    """Decode XCMP (eXtended Command & Management Protocol)."""
    results = []
    if len(data) < 4:
        return results
    # XCMP typically: version(1) + opcode(2) + payload
    # But in our captures it's wrapped in UDP
    opcode_map = {
        0x000D: 'RADIO_STATUS_REQUEST',
        0x000E: 'RADIO_STATUS_REPLY', 
        0x0009: 'RADIO_POWER_CONTROL',
        0x000B: 'DISPLAY_TEXT',
        0x0015: 'CHANNEL_SELECTION',
        0x0400: 'VERSION_INFO_REQUEST',
        0x0401: 'VERSION_INFO_REPLY',
        0x040C: 'RADIO_STATUS',
        0x040D: 'RF_STATS',
    }
    if len(data) >= 3:
        version = data[0]
        opcode = struct.unpack('>H', data[1:3])[0]
        op_name = opcode_map.get(opcode, f'UNKNOWN_0x{opcode:04X}')
        results.append(('xcmp_version', version))
        results.append(('xcmp_opcode', op_name))
        if len(data) > 3:
            results.append(('xcmp_payload', data[3:].hex()))
    return results

def decode_ars(data):
    """Decode ARS (Automatic Registration Service)."""
    results = []
    if len(data) < 2:
        return results
    # ARS is typically on UDP port 4001
    # Message type in first byte
    msg_types = {
        0x00: 'DEVICE_REGISTRATION',
        0x01: 'DEVICE_DEREGISTRATION', 
        0x02: 'DEVICE_STATUS',
        0x80: 'SERVER_ACK',
    }
    msg_type = data[0]
    results.append(('ars_type', msg_types.get(msg_type, f'0x{msg_type:02X}')))
    if len(data) > 1:
        results.append(('ars_payload', data[1:].hex()))
    return results

def scan_for_lrrp_in_payload(data):
    """Scan raw payload bytes for LRRP GPS coordinate patterns."""
    results = []
    # Look for LRRP-style lat/lon tags (0x22/0x23 with length 4)
    for i in range(len(data) - 9):
        if data[i] == 0x22 and data[i+1] == 0x04:
            lat_raw = struct.unpack('>i', data[i+2:i+6])[0]
            lat = lat_raw * (180.0 / 0x7FFFFFFF)
            if -90 <= lat <= 90:
                # Check if lon follows
                if i+6 < len(data) - 5 and data[i+6] == 0x23 and data[i+7] == 0x04:
                    lon_raw = struct.unpack('>i', data[i+8:i+12])[0]
                    lon = lon_raw * (360.0 / 0xFFFFFFFF)
                    if -180 <= lon <= 180:
                        results.append((lat, lon, i))
    return results

# ============================================================================
# DEEP DECODE
# ============================================================================

print("=" * 80)
print("DEEP DECODE: Parsing all hex payloads")
print("=" * 80)

# --- 1. Decode all IP packets ---
ip_packets = []
udp_flows = defaultdict(list)
non_ip_hex = []

for rec in records:
    hexdata = rec.get('hex', '')
    if not hexdata:
        continue
    data = hex_to_bytes(hexdata)
    if not data:
        continue
    
    ip = decode_ip_header(data)
    if ip:
        ip['_rec'] = rec
        ip_packets.append(ip)
        
        if ip['protocol'] == 'UDP' and len(ip['payload']) >= 8:
            udp = decode_udp(ip['payload'])
            if udp:
                flow_key = f"{ip['src']}:{udp['src_port']}->{ip['dst']}:{udp['dst_port']}"
                udp['_ip'] = ip
                udp['_rec'] = rec
                udp_flows[flow_key].append(udp)
    else:
        non_ip_hex.append((rec, data))

print(f"\n=== IP PACKET ANALYSIS ===")
print(f"Total IP packets decoded: {len(ip_packets)}")

# IP flow summary
ip_flows = Counter()
for pkt in ip_packets:
    ip_flows[f"{pkt['src']} -> {pkt['dst']} ({pkt['protocol']})"] += 1

print(f"\nIP Flows:")
for flow, count in ip_flows.most_common(20):
    print(f"  {flow:55s} {count:>5} packets")

# --- 2. Decode UDP flows ---
print(f"\n=== UDP FLOW ANALYSIS ===")
print(f"Unique UDP flows: {len(udp_flows)}")
for flow_key, packets in sorted(udp_flows.items(), key=lambda x: -len(x[1])):
    print(f"\n  Flow: {flow_key} ({len(packets)} packets)")
    # Analyze first few payloads
    for i, pkt in enumerate(packets[:3]):
        payload = pkt['payload']
        if len(payload) > 0:
            print(f"    [{i}] {len(payload)} bytes: {payload[:40].hex()}")
            
            # Try to identify protocol by port
            dst_port = pkt['dst_port']
            src_port = pkt['src_port']
            
            # Port 4001 = ARS
            if dst_port == 4001 or src_port == 4001:
                ars = decode_ars(payload)
                if ars:
                    print(f"        ARS: {dict(ars)}")
            
            # Port 4005/4007 = LRRP
            if dst_port in (4005, 4007) or src_port in (4005, 4007):
                lrrp = decode_lrrp(payload)
                if lrrp:
                    print(f"        LRRP: {dict(lrrp)}")
                # Also scan for GPS patterns
                gps = scan_for_lrrp_in_payload(payload)
                if gps:
                    for lat, lon, offset in gps:
                        print(f"        GPS FOUND @ offset {offset}: lat={lat:.6f} lon={lon:.6f}")
            
            # XCMP ports (various, often high)
            if dst_port == 64414 or src_port == 64414:
                xcmp = decode_xcmp(payload)
                if xcmp:
                    print(f"        XCMP: {dict(xcmp)}")
    
    if len(packets) > 3:
        print(f"    ... and {len(packets)-3} more packets")

# --- 3. Scan ALL payloads for embedded GPS ---
print(f"\n=== GPS COORDINATE SCAN (all payloads) ===")
gps_found = []
for rec in records:
    hexdata = rec.get('hex', '')
    if not hexdata:
        continue
    data = hex_to_bytes(hexdata)
    gps_hits = scan_for_lrrp_in_payload(data)
    if gps_hits:
        for lat, lon, offset in gps_hits:
            ts = datetime.fromtimestamp(rec['ts']/1000).strftime('%H:%M:%S') if rec.get('ts') else '?'
            from_id = rec.get('from', '?')
            print(f"  {ts} from={from_id:>15s} lat={lat:.6f} lon={lon:.6f} (offset {offset} in {rec.get('type','?')})")
            gps_found.append((lat, lon, from_id, rec))

if not gps_found:
    print("  No LRRP GPS coordinates found in raw payloads")
    # Try alternate GPS encoding - look for IEEE 754 lat/lon pairs
    print("  Scanning for IEEE 754 float GPS patterns...")
    ieee_gps = []
    for rec in records:
        hexdata = rec.get('hex', '')
        if not hexdata:
            continue
        data = hex_to_bytes(hexdata)
        if len(data) < 8:
            continue
        # Scan for pairs of 4-byte big-endian floats that look like lat/lon
        for i in range(len(data) - 7):
            try:
                f1 = struct.unpack('>f', data[i:i+4])[0]
                f2 = struct.unpack('>f', data[i+4:i+8])[0]
                # Jacksonville area: lat ~30.3, lon ~-81.6
                if 25 <= f1 <= 35 and -85 <= f2 <= -75:
                    ieee_gps.append((f1, f2, i, rec))
            except:
                pass
    if ieee_gps:
        print(f"  Found {len(ieee_gps)} potential IEEE 754 GPS coordinates:")
        for lat, lon, offset, rec in ieee_gps[:10]:
            ts = datetime.fromtimestamp(rec['ts']/1000).strftime('%H:%M:%S') if rec.get('ts') else '?'
            from_id = rec.get('from', '?')
            print(f"    {ts} from={from_id:>15s} lat={lat:.6f} lon={lon:.6f} (offset {offset})")

# --- 4. Decode SNDCP details ---
print(f"\n=== SNDCP SESSION ANALYSIS ===")
sndcp_recs = [r for r in records if r.get('type') == 'SNDCP']
sndcp_by_sap = Counter(r.get('sap', '') for r in sndcp_recs)
for sap, count in sndcp_by_sap.most_common():
    print(f"  {sap:55s} {count:>5}")

# Extract SNDCP subscriber IDs from context activations
sndcp_subscribers = Counter()
for r in sndcp_recs:
    to_id = r.get('to', '')
    if to_id:
        sndcp_subscribers[to_id] += 1
print(f"\n  SNDCP subscribers with data sessions ({len(sndcp_subscribers)} unique):")
for sub, count in sndcp_subscribers.most_common(15):
    print(f"    {sub:20s} {count:>4} session events")

# --- 5. Decode LSD (Low Speed Data) content ---
print(f"\n=== LOW SPEED DATA (LSD) DECODE ===")
lsd_recs = [r for r in records if r.get('type') == 'LSD']
lsd_by_proto = Counter(r.get('proto', '') for r in lsd_recs)
for proto, count in lsd_by_proto.most_common():
    print(f"  Protocol: {proto:20s} {count:>5}")

# Decode LSD hex payloads
for r in lsd_recs[:10]:
    data = hex_to_bytes(r.get('hex', ''))
    ts = datetime.fromtimestamp(r['ts']/1000).strftime('%H:%M:%S') if r.get('ts') else '?'
    proto = r.get('proto', '')
    details = r.get('details', '')[:80]
    print(f"  {ts} [{proto:10s}] {data.hex() if data else '(empty)':20s} {details}")

# --- 6. Large payloads analysis ---
print(f"\n=== LARGE PAYLOAD ANALYSIS (>50 bytes) ===")
large = [(r, hex_to_bytes(r.get('hex', ''))) for r in records 
         if r.get('len', 0) > 50]
large.sort(key=lambda x: -len(x[1]))
print(f"Records with >50 byte payloads: {len(large)}")
for r, data in large[:15]:
    ts = datetime.fromtimestamp(r['ts']/1000).strftime('%H:%M:%S') if r.get('ts') else '?'
    rtype = r.get('type', '?')
    from_id = r.get('from', '')
    to_id = r.get('to', '')
    proto = r.get('proto', '')
    sap = r.get('sap', '')
    
    # Try IP decode
    ip = decode_ip_header(data)
    ip_info = ""
    if ip:
        ip_info = f" IP:{ip['src']}->{ip['dst']}({ip['protocol']})"
        if ip['protocol'] == 'UDP':
            udp = decode_udp(ip['payload'])
            if udp:
                ip_info += f" port:{udp['src_port']}->{udp['dst_port']}"
                # Show first bytes of UDP payload
                if udp['payload']:
                    ip_info += f" data:{udp['payload'][:16].hex()}"
    
    print(f"  {ts} {rtype:12s} {len(data):>5}B from={from_id:>15s} to={to_id:>10s} {proto:8s}{ip_info}")

# --- 7. Hex pattern analysis on non-IP data ---
print(f"\n=== NON-IP HEX PATTERN ANALYSIS ===")
print(f"Records with hex data that is NOT valid IP: {len(non_ip_hex)}")
# Look at first bytes to identify patterns
first_byte_counter = Counter()
first_two_bytes = Counter()
for rec, data in non_ip_hex:
    if len(data) >= 1:
        first_byte_counter[f'0x{data[0]:02X}'] += 1
    if len(data) >= 2:
        first_two_bytes[f'0x{data[0]:02X}{data[1]:02X}'] += 1

print(f"  First byte distribution (top 15):")
for b, count in first_byte_counter.most_common(15):
    print(f"    {b}: {count:>5}")

# Show some samples of non-IP data
print(f"\n  Sample non-IP payloads:")
for rec, data in non_ip_hex[:20]:
    rtype = rec.get('type', '?')
    sap = rec.get('sap', '')[:40]
    print(f"    [{rtype:12s}] {data[:24].hex():48s} sap={sap}")

# --- 8. TSBK content analysis ---
print(f"\n=== TSBK DETAILED ANALYSIS ===")
tsbk_recs = [r for r in records if 'TSBK' in r.get('type', '')]
tsbk_by_class = Counter(r.get('class', '') for r in tsbk_recs)
for cls, count in tsbk_by_class.most_common():
    print(f"  {cls:50s} {count:>5}")

# Regroup commands - extract patch group details
regroup_adds = [r for r in records if 'GROUP_REGROUP_ADD' in r.get('sap', '')]
regroup_dels = [r for r in records if 'GROUP_REGROUP_DELETE' in r.get('sap', '')]
print(f"\n  Patch Group Regroup: {len(regroup_adds)} adds, {len(regroup_dels)} deletes")
if regroup_adds:
    # Show unique to-IDs (these are the patch group targets)
    pg_targets = Counter(r.get('to', '') for r in regroup_adds if r.get('to', ''))
    print(f"  Patch group targets:")
    for tgt, count in pg_targets.most_common(10):
        print(f"    {tgt:20s} regrouped {count:>4} times")

print(f"\n=== DECODE COMPLETE ===")
