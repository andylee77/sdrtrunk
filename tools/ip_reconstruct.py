#!/usr/bin/env python3
"""
IP Layer Reconstruction & Deep Data Analysis for P25 Capture Corpus

Analyzes JSONL capture files to:
1. Reconstruct IP-layer sessions (SNDCP -> IP assignment -> data flow)
2. Extract and correlate LRRP GPS location data
3. Decode XCMP device management sessions
4. Track ARS (Automatic Registration Service) activity
5. Analyze SNDCP context lifecycle (activate/deactivate/reject)
6. Extract actual IP packet payloads and attempt protocol identification
7. Reconstruct UDP flows between endpoints
8. Identify radio-to-infrastructure vs infrastructure-to-radio traffic
9. Timeline analysis of data sessions
10. Payload hex analysis and pattern detection

Usage:
    python tools/ip_reconstruct.py [file1.jsonl] [file2.jsonl] ...
    python tools/ip_reconstruct.py   # analyzes all logs/p25_data_*_20260325.jsonl
"""

import json
import sys
import os
import io
import re
import struct
from collections import Counter, defaultdict
from datetime import datetime, timezone

# Force UTF-8 output on Windows
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')


def parse_timestamp(ts_ms):
    """Convert epoch millis to readable timestamp."""
    try:
        return datetime.fromtimestamp(ts_ms / 1000.0, tz=timezone.utc).strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]
    except:
        return str(ts_ms)


def hex_to_bytes(hex_str):
    """Convert hex string (with spaces) to bytes."""
    if not hex_str:
        return b''
    try:
        clean = hex_str.replace(' ', '').replace('\n', '')
        return bytes.fromhex(clean)
    except:
        return b''


def extract_ip_from_details(details):
    """Extract IP addresses from details string."""
    ips = re.findall(r'\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b', details or '')
    return ips


def extract_sndcp_ip(details):
    """Extract IP address assigned in SNDCP context accept."""
    # Pattern: "IP:10.71.xxx.xxx" or "IP ADDRESS:10.71.xxx.xxx"  
    m = re.search(r'IP[:\s]*ADDRESS[:\s]*(\d+\.\d+\.\d+\.\d+)', details or '', re.IGNORECASE)
    if m:
        return m.group(1)
    m = re.search(r'IP[:\s]*(\d+\.\d+\.\d+\.\d+)', details or '', re.IGNORECASE)
    if m:
        return m.group(1)
    # Also try to find in hex pattern for 10.71.x.x
    ips = extract_ip_from_details(details)
    for ip in ips:
        if ip.startswith('10.'):
            return ip
    return None


def parse_ipv4_header(data):
    """Parse IPv4 header from raw bytes."""
    if len(data) < 20:
        return None
    version = (data[0] >> 4) & 0xF
    if version != 4:
        return None
    ihl = (data[0] & 0xF) * 4
    total_len = struct.unpack('>H', data[2:4])[0]
    protocol = data[9]
    src_ip = '.'.join(str(b) for b in data[12:16])
    dst_ip = '.'.join(str(b) for b in data[16:20])
    return {
        'version': version,
        'ihl': ihl,
        'total_len': total_len,
        'protocol': protocol,
        'src_ip': src_ip,
        'dst_ip': dst_ip,
        'payload_offset': ihl,
    }


def parse_udp_header(data, offset):
    """Parse UDP header from raw bytes at offset."""
    if len(data) < offset + 8:
        return None
    src_port = struct.unpack('>H', data[offset:offset+2])[0]
    dst_port = struct.unpack('>H', data[offset+2:offset+4])[0]
    length = struct.unpack('>H', data[offset+4:offset+6])[0]
    return {
        'src_port': src_port,
        'dst_port': dst_port,
        'length': length,
        'payload_offset': offset + 8,
    }


def ascii_dump(data, max_len=64):
    """Create ASCII representation of bytes, replacing non-printable with dots."""
    result = []
    for b in data[:max_len]:
        if 32 <= b < 127:
            result.append(chr(b))
        else:
            result.append('.')
    return ''.join(result)


def identify_app_protocol(src_port, dst_port, payload):
    """Try to identify application-layer protocol from ports and payload."""
    known_ports = {
        4001: 'LRRP',
        4005: 'ARS',
        4007: 'TMS (Text Message)',
        4008: 'MNIS',
        4010: 'CLNS',
        64414: 'XCMP/XNL',
        4710: 'MOTOTRBO-GW',
    }
    
    for port in [src_port, dst_port]:
        if port in known_ports:
            return known_ports[port]
    
    # Try payload-based detection
    if payload and len(payload) > 0:
        first_byte = payload[0]
        if first_byte == 0x04 and len(payload) >= 4:
            return 'LRRP (likely)'
        if first_byte == 0x00 and len(payload) >= 2 and payload[1] in [0x01, 0x02, 0x03]:
            return 'ARS (likely)'
    
    return 'Unknown'


class IPReconstructor:
    """Reconstructs IP-layer data from P25 capture corpus."""
    
    def __init__(self):
        # SNDCP tracking
        self.sndcp_sessions = {}  # radio_id -> {ip, activate_time, deactivate_time, ...}
        self.sndcp_events = []    # All SNDCP events in order
        
        # IP flows
        self.ip_flows = defaultdict(list)  # (src_ip, dst_ip, src_port, dst_port) -> [records]
        self.ip_packets = []  # All reconstructed IP packets
        
        # LRRP tracking
        self.lrrp_events = []
        
        # XCMP tracking  
        self.xcmp_events = []
        
        # ARS tracking
        self.ars_events = []
        
        # Radio database
        self.radios = {}  # radio_id -> {ips: [], first_seen, last_seen, ...}
        
        # Raw records
        self.all_records = []
        self.nonzero_records = []
        
        # Stats
        self.stats = Counter()
        self.file_stats = {}
    
    def load_file(self, filename):
        """Load a JSONL capture file."""
        basename = os.path.basename(filename)
        total = 0
        nonzero = 0
        errors = 0
        
        with open(filename, 'r', encoding='utf-8') as f:
            for line in f:
                try:
                    r = json.loads(line)
                    total += 1
                    self.all_records.append(r)
                    
                    ln = r.get('len', 0)
                    if ln > 0:
                        nonzero += 1
                        self.nonzero_records.append(r)
                        self.process_record(r)
                    
                    self.stats['total'] += 1
                    self.stats[f"type_{r.get('type', 'UNK')}"] += 1
                    
                except Exception as e:
                    errors += 1
        
        self.file_stats[basename] = {'total': total, 'nonzero': nonzero, 'errors': errors}
        print(f"  Loaded {basename}: {total:,} total, {nonzero:,} non-zero payload, {errors} errors")
    
    def process_record(self, r):
        """Process a single record for IP reconstruction."""
        rtype = r.get('type', '')
        sap = r.get('sap', '')
        details = r.get('details', '')
        proto = r.get('proto', '')
        hex_data = r.get('hex', '')
        from_id = r.get('from', '')
        to_id = r.get('to', '')
        ts = r.get('ts', 0)
        
        # --- SNDCP Processing ---
        if rtype == 'SNDCP' or 'SNDCP' in sap:
            self.process_sndcp(r)
        
        # --- IP Packet Processing ---
        if rtype == 'PDU_PACKET' and hex_data:
            raw = hex_to_bytes(hex_data)
            if len(raw) >= 20:
                ip = parse_ipv4_header(raw)
                if ip and ip['version'] == 4:
                    self.process_ip_packet(r, raw, ip)
        
        # --- LRRP Processing ---
        if 'LRRP' in proto or 'LRRP' in sap:
            self.lrrp_events.append(r)
        
        # --- XCMP Processing ---
        if 'XCMP' in proto or 'XCMP' in sap:
            self.xcmp_events.append(r)
        
        # --- ARS Processing ---
        if 'ARS' in proto or 'ARS' in sap or ':4005' in sap:
            self.ars_events.append(r)
        
        # Track radio activity
        for rid in [from_id, to_id]:
            if rid and rid.isdigit():
                if rid not in self.radios:
                    self.radios[rid] = {'ips': set(), 'first_seen': ts, 'last_seen': ts, 'events': 0}
                self.radios[rid]['last_seen'] = max(self.radios[rid]['last_seen'], ts)
                self.radios[rid]['first_seen'] = min(self.radios[rid]['first_seen'], ts)
                self.radios[rid]['events'] += 1
    
    def process_sndcp(self, r):
        """Process SNDCP record for IP address tracking."""
        sap = r.get('sap', '')
        details = r.get('details', '')
        from_id = r.get('from', '')
        to_id = r.get('to', '')
        ts = r.get('ts', 0)
        
        radio_id = to_id or from_id
        
        event = {
            'ts': ts,
            'time': parse_timestamp(ts),
            'sap': sap,
            'radio': radio_id,
            'details': details[:200],
        }
        
        if 'ACTIVATE' in sap and 'ACCEPT' in sap:
            ip = extract_sndcp_ip(details)
            event['ip'] = ip
            event['action'] = 'ACTIVATE_ACCEPT'
            
            if radio_id and ip:
                if radio_id not in self.sndcp_sessions:
                    self.sndcp_sessions[radio_id] = []
                self.sndcp_sessions[radio_id].append({
                    'ip': ip, 'activate_ts': ts, 'deactivate_ts': None
                })
                if radio_id in self.radios:
                    self.radios[radio_id]['ips'].add(ip)
        
        elif 'DEACTIVATE' in sap:
            event['action'] = 'DEACTIVATE'
            if radio_id in self.sndcp_sessions and self.sndcp_sessions[radio_id]:
                self.sndcp_sessions[radio_id][-1]['deactivate_ts'] = ts
        
        elif 'REJECT' in sap:
            event['action'] = 'REJECT'
        
        elif 'UNCONFIRMED' in sap or 'RF_UNCONFIRMED' in sap:
            event['action'] = 'DATA'
        
        else:
            event['action'] = sap
        
        self.sndcp_events.append(event)
    
    def process_ip_packet(self, r, raw, ip_hdr):
        """Process an IP packet record."""
        ts = r.get('ts', 0)
        
        packet = {
            'ts': ts,
            'time': parse_timestamp(ts),
            'src_ip': ip_hdr['src_ip'],
            'dst_ip': ip_hdr['dst_ip'],
            'protocol': ip_hdr['protocol'],
            'total_len': ip_hdr['total_len'],
            'from_radio': r.get('from', ''),
            'to_radio': r.get('to', ''),
            'channel': r.get('channel', ''),
            'freq': r.get('freq', 0),
        }
        
        # Parse UDP if protocol == 17
        if ip_hdr['protocol'] == 17:
            udp = parse_udp_header(raw, ip_hdr['payload_offset'])
            if udp:
                packet['src_port'] = udp['src_port']
                packet['dst_port'] = udp['dst_port']
                packet['udp_len'] = udp['length']
                
                # Extract UDP payload
                payload_start = udp['payload_offset']
                payload_data = raw[payload_start:]
                packet['payload_hex'] = payload_data.hex()
                packet['payload_ascii'] = ascii_dump(payload_data)
                packet['payload_len'] = len(payload_data)
                
                # Identify app protocol
                packet['app_proto'] = identify_app_protocol(
                    udp['src_port'], udp['dst_port'], payload_data
                )
                
                # Track flow
                flow_key = (ip_hdr['src_ip'], ip_hdr['dst_ip'], 
                           udp['src_port'], udp['dst_port'])
                self.ip_flows[flow_key].append(packet)
        
        elif ip_hdr['protocol'] == 1:  # ICMP
            packet['app_proto'] = 'ICMP'
        elif ip_hdr['protocol'] == 6:  # TCP
            packet['app_proto'] = 'TCP'
        else:
            packet['app_proto'] = f'Proto-{ip_hdr["protocol"]}'
        
        self.ip_packets.append(packet)
    
    def report(self):
        """Generate comprehensive analysis report."""
        print('\n' + '='*80)
        print('  P25 IP LAYER RECONSTRUCTION & DEEP DATA ANALYSIS')
        print('='*80)
        
        self.report_overview()
        self.report_sndcp_sessions()
        self.report_ip_flows()
        self.report_ip_packets()
        self.report_lrrp()
        self.report_xcmp()
        self.report_ars()
        self.report_radio_database()
        self.report_payload_patterns()
        self.report_timeline()
    
    def report_overview(self):
        """File and record overview."""
        print('\n' + '-'*60)
        print('  1. CORPUS OVERVIEW')
        print('-'*60)
        
        total_all = sum(f['total'] for f in self.file_stats.values())
        total_nz = sum(f['nonzero'] for f in self.file_stats.values())
        
        print(f'\nFiles loaded: {len(self.file_stats)}')
        for name, stats in self.file_stats.items():
            pct = 100 * stats['nonzero'] / max(stats['total'], 1)
            print(f'  {name}')
            print(f'    Total: {stats["total"]:>10,}   Non-zero: {stats["nonzero"]:>8,} ({pct:.1f}%)')
        
        print(f'\nCombined totals:')
        print(f'  Total records:     {total_all:>10,}')
        print(f'  Non-zero payload:  {total_nz:>10,} ({100*total_nz/max(total_all,1):.1f}%)')
        print(f'  Zero-payload:      {total_all - total_nz:>10,} ({100*(total_all-total_nz)/max(total_all,1):.1f}%) [filtered]')
        
        # Type breakdown of non-zero records
        type_counts = Counter(r.get('type', '') for r in self.nonzero_records)
        print(f'\nNon-zero records by type:')
        for t, c in type_counts.most_common():
            print(f'  {t:25s} {c:>8,}')
        
        proto_counts = Counter(r.get('proto', '') for r in self.nonzero_records)
        print(f'\nNon-zero records by protocol:')
        for p, c in proto_counts.most_common():
            print(f'  {p or "(empty)":25s} {c:>8,}')
    
    def report_sndcp_sessions(self):
        """SNDCP IP address assignment analysis."""
        print('\n' + '-'*60)
        print('  2. SNDCP IP ADDRESS ASSIGNMENTS')
        print('-'*60)
        
        if not self.sndcp_events:
            print('  No SNDCP events found.')
            return
        
        action_counts = Counter(e.get('action', '') for e in self.sndcp_events)
        print(f'\nSNDCP event types:')
        for a, c in action_counts.most_common():
            print(f'  {a:40s} {c:>6,}')
        
        # IP assignments
        ip_map = {}  # ip -> set of radios
        radio_ips = defaultdict(set)
        
        for radio, sessions in self.sndcp_sessions.items():
            for s in sessions:
                ip = s.get('ip')
                if ip:
                    if ip not in ip_map:
                        ip_map[ip] = set()
                    ip_map[ip].add(radio)
                    radio_ips[radio].add(ip)
        
        print(f'\nUnique IP addresses assigned: {len(ip_map)}')
        print(f'Unique radios with IPs:       {len(radio_ips)}')
        
        # Subnet analysis
        subnets = Counter()
        for ip in ip_map:
            parts = ip.split('.')
            if len(parts) == 4:
                subnets[f'{parts[0]}.{parts[1]}.{parts[2]}.0/24'] += 1
                
        if subnets:
            print(f'\nIP Subnets (/24):')
            for subnet, count in subnets.most_common(20):
                print(f'  {subnet:25s} {count:>4} IPs')
        
        # Show sample sessions with duration
        sessions_with_duration = []
        for radio, sessions in self.sndcp_sessions.items():
            for s in sessions:
                if s.get('activate_ts') and s.get('deactivate_ts'):
                    duration = (s['deactivate_ts'] - s['activate_ts']) / 1000
                    sessions_with_duration.append({
                        'radio': radio, 'ip': s['ip'],
                        'duration_sec': duration,
                        'start': parse_timestamp(s['activate_ts']),
                    })
        
        if sessions_with_duration:
            sessions_with_duration.sort(key=lambda x: -x['duration_sec'])
            print(f'\nLongest SNDCP sessions (activate->deactivate):')
            for s in sessions_with_duration[:15]:
                dur = s['duration_sec']
                if dur > 3600:
                    dur_str = f'{dur/3600:.1f}h'
                elif dur > 60:
                    dur_str = f'{dur/60:.1f}m'
                else:
                    dur_str = f'{dur:.0f}s'
                print(f'  Radio {s["radio"]:>8s} -> {s["ip"]:>16s}  duration: {dur_str:>8s}  start: {s["start"]}')
        
        # Show radios with multiple IPs
        multi_ip = {r: ips for r, ips in radio_ips.items() if len(ips) > 1}
        if multi_ip:
            print(f'\nRadios with multiple IP assignments ({len(multi_ip)}):')
            for radio, ips in sorted(multi_ip.items(), key=lambda x: -len(x[1]))[:10]:
                print(f'  Radio {radio}: {", ".join(sorted(ips))}')
    
    def report_ip_flows(self):
        """IP flow reconstruction."""
        print('\n' + '-'*60)
        print('  3. RECONSTRUCTED IP FLOWS')
        print('-'*60)
        
        if not self.ip_flows:
            print('  No IP flows reconstructed.')
            print('  (IP packets require PacketMessage with full hex payload)')
            return
        
        print(f'\nTotal unique flows: {len(self.ip_flows)}')
        print(f'Total IP packets:   {len(self.ip_packets)}')
        
        # Sort by packet count
        sorted_flows = sorted(self.ip_flows.items(), key=lambda x: -len(x[1]))
        
        print(f'\nTop flows by packet count:')
        print(f'  {"Source":>21s}  {"Destination":>21s}  {"Proto":>12s}  {"Pkts":>5s}  {"Bytes":>8s}')
        print(f'  {"-"*21}  {"-"*21}  {"-"*12}  {"-"*5}  {"-"*8}')
        
        for (src_ip, dst_ip, src_port, dst_port), packets in sorted_flows[:30]:
            total_bytes = sum(p.get('payload_len', 0) for p in packets)
            app_proto = packets[0].get('app_proto', 'Unknown')
            src = f'{src_ip}:{src_port}'
            dst = f'{dst_ip}:{dst_port}'
            print(f'  {src:>21s}  {dst:>21s}  {app_proto:>12s}  {len(packets):>5d}  {total_bytes:>8,}')
        
        # Direction analysis
        outbound = 0  # infra -> radio (10.51.x.x -> 10.71.x.x)
        inbound = 0   # radio -> infra
        internal = 0
        
        for (src_ip, dst_ip, _, _), packets in self.ip_flows.items():
            count = len(packets)
            src_is_infra = src_ip.startswith('10.51.') or src_ip.startswith('192.168.')
            dst_is_infra = dst_ip.startswith('10.51.') or dst_ip.startswith('192.168.')
            src_is_radio = src_ip.startswith('10.71.')
            dst_is_radio = dst_ip.startswith('10.71.')
            
            if src_is_infra and dst_is_radio:
                outbound += count
            elif src_is_radio and dst_is_infra:
                inbound += count
            else:
                internal += count
        
        print(f'\nTraffic direction:')
        print(f'  Infrastructure -> Radio (outbound): {outbound:>6,} packets')
        print(f'  Radio -> Infrastructure (inbound):  {inbound:>6,} packets')
        print(f'  Other/Internal:                     {internal:>6,} packets')
    
    def report_ip_packets(self):
        """Detailed IP packet analysis."""
        print('\n' + '-'*60)
        print('  4. IP PACKET DETAILS')
        print('-'*60)
        
        if not self.ip_packets:
            print('  No IP packets with parseable headers found.')
            return
        
        # Protocol breakdown
        proto_counts = Counter(p.get('app_proto', 'Unknown') for p in self.ip_packets)
        print(f'\nApplication protocols:')
        for p, c in proto_counts.most_common():
            print(f'  {p:25s} {c:>6,}')
        
        # Show sample packets with payload
        packets_with_payload = [p for p in self.ip_packets if p.get('payload_len', 0) > 0]
        if packets_with_payload:
            print(f'\nSample IP packets with payload ({len(packets_with_payload)} total):')
            for p in packets_with_payload[:20]:
                src = f'{p["src_ip"]}:{p.get("src_port","?")}'
                dst = f'{p["dst_ip"]}:{p.get("dst_port","?")}'
                payload_hex = p.get('payload_hex', '')[:80]
                payload_ascii = p.get('payload_ascii', '')[:40]
                print(f'\n  [{p["time"]}] {src} -> {dst}')
                print(f'    Proto: {p.get("app_proto","?")}  Len: {p.get("payload_len",0)}')
                print(f'    Hex: {payload_hex}{"..." if len(p.get("payload_hex","")) > 80 else ""}')
                if payload_ascii.strip('.'):
                    print(f'    ASCII: {payload_ascii}')
                if p.get('from_radio'):
                    print(f'    Radio FROM: {p["from_radio"]}  TO: {p.get("to_radio","")}')
    
    def report_lrrp(self):
        """LRRP location data analysis."""
        print('\n' + '-'*60)
        print('  5. LRRP LOCATION DATA')
        print('-'*60)
        
        if not self.lrrp_events:
            print('  No LRRP events found.')
            return
        
        print(f'\nTotal LRRP events: {len(self.lrrp_events)}')
        
        # Categorize
        gps_events = [e for e in self.lrrp_events if e.get('lat') or 'Point2d' in e.get('details', '')]
        request_events = [e for e in self.lrrp_events if 'REQUEST' in e.get('sap', '').upper() or 'STOP' in e.get('details', '').upper()]
        
        print(f'  GPS coordinate responses: {len(gps_events)}')
        print(f'  Location requests:        {len(request_events)}')
        
        # Show GPS data
        for e in self.lrrp_events[:20]:
            ts = parse_timestamp(e.get('ts', 0))
            sap = e.get('sap', '')
            from_id = e.get('from', '')
            to_id = e.get('to', '')
            lat = e.get('lat')
            lon = e.get('lon')
            
            if lat and lon:
                print(f'\n  [{ts}] GPS: {lat:.6f}, {lon:.6f}')
                print(f'    From: {from_id}  To: {to_id}  SAP: {sap}')
            else:
                details = e.get('details', '')[:120]
                print(f'\n  [{ts}] {sap}')
                print(f'    From: {from_id}  To: {to_id}')
                print(f'    Details: {details}')
    
    def report_xcmp(self):
        """XCMP device management analysis."""
        print('\n' + '-'*60)
        print('  6. XCMP DEVICE MANAGEMENT')
        print('-'*60)
        
        if not self.xcmp_events:
            print('  No XCMP events found.')
            return
        
        print(f'\nTotal XCMP events: {len(self.xcmp_events)}')
        
        # Group by SAP
        sap_counts = Counter(e.get('sap', '') for e in self.xcmp_events)
        print(f'\nXCMP event types:')
        for s, c in sap_counts.most_common():
            print(f'  {s:45s} {c:>6,}')
        
        # Target radios
        targets = Counter()
        for e in self.xcmp_events:
            to = e.get('to', '')
            if to:
                targets[to] += 1
        
        print(f'\nTop XCMP target radios ({len(targets)} unique):')
        for radio, count in targets.most_common(15):
            print(f'  Radio {radio:>8s}: {count:>4} messages')
    
    def report_ars(self):
        """ARS registration analysis."""
        print('\n' + '-'*60)
        print('  7. ARS REGISTRATION SERVICE')
        print('-'*60)
        
        if not self.ars_events:
            print('  No ARS events found.')
            return
        
        print(f'\nTotal ARS events: {len(self.ars_events)}')
        
        # Target radios
        targets = Counter()
        for e in self.ars_events:
            to = e.get('to', '')
            if to:
                targets[to] += 1
        
        print(f'Unique radios contacted: {len(targets)}')
        print(f'\nTop ARS targets:')
        for radio, count in targets.most_common(15):
            print(f'  Radio {radio:>8s}: {count:>4} messages')
    
    def report_radio_database(self):
        """Radio activity summary."""
        print('\n' + '-'*60)
        print('  8. RADIO DATABASE')
        print('-'*60)
        
        if not self.radios:
            print('  No radio identifiers found.')
            return
        
        print(f'\nTotal unique radio IDs: {len(self.radios)}')
        
        # Radios with IP assignments
        with_ip = {r: info for r, info in self.radios.items() if info.get('ips')}
        print(f'Radios with IP assignments: {len(with_ip)}')
        
        # Most active radios (by event count in non-zero records)
        sorted_radios = sorted(self.radios.items(), key=lambda x: -x[1]['events'])
        print(f'\nMost active radios (non-zero payload events):')
        for radio, info in sorted_radios[:20]:
            ips = ', '.join(sorted(info.get('ips', set()))) or 'no IP'
            duration = (info['last_seen'] - info['first_seen']) / 1000
            dur_str = f'{duration/60:.0f}m' if duration > 60 else f'{duration:.0f}s'
            print(f'  Radio {radio:>8s}: {info["events"]:>5} events  IPs: {ips:>16s}  span: {dur_str}')
    
    def report_payload_patterns(self):
        """Analyze hex payload patterns in non-zero records."""
        print('\n' + '-'*60)
        print('  9. PAYLOAD PATTERN ANALYSIS')
        print('-'*60)
        
        # Group non-zero records by first bytes of hex
        first_byte_counts = Counter()
        length_dist = Counter()
        
        for r in self.nonzero_records:
            hex_data = r.get('hex', '')
            ln = r.get('len', 0)
            length_dist[ln] += 1
            
            raw = hex_to_bytes(hex_data)
            if len(raw) >= 1:
                first_byte_counts[f'0x{raw[0]:02X}'] += 1
        
        print(f'\nPayload length distribution (non-zero):')
        for length, count in sorted(length_dist.items()):
            bar = '#' * min(count // 10, 60)
            print(f'  {length:>5} bytes: {count:>6,}  {bar}')
        
        print(f'\nFirst byte distribution:')
        for byte_val, count in first_byte_counts.most_common(20):
            print(f'  {byte_val}: {count:>6,}')
        
        # Look for structured data patterns
        print(f'\nLooking for structured data patterns...')
        
        # Records with ASCII strings
        records_with_strings = [r for r in self.nonzero_records 
                               if r.get('strings') and len(r.get('strings', [])) > 0]
        if records_with_strings:
            print(f'\nRecords with detected ASCII strings: {len(records_with_strings)}')
            all_strings = Counter()
            for r in records_with_strings:
                for s in r.get('strings', []):
                    all_strings[s] += 1
            print(f'Unique strings: {len(all_strings)}')
            if all_strings:
                print(f'Top strings:')
                for s, c in all_strings.most_common(20):
                    print(f'  "{s}": {c}')
        
        # Records that look like they could contain IP data but weren't parsed
        potential_ip = []
        for r in self.nonzero_records:
            hex_data = r.get('hex', '')
            raw = hex_to_bytes(hex_data)
            if len(raw) >= 20 and r.get('type') != 'LSD':
                # Check for IPv4 header signature  
                if (raw[0] & 0xF0) == 0x40:  # Version 4
                    ip = parse_ipv4_header(raw)
                    if ip and ip['total_len'] <= len(raw) + 20:
                        if ip['src_ip'] != '0.0.0.0':
                            potential_ip.append((r, ip))
        
        if potential_ip:
            print(f'\nPotential unparsed IP packets found: {len(potential_ip)}')
            for r, ip in potential_ip[:10]:
                ts = parse_timestamp(r.get('ts', 0))
                print(f'  [{ts}] {ip["src_ip"]} -> {ip["dst_ip"]}  proto:{ip["protocol"]}  len:{ip["total_len"]}')
                print(f'    Type: {r.get("type")}  Class: {r.get("class")}  SAP: {r.get("sap")}')
    
    def report_timeline(self):
        """Timeline analysis of data activity."""
        print('\n' + '-'*60)
        print('  10. TIMELINE ANALYSIS')
        print('-'*60)
        
        if not self.nonzero_records:
            print('  No non-zero records for timeline.')
            return
        
        # Bucket by minute
        minute_buckets = Counter()
        minute_types = defaultdict(Counter)
        
        for r in self.nonzero_records:
            ts = r.get('ts', 0)
            minute = (ts // 60000) * 60000  # Round to minute
            minute_buckets[minute] += 1
            minute_types[minute][r.get('type', '')] += 1
        
        sorted_minutes = sorted(minute_buckets.items())
        if not sorted_minutes:
            return
        
        first_ts = sorted_minutes[0][0]
        last_ts = sorted_minutes[-1][0]
        duration_min = (last_ts - first_ts) / 60000
        
        print(f'\nCapture duration: {duration_min:.0f} minutes ({duration_min/60:.1f} hours)')
        print(f'First record: {parse_timestamp(first_ts)}')
        print(f'Last record:  {parse_timestamp(last_ts)}')
        print(f'Average rate: {len(self.nonzero_records) / max(duration_min, 1):.1f} records/min (non-zero)')
        
        # Show busiest periods
        print(f'\nBusiest minutes (top 10):')
        for ts, count in sorted(minute_buckets.items(), key=lambda x: -x[1])[:10]:
            types = minute_types[ts]
            type_str = ', '.join(f'{t}:{c}' for t, c in types.most_common(3))
            print(f'  {parse_timestamp(ts)}: {count:>5} records  [{type_str}]')
        
        # Hourly summary
        hour_buckets = Counter()
        for ts, count in minute_buckets.items():
            hour = (ts // 3600000) * 3600000
            hour_buckets[hour] += count
        
        if len(hour_buckets) > 1:
            print(f'\nHourly summary:')
            for hour_ts, count in sorted(hour_buckets.items()):
                bar = '#' * min(count // 50, 60)
                print(f'  {parse_timestamp(hour_ts)[:16]}: {count:>6,} records  {bar}')


def main():
    files = sys.argv[1:] if len(sys.argv) > 1 else sorted(
        f'logs/{f}' for f in os.listdir('logs') 
        if f.startswith('p25_data_') and f.endswith('.jsonl') and '20260325' in f
    )
    
    if not files:
        # Fall back to all files
        if os.path.exists('logs'):
            files = sorted(
                f'logs/{f}' for f in os.listdir('logs') 
                if f.startswith('p25_data_') and f.endswith('.jsonl')
            )
    
    if not files:
        print('No capture files found. Usage: python tools/ip_reconstruct.py [files...]')
        sys.exit(1)
    
    print(f'Loading {len(files)} capture file(s)...')
    
    reconstructor = IPReconstructor()
    for f in files:
        if os.path.exists(f):
            reconstructor.load_file(f)
        else:
            print(f'  WARNING: {f} not found, skipping')
    
    reconstructor.report()


if __name__ == '__main__':
    main()
