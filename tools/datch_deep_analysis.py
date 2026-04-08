#!/usr/bin/env python3
"""
Deep DATCH Analysis Tool
Investigates bit-level structure of DATCH timeslot payloads from corpus data.
"""

import json
import sys
from collections import Counter, defaultdict
from datetime import datetime

def load_datch_records(filepath):
    """Load DATCH_RAW records from JSONL file."""
    records = []
    total = 0
    errors = 0
    with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            total += 1
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
                if rec.get('type') == 'DATCH_RAW':
                    records.append(rec)
            except json.JSONDecodeError:
                errors += 1
    print(f"Loaded {len(records)} DATCH_RAW records from {total} total lines ({errors} parse errors)\n")
    return records

def hex_to_bytes(hex_str):
    """Convert space-separated or continuous hex string to bytes."""
    clean = hex_str.replace(' ', '')
    return bytes.fromhex(clean)

def format_ts(ms):
    """Format millisecond timestamp."""
    return datetime.fromtimestamp(ms / 1000.0).strftime('%H:%M:%S.%f')[:-3]

def xor_bytes(a, b):
    """XOR two byte arrays."""
    return bytes(x ^ y for x, y in zip(a, b))

def analyze_payload_families(records):
    """Group payloads by their stable bytes (ignoring varying positions)."""
    print("=" * 70)
    print("PAYLOAD FAMILY ANALYSIS")
    print("=" * 70)
    
    # First, find which byte positions are most stable vs varying
    all_bytes = [hex_to_bytes(r['hex']) for r in records]
    n = len(all_bytes)
    
    # For each byte position, count unique values
    print("\nByte position variability (unique values across all timeslots):")
    print("  Pos: Unique  Top values")
    for pos in range(40):
        vals = Counter(b[pos] for b in all_bytes)
        top3 = vals.most_common(5)
        top_str = ", ".join(f"0x{v:02X}({c})" for v, c in top3)
        marker = " ***" if len(vals) > 10 else (" **" if len(vals) > 4 else "")
        print(f"  [{pos:2d}]: {len(vals):4d}    {top_str}{marker}")
    
    # Group by bytes 1-8 (the most stable "signature" region)
    print("\n\nPayload families (grouped by bytes 1-8):")
    families = defaultdict(list)
    for i, b in enumerate(all_bytes):
        sig = b[1:9].hex()
        families[sig].append(i)
    
    for sig, indices in sorted(families.items(), key=lambda x: -len(x[1])):
        sample = all_bytes[indices[0]]
        ts_name = records[indices[0]].get('sap', '?')
        print(f"\n  Family '{sig}' — {len(indices)} timeslots")
        print(f"    Sample: {sample.hex(' ')}")
        
        # Show byte 0 distribution within family
        byte0_vals = Counter(all_bytes[idx][0] for idx in indices)
        print(f"    Byte 0: {', '.join(f'0x{v:02X}({c})' for v, c in byte0_vals.most_common(10))}")
        
        # Show byte 9 distribution within family
        byte9_vals = Counter(all_bytes[idx][9] for idx in indices)
        print(f"    Byte 9: {', '.join(f'0x{v:02X}({c})' for v, c in byte9_vals.most_common(10))}")
        
        # Show last byte distribution
        last_vals = Counter(all_bytes[idx][39] for idx in indices)
        print(f"    Byte39: {', '.join(f'0x{v:02X}({c})' for v, c in last_vals.most_common(10))}")

def analyze_byte0_frame_counter(records):
    """Analyze byte 0 bits [7:6] as potential frame counter."""
    print("\n" + "=" * 70)
    print("BYTE 0 FRAME COUNTER ANALYSIS")
    print("=" * 70)
    
    all_bytes = [hex_to_bytes(r['hex']) for r in records]
    
    # Extract bits [7:6] of byte 0
    frame_types = [(b[0] >> 6) & 0x3 for b in all_bytes]
    
    # Show transitions
    print("\nFrame type sequence (first 100 records, bits [7:6] of byte 0):")
    line = ""
    for i, ft in enumerate(frame_types[:100]):
        line += str(ft)
        if (i + 1) % 50 == 0:
            print(f"  [{i-49:4d}-{i:4d}] {line}")
            line = ""
    if line:
        print(f"  [{len(frame_types[:100])-len(line):4d}-{len(frame_types[:100])-1:4d}] {line}")
    
    # Extract bits [5:0] of byte 0 to see the lower bits
    print("\nByte 0 breakdown — bits [7:6] (frame type) vs bits [5:0] (lower):")
    for ft in range(4):
        lower = Counter((all_bytes[i][0] & 0x3F) for i, b in enumerate(all_bytes) if (b[0] >> 6) & 0x3 == ft)
        top5 = lower.most_common(10)
        print(f"  Frame type {ft} ({bin(ft)}):")
        for v, c in top5:
            print(f"    lower=0x{v:02X} ({v:06b}): {c}")

def analyze_xor_differences(records):
    """XOR consecutive payloads to find what changes."""
    print("\n" + "=" * 70)
    print("XOR DIFFERENCE ANALYSIS (consecutive timeslots)")
    print("=" * 70)
    
    all_bytes = [hex_to_bytes(r['hex']) for r in records]
    
    # XOR each pair of consecutive payloads
    diff_positions = Counter()
    for i in range(len(all_bytes) - 1):
        diff = xor_bytes(all_bytes[i], all_bytes[i + 1])
        for pos, val in enumerate(diff):
            if val != 0:
                diff_positions[pos] += 1
    
    print(f"\nByte positions that change between consecutive timeslots (out of {len(all_bytes)-1} transitions):")
    for pos in range(40):
        changes = diff_positions.get(pos, 0)
        pct = changes / (len(all_bytes) - 1) * 100
        bar = "#" * int(pct / 2)
        print(f"  [{pos:2d}]: {changes:5d} ({pct:5.1f}%) {bar}")
    
    # XOR same-family consecutive payloads
    print("\n\nXOR within same family (consecutive same-signature pairs):")
    families = defaultdict(list)
    for i, b in enumerate(all_bytes):
        sig = b[1:9].hex()
        families[sig].append(i)
    
    for sig, indices in sorted(families.items(), key=lambda x: -len(x[1]))[:3]:
        print(f"\n  Family '{sig}' ({len(indices)} members):")
        intra_diffs = Counter()
        xor_samples = []
        for j in range(len(indices) - 1):
            a = all_bytes[indices[j]]
            b = all_bytes[indices[j + 1]]
            diff = xor_bytes(a, b)
            if len(xor_samples) < 5:
                xor_samples.append((indices[j], indices[j+1], diff))
            for pos, val in enumerate(diff):
                if val != 0:
                    intra_diffs[pos] += 1
        
        print(f"    Varying positions (out of {len(indices)-1} intra-family transitions):")
        for pos in range(40):
            changes = intra_diffs.get(pos, 0)
            if changes > 0:
                pct = changes / (len(indices) - 1) * 100
                print(f"      [{pos:2d}]: {changes:5d} ({pct:5.1f}%)")
        
        print(f"    First 5 intra-family XOR diffs:")
        for idx_a, idx_b, diff in xor_samples:
            nonzero = [(p, v) for p, v in enumerate(diff) if v != 0]
            nz_str = ", ".join(f"[{p}]=0x{v:02X}" for p, v in nonzero)
            print(f"      [{idx_a}]^[{idx_b}]: {nz_str}")

def analyze_timeslot_interleaving(records):
    """Analyze TS1 vs TS2 interleaving pattern."""
    print("\n" + "=" * 70)
    print("TIMESLOT INTERLEAVING PATTERN")
    print("=" * 70)
    
    all_bytes = [hex_to_bytes(r['hex']) for r in records]
    
    ts1_records = [(i, r) for i, r in enumerate(records) if 'TS1' in r.get('sap', '')]
    ts2_records = [(i, r) for i, r in enumerate(records) if 'TS2' in r.get('sap', '')]
    
    print(f"\nTS1: {len(ts1_records)} records")
    print(f"TS2: {len(ts2_records)} records")
    
    # Show interleaving pattern
    print("\nTimeslot sequence (first 60):")
    for i in range(min(60, len(records))):
        ts = "TS1" if 'TS1' in records[i].get('sap', '') else "TS2"
        b = all_bytes[i]
        sig = b[1:3].hex()
        ft = (b[0] >> 6) & 0x3
        print(f"  [{i:3d}] {format_ts(records[i]['ts'])} {ts} ft={ft} sig={sig} byte0=0x{b[0]:02X} byte9=0x{b[9]:02X} last=0x{b[39]:02X}")

def analyze_repeating_superframe(records):
    """Look for repeating superframe structure."""
    print("\n" + "=" * 70)
    print("SUPERFRAME PATTERN DETECTION")
    print("=" * 70)
    
    all_bytes = [hex_to_bytes(r['hex']) for r in records]
    
    # Build a pattern signature for each slot
    sigs = []
    for i, b in enumerate(all_bytes):
        ts = "1" if 'TS1' in records[i].get('sap', '') else "2"
        fam = b[1:3].hex()
        ft = (b[0] >> 6) & 0x3
        sigs.append(f"{ts}:{fam}:{ft}")
    
    # Try different superframe sizes
    for sf_size in range(3, 15):
        matches = 0
        total = 0
        for i in range(sf_size, min(200, len(sigs))):
            total += 1
            if sigs[i] == sigs[i - sf_size]:
                matches += 1
        if total > 0:
            pct = matches / total * 100
            if pct > 60:
                print(f"  Superframe size {sf_size}: {pct:.1f}% match")
    
    # Show the pattern for the best match
    print("\nFirst 30 slot signatures:")
    for i in range(min(30, len(sigs))):
        print(f"  [{i:3d}] {sigs[i]}")

def analyze_data_transitions(records):
    """Find where the data changes from idle to active content."""
    print("\n" + "=" * 70)
    print("DATA TRANSITION ANALYSIS")
    print("=" * 70)
    
    all_bytes = [hex_to_bytes(r['hex']) for r in records]
    
    # The two dominant families by byte1 signature
    dom_sigs = Counter(b[1:3].hex() for b in all_bytes).most_common(2)
    dom_set = {sig for sig, _ in dom_sigs}
    print(f"Dominant signatures: {dom_sigs}")
    
    # Find transitions where a non-dominant signature appears
    transitions = []
    for i, b in enumerate(all_bytes):
        sig = b[1:3].hex()
        if sig not in dom_set:
            transitions.append(i)
    
    print(f"\nNon-dominant timeslots (potential data bursts): {len(transitions)} out of {len(all_bytes)}")
    
    # Show context around transitions
    shown = set()
    for t in transitions[:30]:
        start = max(0, t - 1)
        end = min(len(all_bytes), t + 2)
        for i in range(start, end):
            if i not in shown:
                shown.add(i)
                b = all_bytes[i]
                ts = "TS1" if 'TS1' in records[i].get('sap', '') else "TS2"
                sig = b[1:3].hex()
                marker = " <<<" if sig not in dom_set else ""
                print(f"  [{i:4d}] {format_ts(records[i]['ts'])} {ts} | {b.hex(' ')}{marker}")
        if end < len(all_bytes) - 1:
            print("  ---")

def analyze_bit_fields(records):
    """Analyze specific bit fields within the dominant payloads."""
    print("\n" + "=" * 70)
    print("BIT FIELD ANALYSIS (byte 9 and byte 29-30)")
    print("=" * 70)
    
    all_bytes = [hex_to_bytes(r['hex']) for r in records]
    
    # Focus on the EC family (most common)
    ec_family = [(i, b) for i, b in enumerate(all_bytes) if b[1:3].hex() == 'ec27']
    b6_family = [(i, b) for i, b in enumerate(all_bytes) if b[1:3].hex() == 'b684']
    
    print(f"\nEC family ({len(ec_family)} members):")
    
    # Byte 9 analysis
    byte9_vals = Counter(b[9] for _, b in ec_family)
    print(f"  Byte 9 values: {', '.join(f'0x{v:02X}={v:08b}({c})' for v, c in byte9_vals.most_common(10))}")
    
    # Byte 29 analysis
    byte29_vals = Counter(b[29] for _, b in ec_family)
    print(f"  Byte 29 values: {', '.join(f'0x{v:02X}={v:08b}({c})' for v, c in byte29_vals.most_common(10))}")
    
    # Byte 30 analysis
    byte30_vals = Counter(b[30] for _, b in ec_family)
    print(f"  Byte 30 values: {', '.join(f'0x{v:02X}={v:08b}({c})' for v, c in byte30_vals.most_common(10))}")
    
    # Byte 39 (last) analysis
    byte39_vals = Counter(b[39] for _, b in ec_family)
    print(f"  Byte 39 values: {', '.join(f'0x{v:02X}={v:08b}({c})' for v, c in byte39_vals.most_common(10))}")
    
    print(f"\nB6 family ({len(b6_family)} members):")
    byte9_vals = Counter(b[9] for _, b in b6_family)
    print(f"  Byte 9 values: {', '.join(f'0x{v:02X}={v:08b}({c})' for v, c in byte9_vals.most_common(10))}")
    byte29_vals = Counter(b[29] for _, b in b6_family)
    print(f"  Byte 29 values: {', '.join(f'0x{v:02X}={v:08b}({c})' for v, c in byte29_vals.most_common(10))}")
    byte39_vals = Counter(b[39] for _, b in b6_family)
    print(f"  Byte 39 values: {', '.join(f'0x{v:02X}={v:08b}({c})' for v, c in byte39_vals.most_common(10))}")
    
    # Correlate byte 9 with byte 0 frame type
    print(f"\n  EC family: byte 0 [7:6] vs byte 9:")
    for ft in range(4):
        b9 = Counter(b[9] for _, b in ec_family if (b[0] >> 6) & 0x3 == ft)
        if b9:
            print(f"    ft={ft}: byte9={', '.join(f'0x{v:02X}({c})' for v, c in b9.most_common(5))}")

def main():
    if len(sys.argv) < 2:
        print("Usage: python datch_deep_analysis.py <jsonl_file>")
        sys.exit(1)
    
    records = load_datch_records(sys.argv[1])
    if not records:
        print("No DATCH records found.")
        sys.exit(0)
    
    analyze_payload_families(records)
    analyze_byte0_frame_counter(records)
    analyze_bit_fields(records)
    analyze_xor_differences(records)
    analyze_timeslot_interleaving(records)
    analyze_repeating_superframe(records)
    analyze_data_transitions(records)

if __name__ == '__main__':
    main()
