#!/usr/bin/env python3
"""
DATCH Phase 2 Analysis Tool — Cross-Session & Deep Protocol Investigation

Follows up on the initial corpus analysis (023_datch_corpus_analysis_findings.md)
to investigate:
  1. XOR of idle families (EC vs B6) to reveal FEC/interleaving structure
  2. Cross-session comparison (do different channels share idle patterns?)
  3. Data burst deep dive (EC-variant byte diffs, burst structure)
  4. High-entropy burst investigation
  5. Counter correlation and timing analysis
  6. Bit-level field extraction for protocol reverse engineering

Usage:
    python datch_phase2_analysis.py <jsonl_file> [--xor] [--cross] [--bursts] [--entropy] [--all]
"""

import json
import sys
import math
import argparse
from collections import Counter, defaultdict
from datetime import datetime


def load_datch_records(filepath):
    """Load DATCH_RAW records from JSONL file."""
    records = []
    with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
                if rec.get('type') == 'DATCH_RAW':
                    records.append(rec)
            except json.JSONDecodeError:
                pass
    return records


def hex_to_bytes(hex_str):
    """Convert hex string to bytes."""
    return bytes.fromhex(hex_str.replace(' ', ''))


def format_ts(ms):
    """Format millisecond timestamp."""
    return datetime.fromtimestamp(ms / 1000.0).strftime('%H:%M:%S.%f')[:-3]


def xor_bytes(a, b):
    """XOR two byte arrays."""
    return bytes(x ^ y for x, y in zip(a, b))


def popcount(val):
    """Count set bits."""
    return bin(val).count('1')


def byte_entropy(data):
    """Calculate Shannon entropy of a byte sequence."""
    if not data:
        return 0.0
    freq = Counter(data)
    n = len(data)
    return -sum((c / n) * math.log2(c / n) for c in freq.values())


def split_sessions(records, gap_ms=2000):
    """Split records into sessions by time gap."""
    sorted_recs = sorted(records, key=lambda r: r.get('ts', 0))
    sessions = []
    cur = []
    for r in sorted_recs:
        if cur and r['ts'] - cur[-1]['ts'] > gap_ms:
            sessions.append(cur)
            cur = []
        cur.append(r)
    if cur:
        sessions.append(cur)
    return sessions


def get_idle_canonical(session_bytes):
    """Find the most common (idle) payload for each family signature."""
    families = defaultdict(list)
    for b in session_bytes:
        sig = b[1:9].hex()
        families[sig].append(b)

    # The two largest families are the idle families
    sorted_fams = sorted(families.items(), key=lambda x: -len(x[1]))
    result = {}
    for sig, members in sorted_fams[:2]:
        # Find the most common exact payload
        payload_counter = Counter(m.hex() for m in members)
        canonical_hex = payload_counter.most_common(1)[0][0]
        result[sig] = {
            'canonical': bytes.fromhex(canonical_hex),
            'count': len(members),
            'sig': sig,
            'members': members
        }
    return result


def analyze_xor_idle_families(records):
    """XOR the two idle families to reveal FEC/interleaving structure."""
    print("=" * 80)
    print("ANALYSIS 1: XOR OF IDLE FAMILIES (EC vs B6)")
    print("=" * 80)
    print("Goal: XOR reveals which bits differ between families, potentially exposing")
    print("      FEC structure, interleaving patterns, or payload field boundaries.\n")

    sessions = split_sessions(records)

    for si, session in enumerate(sessions):
        freq = session[0].get('freq', 0)
        print(f"--- Session {si + 1} ({freq / 1e6:.4f} MHz, {len(session)} slots) ---\n")

        all_bytes = [hex_to_bytes(r['hex']) for r in session]
        idles = get_idle_canonical(all_bytes)

        if len(idles) < 2:
            print("  Less than 2 idle families found, skipping.\n")
            continue

        fam_list = list(idles.values())
        fam_a = fam_list[0]
        fam_b = fam_list[1]

        print(f"  Family A ({fam_a['sig'][:8]}...): {fam_a['count']} slots")
        print(f"    Canonical: {fam_a['canonical'].hex(' ')}")
        print(f"  Family B ({fam_b['sig'][:8]}...): {fam_b['count']} slots")
        print(f"    Canonical: {fam_b['canonical'].hex(' ')}")

        # XOR the canonical payloads
        xor_result = xor_bytes(fam_a['canonical'], fam_b['canonical'])
        print(f"\n  XOR (A ^ B): {xor_result.hex(' ')}")

        # Analyze the XOR result
        total_diff_bits = sum(popcount(b) for b in xor_result)
        total_bits = len(xor_result) * 8
        print(f"\n  Differing bits: {total_diff_bits} / {total_bits} ({100 * total_diff_bits / total_bits:.1f}%)")

        # Per-byte analysis
        print(f"\n  Per-byte XOR breakdown:")
        print(f"  {'Pos':>3s}  {'XOR':>4s}  {'Binary':>10s}  {'Bits':>4s}  {'FamA':>4s}  {'FamB':>4s}")
        for i, (x, a, b) in enumerate(zip(xor_result, fam_a['canonical'], fam_b['canonical'])):
            bits = popcount(x)
            marker = ""
            if x == 0x00:
                marker = "  (same)"
            elif x == 0xFF:
                marker = "  (ALL DIFF)"
            elif bits >= 6:
                marker = "  (mostly diff)"
            print(f"  [{i:2d}]  0x{x:02X}  {x:08b}  {bits:4d}  0x{a:02X}  0x{b:02X}{marker}")

        # Look for patterns in the XOR
        print(f"\n  XOR pattern analysis:")
        # Check for repeating byte patterns
        for period in [2, 4, 5, 8, 10, 20]:
            if len(xor_result) % period == 0:
                chunks = [xor_result[i:i + period] for i in range(0, len(xor_result), period)]
                unique = len(set(c.hex() for c in chunks))
                if unique <= period:
                    print(f"    Period {period}: {unique} unique chunks")

        # Bit-level run analysis
        bit_str = ''.join(f'{b:08b}' for b in xor_result)
        runs = []
        cur_bit = bit_str[0]
        cur_len = 1
        for bit in bit_str[1:]:
            if bit == cur_bit:
                cur_len += 1
            else:
                runs.append((cur_bit, cur_len))
                cur_bit = bit
                cur_len = 1
        runs.append((cur_bit, cur_len))

        long_runs = [(b, l) for b, l in runs if l >= 4]
        if long_runs:
            print(f"    Long bit runs (>=4) in XOR: {len(long_runs)}")
            for b, l in long_runs[:10]:
                print(f"      {l}x '{b}'")

        print()


def analyze_cross_session(records):
    """Compare idle families across sessions on different frequencies."""
    print("=" * 80)
    print("ANALYSIS 2: CROSS-SESSION COMPARISON")
    print("=" * 80)
    print("Goal: Determine if idle patterns are frequency-specific or system-wide.\n")

    sessions = split_sessions(records)

    if len(sessions) < 2:
        print("  Only 1 session found, need at least 2 for cross-session comparison.\n")
        return

    session_idles = []
    for si, session in enumerate(sessions):
        freq = session[0].get('freq', 0)
        all_bytes = [hex_to_bytes(r['hex']) for r in session]
        idles = get_idle_canonical(all_bytes)
        session_idles.append((si, freq, idles, all_bytes))
        print(f"  Session {si + 1} ({freq / 1e6:.4f} MHz): {len(idles)} idle families")
        for sig, info in idles.items():
            print(f"    {sig[:8]}...: {info['count']} slots")

    # Compare idle signatures across sessions
    print(f"\n  Cross-session idle family comparison:")
    all_sigs = set()
    for _, _, idles, _ in session_idles:
        all_sigs.update(idles.keys())

    for sig in sorted(all_sigs):
        present_in = []
        for si, freq, idles, _ in session_idles:
            if sig in idles:
                present_in.append(f"S{si + 1}({freq / 1e6:.4f}MHz, n={idles[sig]['count']})")
        shared = "SHARED" if len(present_in) > 1 else "unique"
        print(f"    {sig[:8]}...: {shared} — {', '.join(present_in)}")

    # If we have shared families, XOR their canonical forms across sessions
    print(f"\n  Cross-session canonical payload comparison:")
    for sig in sorted(all_sigs):
        canonicals = []
        for si, freq, idles, _ in session_idles:
            if sig in idles:
                canonicals.append((si, freq, idles[sig]['canonical']))
        if len(canonicals) >= 2:
            a_si, a_freq, a_can = canonicals[0]
            b_si, b_freq, b_can = canonicals[1]
            xor_result = xor_bytes(a_can, b_can)
            diff_bits = sum(popcount(b) for b in xor_result)
            if diff_bits == 0:
                print(f"    {sig[:8]}...: IDENTICAL across sessions!")
            else:
                print(f"    {sig[:8]}...: {diff_bits} bits differ")
                print(f"      S{a_si + 1}: {a_can.hex(' ')}")
                print(f"      S{b_si + 1}: {b_can.hex(' ')}")
                print(f"      XOR:  {xor_result.hex(' ')}")
                # Show which positions differ
                diffs = [i for i, x in enumerate(xor_result) if x != 0]
                print(f"      Diff positions: {diffs}")

    # TS1/TS2 ratio comparison
    print(f"\n  Timeslot ratio comparison:")
    for si, session in enumerate(sessions):
        ts1 = sum(1 for r in session if 'TS1' in r.get('sap', ''))
        ts2 = sum(1 for r in session if 'TS2' in r.get('sap', ''))
        freq = session[0].get('freq', 0)
        ratio = ts1 / max(ts2, 1)
        print(f"    S{si + 1} ({freq / 1e6:.4f} MHz): TS1={ts1}, TS2={ts2}, ratio={ratio:.2f}")

    # Superframe pattern comparison
    print(f"\n  Superframe pattern comparison:")
    for si, session in enumerate(sessions):
        all_bytes = [hex_to_bytes(r['hex']) for r in session]
        sigs = []
        for i, b in enumerate(all_bytes):
            ts = "1" if 'TS1' in session[i].get('sap', '') else "2"
            fam = b[1:3].hex()
            ft = (b[0] >> 6) & 0x3
            sigs.append(f"{ts}:{fam}:{ft}")

        # Test superframe sizes
        for sf_size in range(3, 15):
            matches = 0
            total = 0
            for i in range(sf_size, min(300, len(sigs))):
                total += 1
                if sigs[i] == sigs[i - sf_size]:
                    matches += 1
            if total > 0:
                pct = matches / total * 100
                if pct > 60:
                    print(f"    S{si + 1}: superframe size {sf_size} = {pct:.1f}% match")

    print()


def analyze_data_bursts(records):
    """Deep dive into data burst structure and content."""
    print("=" * 80)
    print("ANALYSIS 3: DATA BURST DEEP DIVE")
    print("=" * 80)
    print("Goal: Characterize non-idle timeslots, find structure in data payloads.\n")

    sessions = split_sessions(records)

    for si, session in enumerate(sessions):
        freq = session[0].get('freq', 0)
        all_bytes = [hex_to_bytes(r['hex']) for r in session]
        idles = get_idle_canonical(all_bytes)
        idle_sigs = set(idles.keys())

        # Identify non-idle timeslots
        data_slots = []
        for i, b in enumerate(all_bytes):
            sig = b[1:9].hex()
            if sig not in idle_sigs:
                data_slots.append((i, b, session[i]))

        print(f"--- Session {si + 1} ({freq / 1e6:.4f} MHz) ---")
        print(f"  Total slots: {len(all_bytes)}, Data slots: {len(data_slots)} ({100 * len(data_slots) / len(all_bytes):.1f}%)\n")

        if not data_slots:
            print("  No data bursts found.\n")
            continue

        # Group data slots into bursts (consecutive or near-consecutive)
        bursts = []
        cur_burst = [data_slots[0]]
        for ds in data_slots[1:]:
            if ds[0] - cur_burst[-1][0] <= 3:  # Within 3 slots = same burst
                cur_burst.append(ds)
            else:
                bursts.append(cur_burst)
                cur_burst = [ds]
        bursts.append(cur_burst)

        print(f"  Found {len(bursts)} data bursts:\n")

        for bi, burst in enumerate(bursts):
            first_idx = burst[0][0]
            last_idx = burst[-1][0]
            first_ts = burst[0][2].get('ts', 0)
            last_ts = burst[-1][2].get('ts', 0)
            dur_ms = last_ts - first_ts

            print(f"  Burst {bi + 1}: slots [{first_idx}-{last_idx}], {len(burst)} data slots, {dur_ms}ms")

            # Family breakdown within burst
            burst_fams = Counter()
            for _, b, _ in burst:
                fam = b[0:3].hex()
                burst_fams[fam] += 1
            print(f"    Families: {dict(burst_fams.most_common(10))}")

            # Entropy per slot
            entropies = [byte_entropy(b) for _, b, _ in burst]
            avg_ent = sum(entropies) / len(entropies)
            max_ent = max(entropies)
            min_ent = min(entropies)
            print(f"    Entropy: avg={avg_ent:.2f}, min={min_ent:.2f}, max={max_ent:.2f} (8.0=random)")

            # Show TS1/TS2 breakdown
            ts1_ct = sum(1 for _, _, r in burst if 'TS1' in r.get('sap', ''))
            ts2_ct = sum(1 for _, _, r in burst if 'TS2' in r.get('sap', ''))
            print(f"    TS distribution: TS1={ts1_ct}, TS2={ts2_ct}")

            # XOR each data slot against nearest idle canonical
            print(f"    Data vs idle XOR (showing which bytes carry data):")
            for di, (idx, b, rec) in enumerate(burst[:5]):
                # Find closest idle family by first byte similarity
                best_idle = None
                best_match = -1
                for sig, info in idles.items():
                    match = sum(1 for x, y in zip(b[1:9], info['canonical'][1:9]) if x == y)
                    if match > best_match:
                        best_match = match
                        best_idle = info['canonical']

                if best_idle is not None:
                    xor = xor_bytes(b, best_idle)
                    diff_bytes = [i for i, x in enumerate(xor) if x != 0]
                    diff_bits = sum(popcount(x) for x in xor)
                    ts = "TS1" if 'TS1' in rec.get('sap', '') else "TS2"
                    print(f"      [{idx}] {ts} | {diff_bits} bits diff | positions: {diff_bytes}")
                    if di == 0:
                        print(f"             XOR: {xor.hex(' ')}")

            # Show raw hex of first few slots
            print(f"    Raw data (first 5 slots):")
            for idx, b, rec in burst[:5]:
                ts = "TS1" if 'TS1' in rec.get('sap', '') else "TS2"
                ent = byte_entropy(b)
                print(f"      [{idx:4d}] {ts} ent={ent:.2f} | {b.hex(' ')}")

            print()


def analyze_entropy_patterns(records):
    """Detailed entropy analysis to classify timeslot content types."""
    print("=" * 80)
    print("ANALYSIS 4: ENTROPY & CONTENT CLASSIFICATION")
    print("=" * 80)
    print("Goal: Classify every timeslot by entropy to find encrypted/compressed data.\n")

    sessions = split_sessions(records)

    for si, session in enumerate(sessions):
        freq = session[0].get('freq', 0)
        all_bytes = [hex_to_bytes(r['hex']) for r in session]

        print(f"--- Session {si + 1} ({freq / 1e6:.4f} MHz, {len(session)} slots) ---\n")

        # Compute entropy for each timeslot
        entropies = [(i, byte_entropy(b), b) for i, b in enumerate(all_bytes)]

        # Histogram
        bins = [0, 1, 2, 3, 4, 5, 6, 7, 8.01]
        hist = [0] * (len(bins) - 1)
        for _, ent, _ in entropies:
            for j in range(len(bins) - 1):
                if bins[j] <= ent < bins[j + 1]:
                    hist[j] += 1
                    break

        print(f"  Entropy histogram:")
        for j in range(len(bins) - 1):
            bar = "#" * (hist[j] * 60 // max(max(hist), 1))
            print(f"    {bins[j]:.0f}-{bins[j + 1]:.0f}: {hist[j]:5d} {bar}")

        # High entropy slots (potential encrypted content)
        high_ent = [(i, ent, b) for i, ent, b in entropies if ent > 6.5]
        if high_ent:
            print(f"\n  HIGH ENTROPY slots (>{6.5}): {len(high_ent)}")
            for i, ent, b in high_ent:
                ts = "TS1" if 'TS1' in session[i].get('sap', '') else "TS2"
                t = format_ts(session[i].get('ts', 0))
                print(f"    [{i:4d}] {t} {ts} ent={ent:.2f} | {b.hex(' ')}")

            # Check if high-entropy slots are consecutive
            if len(high_ent) > 1:
                indices = [i for i, _, _ in high_ent]
                gaps = [indices[j + 1] - indices[j] for j in range(len(indices) - 1)]
                print(f"    Slot gaps between high-entropy: {gaps}")
                if all(g <= 2 for g in gaps):
                    print(f"    >>> ALL CONSECUTIVE — likely a single encrypted burst")

                # Bit distribution within high-entropy region
                all_high_bytes = b''.join(b for _, _, b in high_ent)
                ones = sum(popcount(byte) for byte in all_high_bytes)
                total = len(all_high_bytes) * 8
                print(f"    Bit distribution: {ones}/{total} ones = {100 * ones / total:.1f}% (50.0% = perfectly random)")

        # Low entropy slots (structured data)
        low_ent = [(i, ent, b) for i, ent, b in entropies if 3.0 < ent < 5.5]
        if low_ent:
            print(f"\n  STRUCTURED DATA slots (entropy 3.0-5.5): {len(low_ent)}")
            for i, ent, b in low_ent[:10]:
                ts = "TS1" if 'TS1' in session[i].get('sap', '') else "TS2"
                # Count zero bytes
                zeros = sum(1 for byte in b if byte == 0)
                print(f"    [{i:4d}] {ts} ent={ent:.2f} zeros={zeros} | {b.hex(' ')}")

        # Very low entropy (sparse/maintenance)
        vlow_ent = [(i, ent, b) for i, ent, b in entropies if ent <= 3.0]
        if vlow_ent:
            print(f"\n  SPARSE/MAINTENANCE slots (entropy <=3.0): {len(vlow_ent)}")
            for i, ent, b in vlow_ent[:5]:
                ts = "TS1" if 'TS1' in session[i].get('sap', '') else "TS2"
                zeros = sum(1 for byte in b if byte == 0)
                print(f"    [{i:4d}] {ts} ent={ent:.2f} zeros={zeros} | {b.hex(' ')}")

        print()


def analyze_counter_timing(records):
    """Correlate counter fields with timing and slot position."""
    print("=" * 80)
    print("ANALYSIS 5: COUNTER FIELD TIMING CORRELATION")
    print("=" * 80)
    print("Goal: Understand how byte 9, byte 30, byte 39 counters relate to time.\n")

    sessions = split_sessions(records)

    for si, session in enumerate(sessions):
        freq = session[0].get('freq', 0)
        all_bytes = [hex_to_bytes(r['hex']) for r in session]
        idles = get_idle_canonical(all_bytes)

        print(f"--- Session {si + 1} ({freq / 1e6:.4f} MHz) ---\n")

        # For each idle family, track counter progression over time
        for sig, info in idles.items():
            members = []
            for i, b in enumerate(all_bytes):
                if b[1:9].hex() == sig:
                    members.append((i, b, session[i]))

            if len(members) < 10:
                continue

            print(f"  Family {sig[:8]}... ({len(members)} slots):")

            # Track byte 9 high nibble cycle
            b9_values = [(i, b[9]) for i, b, _ in members]
            b9_hi = [(i, (v >> 4) & 0xF) for i, v in b9_values]
            b9_lo = [(i, v & 0xF) for i, v in b9_values]

            # Find cycle length
            hi_vals = [v for _, v in b9_hi[:50]]
            print(f"    Byte 9 high nibble (first 50): {' '.join(f'{v:X}' for v in hi_vals)}")

            # Detect cycle
            for cycle_len in range(2, 8):
                if len(hi_vals) >= cycle_len * 3:
                    pattern = hi_vals[:cycle_len]
                    matches = 0
                    checks = 0
                    for j in range(cycle_len, len(hi_vals)):
                        checks += 1
                        if hi_vals[j] == pattern[j % cycle_len]:
                            matches += 1
                    if checks > 0 and matches / checks > 0.9:
                        print(f"    >>> Byte 9 high nibble: {cycle_len}-cycle detected: {[f'{v:X}' for v in pattern]}")
                        break

            # Byte 30 bits[3:2] cycle
            b30_vals = [(i, (b[30] >> 2) & 0x3) for i, b, _ in members]
            sub_vals = [v for _, v in b30_vals[:50]]
            print(f"    Byte 30 bits[3:2] (first 50): {' '.join(str(v) for v in sub_vals)}")

            # Byte 39 tracking
            b39_vals = [b[39] for _, b, _ in members[:30]]
            print(f"    Byte 39 (first 30): {' '.join(f'{v:02X}' for v in b39_vals)}")

            # Timing between consecutive same-family slots
            time_diffs = []
            for j in range(1, min(30, len(members))):
                dt = members[j][2].get('ts', 0) - members[j - 1][2].get('ts', 0)
                time_diffs.append(dt)
            if time_diffs:
                avg_dt = sum(time_diffs) / len(time_diffs)
                print(f"    Avg inter-slot time: {avg_dt:.1f}ms (first 30)")
                dt_counter = Counter(time_diffs)
                print(f"    Time diff distribution: {dict(dt_counter.most_common(8))}")

            print()


def analyze_fec_candidates(records):
    """Try to identify the FEC scheme by analyzing bit patterns."""
    print("=" * 80)
    print("ANALYSIS 6: FEC SCHEME IDENTIFICATION")
    print("=" * 80)
    print("Goal: Determine what FEC (if any) is applied to the 320-bit timeslot.\n")

    sessions = split_sessions(records)
    session = sessions[0]  # Use largest session
    all_bytes = [hex_to_bytes(r['hex']) for r in session]
    idles = get_idle_canonical(all_bytes)

    # Get EC and B6 idle canonical forms
    fam_list = list(idles.values())
    if len(fam_list) < 2:
        print("  Need at least 2 idle families.\n")
        return

    ec_canonical = fam_list[0]['canonical']
    b6_canonical = fam_list[1]['canonical']

    print(f"  EC canonical ({len(ec_canonical)} bytes): {ec_canonical.hex(' ')}")
    print(f"  B6 canonical ({len(b6_canonical)} bytes): {b6_canonical.hex(' ')}")

    # Convert to bit strings
    ec_bits = ''.join(f'{b:08b}' for b in ec_canonical)
    b6_bits = ''.join(f'{b:08b}' for b in b6_canonical)

    print(f"\n  EC bits ({len(ec_bits)}): {ec_bits[:80]}...")
    print(f"  B6 bits ({len(b6_bits)}): {b6_bits[:80]}...")

    # Check for known FEC signatures:

    # 1. Trellis 1/2 rate: 320 bits in = 160 bits payload
    print(f"\n  FEC Rate Analysis:")
    print(f"    Raw timeslot: 320 bits (40 bytes)")
    print(f"    If 1/2-rate trellis: 160 bits payload (20 bytes)")
    print(f"    If 3/4-rate: 240 bits payload (30 bytes)")
    print(f"    If no FEC (just CRC): ~304-312 bits payload + 8-16 bit CRC")

    # 2. Check if removing known counter positions reveals more structure
    # Positions that vary: 0, 9, 30, 39 (from findings)
    # That's 4 bytes = 32 bits of framing overhead
    # Remaining: 36 bytes = 288 bits of payload (if no FEC)
    print(f"\n  Known framing overhead: bytes [0, 9, 30, 39] = 32 bits")
    print(f"    Remaining payload: 288 bits (36 bytes)")

    # 3. Check for convolutional code signature
    # In a 1/2-rate code, consecutive output bits are pairwise related
    print(f"\n  Consecutive bit-pair correlation (convolutional code test):")
    for name, bits in [("EC", ec_bits), ("B6", b6_bits)]:
        # Take pairs of bits and check if they're correlated
        pairs = [(bits[i], bits[i + 1]) for i in range(0, len(bits) - 1, 2)]
        pair_counter = Counter(pairs)
        print(f"    {name} bit pairs: {dict(pair_counter)}")

        # Check for systematic code (payload bits appear at regular intervals)
        # In a rate-1/2 systematic code, every other bit is the data bit
        even_bits = bits[::2]
        odd_bits = bits[1::2]
        even_ones = even_bits.count('1')
        odd_ones = odd_bits.count('1')
        print(f"    {name} even-position 1s: {even_ones}/{len(even_bits)}, odd-position 1s: {odd_ones}/{len(odd_bits)}")

    # 4. Check the XOR between families for Hamming distance patterns
    xor_bits = ''.join(str(int(a) ^ int(b)) for a, b in zip(ec_bits, b6_bits))
    # In a linear code, the XOR of two codewords is also a codeword
    xor_weight = xor_bits.count('1')
    print(f"\n  XOR Hamming weight: {xor_weight} / {len(xor_bits)} ({100 * xor_weight / len(xor_bits):.1f}%)")
    print(f"  (If this is a codeword in a linear code, the weight tells us about minimum distance)")

    # 5. Try interpreting as interleaved blocks
    print(f"\n  Block interleave test (reorder bits in various patterns):")
    for rows in [4, 5, 8, 10, 16, 20]:
        cols = 320 // rows
        if 320 % rows != 0:
            continue
        # De-interleave: read column-by-column instead of row-by-row
        deinterleaved = ''
        for c in range(cols):
            for r in range(rows):
                deinterleaved += ec_bits[r * cols + c]
        # Check if de-interleaved has more structure (longer runs of same bit)
        runs = 1
        for j in range(1, len(deinterleaved)):
            if deinterleaved[j] != deinterleaved[j - 1]:
                runs += 1
        orig_runs = 1
        for j in range(1, len(ec_bits)):
            if ec_bits[j] != ec_bits[j - 1]:
                orig_runs += 1
        improvement = (orig_runs - runs) / orig_runs * 100
        if abs(improvement) > 5:
            print(f"    {rows}x{cols}: runs {orig_runs} -> {runs} ({improvement:+.1f}% {'less' if improvement > 0 else 'more'} transitions)")

    print()


def main():
    parser = argparse.ArgumentParser(
        description="DATCH Phase 2 Deep Analysis — Cross-Session & Protocol Investigation"
    )
    parser.add_argument('jsonl_file', help='Path to JSONL corpus file')
    parser.add_argument('--xor', action='store_true', help='XOR idle families analysis')
    parser.add_argument('--cross', action='store_true', help='Cross-session comparison')
    parser.add_argument('--bursts', action='store_true', help='Data burst deep dive')
    parser.add_argument('--entropy', action='store_true', help='Entropy classification')
    parser.add_argument('--counters', action='store_true', help='Counter timing analysis')
    parser.add_argument('--fec', action='store_true', help='FEC identification analysis')
    parser.add_argument('--all', action='store_true', help='Run all analyses')

    args = parser.parse_args()

    if args.all:
        args.xor = args.cross = args.bursts = args.entropy = args.counters = args.fec = True

    # Default: run the most important analyses
    if not any([args.xor, args.cross, args.bursts, args.entropy, args.counters, args.fec]):
        args.xor = args.cross = args.bursts = True

    records = load_datch_records(args.jsonl_file)

    if not records:
        print("No DATCH_RAW records found.")
        return

    print(f"Loaded {len(records)} DATCH records\n")

    sessions = split_sessions(records)
    for i, s in enumerate(sessions):
        freq = s[0].get('freq', 0)
        dur = (s[-1]['ts'] - s[0]['ts']) / 1000
        print(f"  Session {i + 1}: {freq / 1e6:.4f} MHz, {len(s)} slots, {dur:.1f}s")
    print()

    if args.xor:
        analyze_xor_idle_families(records)
    if args.cross:
        analyze_cross_session(records)
    if args.bursts:
        analyze_data_bursts(records)
    if args.entropy:
        analyze_entropy_patterns(records)
    if args.counters:
        analyze_counter_timing(records)
    if args.fec:
        analyze_fec_candidates(records)


if __name__ == '__main__':
    main()
