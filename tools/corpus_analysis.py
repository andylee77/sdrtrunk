"""Comprehensive analysis of P25 data capture JSONL corpus files."""
import json, glob, os, sys
from collections import Counter, defaultdict
from datetime import datetime

logs_dir = r'c:\Users\Andy\Projects\SDRTrunk\sdrtrunk\logs'
files = sorted(glob.glob(os.path.join(logs_dir, 'p25_data_*.jsonl')))

records = []
parse_errors = 0
for f in files:
    with open(f, 'r', encoding='utf-8', errors='replace') as fh:
        for line_no, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
                rec['_file'] = os.path.basename(f)
                records.append(rec)
            except json.JSONDecodeError:
                parse_errors += 1

print(f"=== P25 DATA CAPTURE CORPUS ANALYSIS ===")
print(f"Files: {len(files)}")
for f in files:
    sz = os.path.getsize(f)
    print(f"  {os.path.basename(f):60s} {sz:>10,} bytes")
print(f"Total records: {len(records):,}")
print(f"Parse errors: {parse_errors}")
print()

# --- By System ---
by_system = Counter(r['_file'].replace('p25_data_','').rsplit('_',1)[0] for r in records)
print("=== RECORDS BY SYSTEM ===")
for sys_name, count in by_system.most_common():
    print(f"  {sys_name:45s} {count:>6,}")
print()

# --- By Type ---
by_type = Counter(r.get('type','?') for r in records)
print("=== RECORDS BY TYPE ===")
for t, count in by_type.most_common():
    print(f"  {t:25s} {count:>6,} ({100*count/len(records):.1f}%)")
print()

# --- By Protocol ---
by_proto = Counter(r.get('proto','') for r in records)
print("=== RECORDS BY PROTOCOL ===")
for p, count in by_proto.most_common(20):
    print(f"  {(p or '(empty)'):25s} {count:>6,}")
print()

# --- By SAP/Opcode ---
by_sap = Counter(r.get('sap','') for r in records)
print("=== TOP 25 SAP/OPCODE VALUES ===")
for s, count in by_sap.most_common(25):
    print(f"  {(s or '(empty)'):50s} {count:>6,}")
print()

# --- By Class ---
by_class = Counter(r.get('class','') for r in records)
print("=== RECORDS BY MESSAGE CLASS ===")
for c, count in by_class.most_common(15):
    print(f"  {c:40s} {count:>6,}")
print()

# --- Unique From IDs ---
from_ids = Counter(r.get('from','') for r in records if r.get('from',''))
print(f"=== UNIQUE FROM IDs: {len(from_ids)} ===")
print("  Top 15 most active:")
for fid, count in from_ids.most_common(15):
    print(f"    {fid:20s} {count:>6,} records")
print()

# --- Unique To IDs ---
to_ids = Counter(r.get('to','') for r in records if r.get('to',''))
print(f"=== UNIQUE TO IDs: {len(to_ids)} ===")
print("  Top 15:")
for tid, count in to_ids.most_common(15):
    print(f"    {tid:20s} {count:>6,} records")
print()

# --- Frequencies ---
freqs = Counter(r.get('freq',0) for r in records if r.get('freq',0) > 0)
print(f"=== UNIQUE FREQUENCIES: {len(freqs)} ===")
for freq, count in freqs.most_common(20):
    print(f"    {freq/1e6:>10.4f} MHz   {count:>6,} records")
print()

# --- Channels ---
channels = Counter(r.get('channel','') for r in records if r.get('channel',''))
print(f"=== UNIQUE CHANNELS: {len(channels)} ===")
for ch, count in channels.most_common(20):
    print(f"    {ch:15s} {count:>6,} records")
print()

# --- GPS Records ---
gps_records = [r for r in records if 'lat' in r and 'lon' in r]
print(f"=== GPS RECORDS: {len(gps_records)} ===")
if gps_records:
    unique_positions = set()
    for g in gps_records:
        lat = round(g['lat'], 4)
        lon = round(g['lon'], 4)
        unique_positions.add((lat, lon))
    print(f"  Unique positions (4dp): {len(unique_positions)}")
    for g in gps_records[:10]:
        ts = datetime.fromtimestamp(g['ts']/1000).strftime('%H:%M:%S') if g.get('ts') else '?'
        from_id = g.get('from','?')
        lat = g.get('lat', 0)
        lon = g.get('lon', 0)
        hdg = g.get('heading', None)
        spd = g.get('speed', None)
        extras = ""
        if hdg is not None:
            extras += f" hdg:{hdg:.0f}"
        if spd is not None:
            extras += f" spd:{spd:.1f}km/h"
        print(f"  {ts} from={from_id:>10s} lat={lat:.6f} lon={lon:.6f}{extras}")
    if len(gps_records) > 10:
        print(f"  ... and {len(gps_records)-10} more")
print()

# --- Strings detected ---
all_strings = []
for r in records:
    for s in r.get('strings', []):
        all_strings.append(s)
str_counter = Counter(all_strings)
print(f"=== DETECTED STRINGS: {len(all_strings)} total, {len(str_counter)} unique ===")
for s, count in str_counter.most_common(15):
    print(f"  {s:40s} {count:>4,}x")
print()

# --- Payload size distribution ---
sizes = [r.get('len',0) for r in records if r.get('len',0) > 0]
if sizes:
    print(f"=== PAYLOAD SIZE DISTRIBUTION ===")
    print(f"  Total payloads with data: {len(sizes)}")
    print(f"  Min: {min(sizes)} bytes, Max: {max(sizes)} bytes, Avg: {sum(sizes)/len(sizes):.1f} bytes")
    brackets = [(0,10),(10,50),(50,100),(100,500),(500,1000),(1000,5000)]
    for lo, hi in brackets:
        cnt = sum(1 for s in sizes if lo <= s < hi)
        if cnt:
            print(f"  {lo:>5d}-{hi:<5d} bytes: {cnt:>6,}")
print()

# --- Time range ---
timestamps = [r.get('ts',0) for r in records if r.get('ts',0) > 0]
if timestamps:
    first = datetime.fromtimestamp(min(timestamps)/1000)
    last = datetime.fromtimestamp(max(timestamps)/1000)
    duration = (max(timestamps) - min(timestamps)) / 1000 / 60
    print(f"=== TIME RANGE ===")
    print(f"  First: {first.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  Last:  {last.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  Duration: {duration:.1f} minutes ({duration/60:.1f} hours)")
    rate = len(records) / (duration if duration > 0 else 1)
    print(f"  Avg capture rate: {rate:.1f} records/minute")
