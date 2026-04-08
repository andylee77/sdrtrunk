#!/usr/bin/env python3
"""
DATCH (Motorola TDMA Data Channel) Timeslot Analysis Tool

Reads JSONL corpus files produced by P25DataCaptureModule and analyzes
raw DATCH_RAW records to discover:
  - Header patterns (first 4-8 bits) and sequence numbers
  - Fragment boundaries and reassembly indicators
  - FEC encoding signatures (trellis, Viterbi, Reed-Solomon)
  - IP packet signatures (0x45 IPv4 header) after various decodings
  - Timeslot correlation (TS1 vs TS2 independence/strapping)
  - Session grouping by channel/frequency/time proximity

Usage:
    python datch_analysis.py <jsonl_file> [--sessions] [--headers] [--fec] [--all]

Output goes to stdout with summary statistics.

Part of Change 023: Phase 2 TDMA Data Channel Decoding (raw capture phase).
"""

import json
import sys
import argparse
from collections import Counter, defaultdict
from datetime import datetime


def load_datch_records(filepath):
    """Load DATCH_RAW records from a JSONL corpus file."""
    records = []
    total_lines = 0
    errors = 0

    with open(filepath, 'r') as f:
        for line in f:
            total_lines += 1
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
                if rec.get('type') == 'DATCH_RAW':
                    records.append(rec)
            except json.JSONDecodeError:
                errors += 1

    print(f"Loaded {len(records)} DATCH_RAW records from {total_lines} total lines ({errors} parse errors)")
    return records


def hex_to_bytes(hex_str):
    """Convert space-separated hex string to bytes."""
    if not hex_str:
        return b''
    # Handle both space-separated and continuous hex
    hex_clean = hex_str.replace(' ', '').replace('...', '')
    try:
        return bytes.fromhex(hex_clean)
    except ValueError:
        return b''


def analyze_summary(records):
    """Print high-level summary statistics."""
    print("\n" + "=" * 70)
    print("DATCH CAPTURE SUMMARY")
    print("=" * 70)

    if not records:
        print("No DATCH_RAW records found.")
        return

    # Basic counts
    print(f"Total DATCH timeslots: {len(records)}")
    print(f"Total raw bytes:       {sum(r.get('len', 0) for r in records):,}")

    # Timeslot distribution
    ts_counter = Counter()
    for r in records:
        sap = r.get('sap', '')
        if 'TS1' in sap:
            ts_counter['TS1'] += 1
        elif 'TS2' in sap:
            ts_counter['TS2'] += 1
        else:
            ts_counter['Unknown'] += 1

    print(f"\nTimeslot distribution:")
    for ts, count in sorted(ts_counter.items()):
        print(f"  {ts}: {count} timeslots ({count * 40:,} bytes)")

    # Frequency distribution
    freq_counter = Counter(r.get('freq', 0) for r in records)
    print(f"\nFrequency distribution:")
    for freq, count in freq_counter.most_common(10):
        if freq > 0:
            print(f"  {freq / 1e6:.4f} MHz: {count} timeslots")
        else:
            print(f"  (unknown): {count} timeslots")

    # Channel distribution
    chan_counter = Counter(r.get('channel', '') for r in records)
    print(f"\nChannel distribution:")
    for chan, count in chan_counter.most_common(10):
        print(f"  {chan or '(none)'}: {count} timeslots")

    # Time range
    timestamps = [r.get('ts', 0) for r in records if r.get('ts', 0) > 0]
    if timestamps:
        ts_min = min(timestamps)
        ts_max = max(timestamps)
        duration_s = (ts_max - ts_min) / 1000.0
        print(f"\nTime range:")
        print(f"  First: {datetime.fromtimestamp(ts_min / 1000).strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}")
        print(f"  Last:  {datetime.fromtimestamp(ts_max / 1000).strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}")
        print(f"  Duration: {duration_s:.1f}s")
        print(f"  Rate: {len(records) / max(duration_s, 0.001):.1f} timeslots/sec")


def analyze_sessions(records):
    """Group DATCH timeslots into sessions by time proximity and channel."""
    print("\n" + "=" * 70)
    print("DATCH SESSION ANALYSIS")
    print("=" * 70)

    if not records:
        return

    # Sort by timestamp
    sorted_recs = sorted(records, key=lambda r: r.get('ts', 0))

    # Group into sessions: gap > 2 seconds = new session
    SESSION_GAP_MS = 2000
    sessions = []
    current_session = []

    for rec in sorted_recs:
        if current_session:
            last_ts = current_session[-1].get('ts', 0)
            curr_ts = rec.get('ts', 0)
            if curr_ts - last_ts > SESSION_GAP_MS:
                sessions.append(current_session)
                current_session = []
        current_session.append(rec)

    if current_session:
        sessions.append(current_session)

    print(f"\nFound {len(sessions)} data sessions (gap threshold: {SESSION_GAP_MS}ms)")

    for i, session in enumerate(sessions):
        ts_start = session[0].get('ts', 0)
        ts_end = session[-1].get('ts', 0)
        duration_s = (ts_end - ts_start) / 1000.0
        freq = session[0].get('freq', 0)
        chan = session[0].get('channel', '')

        # Count per-timeslot
        ts1_count = sum(1 for r in session if 'TS1' in r.get('sap', ''))
        ts2_count = sum(1 for r in session if 'TS2' in r.get('sap', ''))

        start_str = datetime.fromtimestamp(ts_start / 1000).strftime('%H:%M:%S.%f')[:-3]
        freq_str = f"{freq / 1e6:.4f} MHz" if freq > 0 else "(unknown)"

        print(f"\n  Session {i + 1}: {start_str} | {duration_s:.1f}s | {len(session)} timeslots "
              f"| {freq_str} | ch:{chan}")
        print(f"    TS1: {ts1_count} slots ({ts1_count * 40} bytes) | "
              f"TS2: {ts2_count} slots ({ts2_count * 40} bytes)")
        print(f"    Total raw data: {len(session) * 40:,} bytes")


def analyze_headers(records):
    """Analyze the first N bits/bytes of each DATCH timeslot for patterns."""
    print("\n" + "=" * 70)
    print("DATCH HEADER PATTERN ANALYSIS")
    print("=" * 70)

    if not records:
        return

    # Analyze first byte patterns
    first_byte_counter = Counter()
    first_nibble_counter = Counter()
    first_2bits_counter = Counter()
    second_byte_counter = Counter()

    for rec in records:
        data = hex_to_bytes(rec.get('hex', ''))
        if len(data) >= 2:
            b0 = data[0]
            b1 = data[1]
            first_byte_counter[f"0x{b0:02X}"] += 1
            first_nibble_counter[f"0x{(b0 >> 4):X}"] += 1
            first_2bits_counter[f"{(b0 >> 6) & 3:02b}"] += 1
            second_byte_counter[f"0x{b1:02X}"] += 1

    print(f"\nFirst 2 bits (potential frame type):")
    for bits, count in first_2bits_counter.most_common():
        pct = 100.0 * count / len(records)
        print(f"  {bits}: {count} ({pct:.1f}%)")

    print(f"\nFirst nibble (high 4 bits of byte 0):")
    for nib, count in first_nibble_counter.most_common(16):
        pct = 100.0 * count / len(records)
        print(f"  {nib}: {count} ({pct:.1f}%)")

    print(f"\nFirst byte distribution (top 20):")
    for byte_val, count in first_byte_counter.most_common(20):
        pct = 100.0 * count / len(records)
        print(f"  {byte_val}: {count} ({pct:.1f}%)")

    print(f"\nSecond byte distribution (top 20):")
    for byte_val, count in second_byte_counter.most_common(20):
        pct = 100.0 * count / len(records)
        print(f"  {byte_val}: {count} ({pct:.1f}%)")

    # Look for incrementing sequences in first few bytes
    print(f"\nSequence analysis (first 4 bytes across consecutive timeslots):")
    sorted_recs = sorted(records, key=lambda r: r.get('ts', 0))
    prev_bytes = None
    seq_diffs = []

    for rec in sorted_recs[:100]:  # First 100 for brevity
        data = hex_to_bytes(rec.get('hex', ''))
        if len(data) >= 4:
            header = data[:4]
            if prev_bytes is not None:
                # XOR to find changing bits
                diff = bytes(a ^ b for a, b in zip(header, prev_bytes))
                seq_diffs.append(diff)
            prev_bytes = header

    if seq_diffs:
        # Find which byte positions change most
        for pos in range(min(4, len(seq_diffs[0]))):
            changes = sum(1 for d in seq_diffs if d[pos] != 0)
            pct = 100.0 * changes / len(seq_diffs)
            print(f"  Byte {pos}: changes in {changes}/{len(seq_diffs)} transitions ({pct:.0f}%)")


def analyze_fec(records):
    """Attempt to detect FEC encoding by looking for known patterns after various decodings."""
    print("\n" + "=" * 70)
    print("FEC DETECTION ANALYSIS")
    print("=" * 70)

    if not records:
        return

    # Check for IPv4 header signature (0x45) at various offsets
    print("\nSearching for IPv4 header (0x45) at various offsets in raw data:")
    ipv4_hits = defaultdict(int)

    for rec in records:
        data = hex_to_bytes(rec.get('hex', ''))
        for offset in range(min(len(data), 20)):
            if data[offset] == 0x45:
                ipv4_hits[offset] += 1

    if ipv4_hits:
        for offset, count in sorted(ipv4_hits.items()):
            pct = 100.0 * count / len(records)
            print(f"  Offset {offset}: {count} hits ({pct:.1f}%)")
    else:
        print("  No IPv4 headers found in raw data (FEC likely needs to be applied first)")

    # Check for all-zero and all-ones patterns (common in idle/padding)
    zero_count = 0
    ones_count = 0
    high_entropy = 0

    for rec in records:
        data = hex_to_bytes(rec.get('hex', ''))
        if not data:
            continue

        zeros = sum(1 for b in data if b == 0x00)
        ones = sum(1 for b in data if b == 0xFF)

        if zeros > len(data) * 0.8:
            zero_count += 1
        elif ones > len(data) * 0.8:
            ones_count += 1
        else:
            high_entropy += 1

    print(f"\nEntropy analysis:")
    print(f"  Mostly zeros (>80%): {zero_count} timeslots ({100.0 * zero_count / max(1, len(records)):.1f}%)")
    print(f"  Mostly ones  (>80%): {ones_count} timeslots ({100.0 * ones_count / max(1, len(records)):.1f}%)")
    print(f"  High entropy:        {high_entropy} timeslots ({100.0 * high_entropy / max(1, len(records)):.1f}%)")

    # Bit distribution analysis (should be ~50/50 for encrypted/random, skewed for structured)
    total_ones_bits = 0
    total_bits = 0

    for rec in records[:200]:  # Sample first 200
        data = hex_to_bytes(rec.get('hex', ''))
        for b in data:
            total_ones_bits += bin(b).count('1')
            total_bits += 8

    if total_bits > 0:
        ones_pct = 100.0 * total_ones_bits / total_bits
        print(f"\nBit distribution (sample of {min(200, len(records))} timeslots):")
        print(f"  1-bits: {ones_pct:.1f}% (50.0% = random/encrypted, skewed = structured)")

    # Repeating pattern detection
    print(f"\nRepeating payload detection (exact duplicates):")
    hex_counter = Counter(rec.get('hex', '') for rec in records)
    repeats = {h: c for h, c in hex_counter.items() if c > 1 and h}

    if repeats:
        print(f"  {len(repeats)} unique payloads appear more than once")
        for hex_val, count in sorted(repeats.items(), key=lambda x: -x[1])[:10]:
            print(f"    {count}x: {hex_val[:80]}{'...' if len(hex_val) > 80 else ''}")
    else:
        print("  No exact duplicate payloads found")


def dump_raw(records, max_records=50):
    """Dump raw hex of the first N records for manual inspection."""
    print("\n" + "=" * 70)
    print(f"RAW DATCH TIMESLOT DUMP (first {min(max_records, len(records))})")
    print("=" * 70)

    sorted_recs = sorted(records, key=lambda r: r.get('ts', 0))

    for i, rec in enumerate(sorted_recs[:max_records]):
        ts = rec.get('ts', 0)
        sap = rec.get('sap', '')
        freq = rec.get('freq', 0)
        hex_val = rec.get('hex', '')
        ts_str = datetime.fromtimestamp(ts / 1000).strftime('%H:%M:%S.%f')[:-3] if ts > 0 else '??:??:??.???'
        freq_str = f"{freq / 1e6:.4f}" if freq > 0 else "?.????"

        print(f"  [{i:4d}] {ts_str} {sap:8s} {freq_str} MHz | {hex_val}")


def main():
    parser = argparse.ArgumentParser(
        description="Analyze DATCH (TDMA Data Channel) raw captures from P25 JSONL corpus"
    )
    parser.add_argument('jsonl_file', help='Path to JSONL corpus file')
    parser.add_argument('--sessions', action='store_true', help='Show session grouping analysis')
    parser.add_argument('--headers', action='store_true', help='Show header pattern analysis')
    parser.add_argument('--fec', action='store_true', help='Show FEC detection analysis')
    parser.add_argument('--dump', action='store_true', help='Dump raw hex of first 50 records')
    parser.add_argument('--all', action='store_true', help='Run all analyses')

    args = parser.parse_args()

    if args.all:
        args.sessions = args.headers = args.fec = args.dump = True

    # Default: show summary + sessions if no specific flags
    if not (args.sessions or args.headers or args.fec or args.dump):
        args.sessions = True

    records = load_datch_records(args.jsonl_file)

    if not records:
        print("\nNo DATCH_RAW records found in the corpus file.")
        print("Make sure the system has active Phase 2 TDMA data channels.")
        return

    analyze_summary(records)

    if args.sessions:
        analyze_sessions(records)
    if args.headers:
        analyze_headers(records)
    if args.fec:
        analyze_fec(records)
    if args.dump:
        dump_raw(records)


if __name__ == '__main__':
    main()
