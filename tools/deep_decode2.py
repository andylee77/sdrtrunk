"""Deep decode #2 — parse protocol-layer hex from captured JSONL records."""
import json, glob, os, struct
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
                records.append(json.loads(line))
            except:
                pass

print(f"Loaded {len(records):,} records\n")

def h2b(s):
    if not s: return b''
    s = s.replace(' ','').replace('\n','')
    try: return bytes.fromhex(s)
    except: return b''

def ts(rec):
    t = rec.get('ts',0)
    if t: return datetime.fromtimestamp(t/1000).strftime('%H:%M:%S')
    return '?'

# ============================================================================
# 1. LRRP — decode Triggered Location Start Request TLV
# ============================================================================
print("=" * 80)
print("1. LRRP PACKET DECODE")
print("=" * 80)
lrrp_recs = [r for r in records if r.get('proto') == 'LRRP' or r.get('type') == 'LRRP']
print(f"LRRP records: {len(lrrp_recs)}")

for r in lrrp_recs:
    data = h2b(r.get('hex',''))
    print(f"\n  {ts(r)} from={r.get('from','?'):>20s} to={r.get('to','?'):>30s} len={len(data)}")
    print(f"  hex: {data.hex()}")
    
    # LRRP TLV decode
    # First byte is often message type/version
    if len(data) >= 2:
        msg_type = data[0]
        print(f"  msg_type=0x{msg_type:02X}", end="")
        # LRRP message types:
        # 0x05 = Immediate Location Request
        # 0x07 = Triggered Location Start Request 
        # 0x09 = Triggered Location Stop Request
        # 0x0D = Immediate Location Report
        # 0x0F = Triggered Location Report
        lrrp_types = {
            0x05: 'IMMEDIATE_LOCATION_REQUEST',
            0x07: 'TRIGGERED_LOCATION_START_REQUEST',
            0x09: 'TRIGGERED_LOCATION_STOP_REQUEST',
            0x0D: 'IMMEDIATE_LOCATION_REPORT',
            0x0F: 'TRIGGERED_LOCATION_REPORT',
            0x04: 'IMMEDIATE_LOCATION_REQUEST_v2',
            0x06: 'TRIGGERED_LOCATION_START_REQUEST_v2',
        }
        print(f" = {lrrp_types.get(msg_type, 'UNKNOWN')}")
        
        # Parse TLV elements starting at offset 1
        offset = 1
        while offset < len(data) - 1:
            tag = data[offset]
            # Check for 2-byte length tags
            if tag in (0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x34, 0x51, 0x55, 0x56, 0x58, 0x66):
                if offset + 1 >= len(data):
                    break
                tlen = data[offset+1]
                if offset + 2 + tlen > len(data):
                    tval = data[offset+2:]
                    offset = len(data)
                else:
                    tval = data[offset+2:offset+2+tlen]
                    offset += 2 + tlen
            else:
                # Single-byte value or unknown structure
                tval = data[offset+1:offset+2] if offset+1 < len(data) else b''
                offset += 2
            
            # Decode known tags
            if tag == 0x22 and len(tval) >= 4:
                lat_raw = struct.unpack('>i', tval[:4])[0]
                lat = lat_raw * (180.0 / 0x7FFFFFFF)
                print(f"    TAG 0x22 LATITUDE: {lat:.6f} deg")
            elif tag == 0x23 and len(tval) >= 4:
                lon_raw = struct.unpack('>i', tval[:4])[0]
                lon = lon_raw * (360.0 / 0xFFFFFFFF)
                print(f"    TAG 0x23 LONGITUDE: {lon:.6f} deg")
            elif tag == 0x24 and len(tval) >= 2:
                alt = struct.unpack('>H', tval[:2])[0]
                print(f"    TAG 0x24 ALTITUDE: {alt} meters")
            elif tag == 0x26 and len(tval) >= 1:
                print(f"    TAG 0x26 SPEED: {tval.hex()}")
            elif tag == 0x27 and len(tval) >= 2:
                heading = struct.unpack('>H', tval[:2])[0] * 360.0 / 65536
                print(f"    TAG 0x27 HEADING: {heading:.1f} deg")
            elif tag == 0x34:
                print(f"    TAG 0x34 TRIGGER_DEFINITION: {tval.hex()}")
            elif tag == 0x51:
                print(f"    TAG 0x51 REQUEST_ID: {tval.hex()}")
            elif tag == 0x55:
                print(f"    TAG 0x55 RESULT_CODE: {tval.hex()}")
            elif tag == 0x58:
                print(f"    TAG 0x58 INTERVAL: {tval.hex()}")
            elif tag == 0x66:
                print(f"    TAG 0x66 TRIGGER_TYPE: {tval.hex()}")
            else:
                print(f"    TAG 0x{tag:02X} ({len(tval)}B): {tval.hex()}")

# ============================================================================
# 2. SNDCP — decode the activate/deactivate hex
# ============================================================================
print("\n" + "=" * 80)
print("2. SNDCP PACKET DECODE")
print("=" * 80)
sndcp_recs = [r for r in records if r.get('type') == 'SNDCP']
# Show unique hex patterns
sndcp_patterns = Counter()
for r in sndcp_recs:
    data = h2b(r.get('hex',''))
    if data:
        # First 4 bytes as pattern key
        key = data[:4].hex()
        sndcp_patterns[key] += 1

print(f"SNDCP records: {len(sndcp_recs)}, unique first-4-byte patterns: {len(sndcp_patterns)}")
for pat, count in sndcp_patterns.most_common(15):
    print(f"  {pat}  x{count}")

# Decode SNDCP structure
print(f"\nSNDCP decode (first 10 unique patterns):")
seen_pats = set()
for r in sndcp_recs:
    data = h2b(r.get('hex',''))
    if not data or data[:4].hex() in seen_pats:
        continue
    seen_pats.add(data[:4].hex())
    if len(seen_pats) > 10:
        break
    
    print(f"\n  {ts(r)} to={r.get('to','?'):>10s} sap={r.get('sap','')}")
    print(f"  hex ({len(data)}B): {data.hex()}")
    
    # SNDCP PDU: Type(4 bits) | Version(4 bits) | ...
    if len(data) >= 1:
        pdu_type = (data[0] >> 4) & 0xF
        version = data[0] & 0xF
        sndcp_pdu_types = {
            0: 'ACTIVATE_TDS_CONTEXT_REQUEST',
            1: 'ACTIVATE_TDS_CONTEXT_ACCEPT',
            2: 'ACTIVATE_TDS_CONTEXT_REJECT',
            3: 'DEACTIVATE_TDS_CONTEXT_REQUEST',
            4: 'DEACTIVATE_TDS_CONTEXT_ACCEPT',
            5: 'SN_DATA',
            6: 'RF_CONFIRMED_DATA',
            7: 'RF_UNCONFIRMED_DATA',
        }
        print(f"  PDU type={pdu_type} ({sndcp_pdu_types.get(pdu_type,'UNKNOWN')}) version={version}")
        
        if pdu_type == 1 and len(data) >= 8:  # Activate TDS Context Accept
            # Byte 1: NSAPI(4) | PCOMP(4)
            nsapi = (data[1] >> 4) & 0xF
            pcomp = data[1] & 0xF
            # Byte 2: DCOMP(4) | ...
            dcomp = (data[2] >> 4) & 0xF
            print(f"  NSAPI={nsapi} PCOMP={pcomp} DCOMP={dcomp}")
            # Remaining bytes: IP config
            if len(data) >= 8:
                ip_bytes = data[4:8] if len(data) >= 8 else data[4:]
                if len(ip_bytes) == 4:
                    ip_addr = '.'.join(str(b) for b in ip_bytes)
                    print(f"  Assigned IP: {ip_addr}")
            # Full remaining
            print(f"  Remaining: {data[3:].hex()}")

# ============================================================================
# 3. Motorola TSBK decode — Opcode 0x8B (TDMA Data Channel) & 0x87 (Unknown 135)
# ============================================================================
print("\n" + "=" * 80)
print("3. MOTOROLA TSBK HEX DECODE")
print("=" * 80)

# Decode 0x8B TDMA Data Channel
tdma_ch_recs = [r for r in records if '8B_TDMA_DATA_CHANNEL' in r.get('sap','')]
print(f"\n--- Motorola 0x8B TDMA Data Channel: {len(tdma_ch_recs)} records ---")
tdma_patterns = Counter()
for r in tdma_ch_recs:
    data = h2b(r.get('hex',''))
    if data:
        tdma_patterns[data.hex()] += 1
print(f"Unique hex patterns: {len(tdma_patterns)}")
for pat, count in tdma_patterns.most_common(10):
    data = bytes.fromhex(pat)
    print(f"\n  Pattern (x{count}): {pat}")
    # Parse Motorola MAC PDU: 7C 87 90 03 8B ...
    # Byte 0: 0x7C = MAC_RELEASE? or MAC type marker
    # Bytes vary — let's decode as TDMA channel assignment
    if len(data) >= 20:
        # Typical: 7C 87 90 03 8B 90 0F FF 04 05 FF 04 05 FF 04 05 FF 04 05 00 00 B3 A0
        # After 7C 87 90 03 8B 90:
        #   0FFF = service options?
        #   Then repeating 04 05 pattern = channel number pairs?
        offset = 6 if data[:2] == b'\x7c\x87' else 0
        remaining = data[offset:]
        # Try to extract channel numbers
        channels = []
        for i in range(0, len(remaining)-1, 2):
            ch_val = struct.unpack('>H', remaining[i:i+2])[0]
            if ch_val != 0 and ch_val != 0xFFFF:
                channels.append(ch_val)
        if channels:
            print(f"  Channel values: {channels}")
            # Convert to frequencies using 12.5kHz step from 851.0 MHz base
            for ch in channels:
                if 0 < ch < 4096:
                    freq = 851.0 + (ch * 0.025)
                    if ch > 0x0400:
                        freq = 851.0125 + ((ch - 0x0380) * 0.025)
                    print(f"    Channel {ch} (0x{ch:04X})")

# Decode 0x87 Unknown Opcode 135
unk135_recs = [r for r in records if 'UNKNOWN_OPCODE_135' in r.get('sap','')]
print(f"\n--- Motorola 0x87 Unknown Opcode 135: {len(unk135_recs)} records ---")
unk135_patterns = Counter()
for r in unk135_recs:
    data = h2b(r.get('hex',''))
    if data:
        unk135_patterns[data.hex()] += 1
print(f"Unique hex patterns: {len(unk135_patterns)}")
for pat, count in unk135_patterns.most_common(5):
    data = bytes.fromhex(pat)
    print(f"\n  Pattern (x{count}): {pat}")
    if len(data) >= 10:
        # 7C 87 90 03 8D 90 05 80 A1 00 00 00 00 00 00 00 00 00 00 00 00 CF 80
        print(f"  Bytes 0-1: {data[0]:02X} {data[1]:02X} (MAC header)")
        print(f"  Byte 4:   {data[4]:02X} = opcode 0x{data[4]:02X} ({data[4]})")
        print(f"  Byte 5:   {data[5]:02X}")
        print(f"  Bytes 6-7: {data[6]:02X}{data[7]:02X}")
        print(f"  Byte 8:   {data[8]:02X}")
        print(f"  Rest:     {data[9:].hex()}")

# ============================================================================
# 4. XCMP decode 
# ============================================================================
print("\n" + "=" * 80)
print("4. XCMP PACKET DECODE")
print("=" * 80)
xcmp_recs = [r for r in records if r.get('proto') == 'XCMP']
print(f"XCMP records: {len(xcmp_recs)}")
xcmp_patterns = Counter()
for r in xcmp_recs:
    data = h2b(r.get('hex',''))
    if data and len(data) >= 3:
        # Group by first 6 bytes
        key = data[:min(6,len(data))].hex()
        xcmp_patterns[key] += 1

print(f"Unique XCMP first-6B patterns: {len(xcmp_patterns)}")
for pat, count in xcmp_patterns.most_common(10):
    print(f"  {pat}  x{count}")

# Show actual XCMP decodes
print(f"\nXCMP full decode (first 10):")
for i, r in enumerate(xcmp_recs[:10]):
    data = h2b(r.get('hex',''))
    print(f"\n  [{i}] {ts(r)} from={r.get('from','?'):>20s} to={r.get('to','?'):>30s}")
    print(f"  hex ({len(data)}B): {data.hex()}")

# ============================================================================
# 5. ARS decode
# ============================================================================
print("\n" + "=" * 80)
print("5. ARS (Automatic Registration) DECODE")
print("=" * 80)
ars_recs = [r for r in records if r.get('proto') == 'ARS']
print(f"ARS records: {len(ars_recs)}")
for r in ars_recs[:15]:
    data = h2b(r.get('hex',''))
    print(f"  {ts(r)} from={r.get('from','?'):>20s} to={r.get('to','?'):>30s} {len(data)}B: {data.hex()}")

# ============================================================================
# 6. PDU_PACKET with "UNKNOWN" protocol — the bulk of the data
# ============================================================================
print("\n" + "=" * 80)
print("6. PDU_PACKET 'UNKNOWN' PROTOCOL DECODE")
print("=" * 80)
unk_pdu = [r for r in records if r.get('type') == 'PDU_PACKET' and r.get('proto','') in ('UNKNOWN','')]
print(f"Unknown/empty protocol PDU packets: {len(unk_pdu)}")

# Group by first byte of hex
fb_counter = Counter()
for r in unk_pdu:
    data = h2b(r.get('hex',''))
    if data:
        fb_counter[f'0x{data[0]:02X}'] += 1

print(f"\nFirst byte distribution:")
for fb, count in fb_counter.most_common(15):
    print(f"  {fb}: {count:>5}")

# Show representative samples for each first-byte pattern
print(f"\nRepresentative samples per first-byte pattern:")
seen_fb = set()
for r in unk_pdu:
    data = h2b(r.get('hex',''))
    if not data:
        continue
    fb = data[0]
    if fb in seen_fb:
        continue
    seen_fb.add(fb)
    
    from_id = r.get('from','')
    to_id = r.get('to','')
    print(f"\n  First byte 0x{fb:02X}:")
    print(f"  {ts(r)} from={from_id:>20s} to={to_id:>30s}")
    print(f"  hex ({len(data)}B): {data[:32].hex()}")
    
    # Try to identify the payload
    if fb == 0x45:  # IPv4
        print(f"  -> Looks like IPv4 packet")
    elif fb in (0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09):
        # Could be SNDCP, ARS, or LRRP
        print(f"  -> Low first byte, possibly LRRP/ARS/SNDCP control")
    elif fb >= 0x40 and fb <= 0x4F:
        print(f"  -> Version nibble 4, could be IPv4")
    else:
        # Try to find printable ASCII
        printable = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[:32])
        print(f"  -> ASCII: {printable}")

# ============================================================================
# 7. ResponseMessage (the biggest class) — decode hex
# ============================================================================
print("\n" + "=" * 80)
print("7. ResponseMessage (PDU Response) DECODE")
print("=" * 80)
resp_recs = [r for r in records if r.get('class') == 'ResponseMessage']
print(f"ResponseMessage records: {len(resp_recs)}")

# These are PDU responses — the hex contains the response data
resp_fb = Counter()
for r in resp_recs:
    data = h2b(r.get('hex',''))
    if data and len(data) >= 1:
        resp_fb[f'0x{data[0]:02X}'] += 1

print(f"First byte distribution:")
for fb, count in resp_fb.most_common(15):
    print(f"  {fb}: {count:>5}")

# Decode a few
print(f"\nSample ResponseMessage payloads:")
for r in resp_recs[:8]:
    data = h2b(r.get('hex',''))
    from_id = r.get('from','')
    to_id = r.get('to','')
    sap = r.get('sap','')
    print(f"  {ts(r)} from={from_id:>20s} to={to_id:>10s} {len(data)}B: {data[:24].hex()}")

# ============================================================================
# 8. PacketMessage — the IP-wrapped packets already decoded by SDRTrunk
# ============================================================================
print("\n" + "=" * 80)
print("8. PacketMessage (IP) DECODE")
print("=" * 80)
pkt_recs = [r for r in records if r.get('class') == 'PacketMessage']
print(f"PacketMessage records: {len(pkt_recs)}")

# These have src/dst IP embedded in from/to fields
pkt_flows = Counter()
for r in pkt_recs:
    f = r.get('from','')
    t = r.get('to','')
    pkt_flows[f"{f} -> {t}"] += 1

print(f"\nPacketMessage flows:")
for flow, count in pkt_flows.most_common(20):
    print(f"  {flow:60s} {count:>4}")

# Show hex payloads of PacketMessage records
print(f"\nPacketMessage hex payloads (first 15):")
for r in pkt_recs[:15]:
    data = h2b(r.get('hex',''))
    proto = r.get('proto','')
    sap = r.get('sap','')[:50]
    from_id = r.get('from','')
    to_id = r.get('to','')
    print(f"  {ts(r)} {proto:8s} from={from_id:>25s} to={to_id:>30s}")
    if data:
        print(f"    hex ({len(data)}B): {data[:48].hex()}")
        printable = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[:48])
        print(f"    ascii: {printable}")

# ============================================================================
# 9. PDUSequenceMessage — multi-block data
# ============================================================================
print("\n" + "=" * 80)
print("9. PDUSequenceMessage (Multi-Block PDU) DECODE")
print("=" * 80)
seq_recs = [r for r in records if r.get('class') == 'PDUSequenceMessage']
print(f"PDUSequenceMessage records: {len(seq_recs)}")
for r in seq_recs[:10]:
    data = h2b(r.get('hex',''))
    from_id = r.get('from','')
    to_id = r.get('to','')
    sap = r.get('sap','')
    proto = r.get('proto','')
    print(f"\n  {ts(r)} {proto:8s} sap={sap}")
    print(f"  from={from_id} to={to_id}")
    if data:
        print(f"  hex ({len(data)}B): {data[:64].hex()}")
        if len(data) > 64:
            print(f"  hex contd: {data[64:128].hex()}")
        printable = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[:80])
        print(f"  ascii: {printable}")

print(f"\n{'='*80}")
print("DECODE COMPLETE")
print(f"{'='*80}")
