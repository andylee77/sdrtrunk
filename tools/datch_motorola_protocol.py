#!/usr/bin/env python3
"""
DATCH Motorola Protocol Deep Analysis Tool

Performs deeper analysis of DATCH data bursts to identify Motorola-proprietary
protocol structures within the captured timeslot data.

Key analyses:
  1. EC-variant XOR extraction -- reveals user data encoded as bit diffs from idle
  2. Data family protocol signatures -- identifies Motorola opcodes/headers
  3. Multi-slot burst reassembly -- concatenates payloads for protocol detection
  4. Cross-burst correlation -- finds repeated protocol structures
  5. Motorola protocol signature search (OTAR, XCMP, ARS, LRRP, MDT, etc.)
  6. Nibble/byte pattern analysis for reverse engineering framing

Usage:
    python datch_motorola_protocol.py <jsonl_file> [--session N] [--all]
"""

import json
import sys
import math
import argparse
from collections import Counter, defaultdict
from datetime import datetime


# ============================================================================
# Known Motorola / P25 protocol signatures to search for
# ============================================================================
PROTOCOL_SIGNATURES = {
    # SNDCP (Sub-Network Dependent Convergence Protocol)
    'sndcp_activate': bytes([0xC0]),       # SNDCP Activate TDS Context Request
    'sndcp_data':     bytes([0x80]),       # SNDCP Data PDU
    'sndcp_deact':    bytes([0xE0]),       # SNDCP Deactivate

    # IP Headers
    'ipv4':           bytes([0x45]),       # IPv4, header length 20
    'ipv4_tos0':      bytes([0x45, 0x00]),
    'ipv6':           bytes([0x60]),       # IPv6

    # UDP
    'udp_lrrp_dst':   bytes([0x00, 0x4C, 0x50]),  # UDP dst port for LRRP (various)

    # Motorola OTAR (Over-The-Air Rekeying)
    'otar_msg':       bytes([0x0A]),       # OTAR message indicator
    'otar_rekey':     bytes([0x0C]),       # OTAR rekey

    # Motorola XCMP (eXtensible Command Protocol)
    'xcmp_header':    bytes([0x00, 0x80]), # XCMP message start

    # ARS (Automatic Registration Service)
    'ars_register':   bytes([0xD0]),       # ARS registration
    'ars_deregister': bytes([0xD1]),       # ARS deregistration
    'ars_query':      bytes([0xD3]),       # ARS query

    # Motorola TMS (Text Messaging Service)
    'tms_header':     bytes([0xA0]),       # TMS message header
    'tms_ack':        bytes([0xA1]),       # TMS acknowledgment

    # Common framing bytes
    'null_padding':   bytes([0x00, 0x00, 0x00, 0x00]),
    'ff_padding':     bytes([0xFF, 0xFF, 0xFF, 0xFF]),
}

# Known Motorola MDT (Mobile Data Terminal) opcodes
MOTOROLA_MDT_OPCODES = {
    0x00: 'NULL/Padding',
    0x01: 'Data Request',
    0x02: 'Data Response',
    0x03: 'Data Confirm',
    0x04: 'Status Update',
    0x05: 'Status Query',
    0x06: 'Status Response',
    0x10: 'GPS Location',
    0x11: 'GPS Request',
    0x20: 'Message Send',
    0x21: 'Message Ack',
    0x30: 'Group Data',
    0x40: 'Emergency Data',
    0x80: 'SNDCP Data',
    0xA0: 'TMS Message',
    0xC0: 'SNDCP Control',
    0xD0: 'ARS Message',
    0xE0: 'SNDCP Deactivate',
    0xF0: 'System Control',
}


def load_datch_records(filepath):
    """Load DATCH_RAW records from JSONL file."""
    records = []
    errors = 0
    with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line, strict=False)
                if rec.get('type') == 'DATCH_RAW':
                    records.append(rec)
            except (json.JSONDecodeError, ValueError):
                errors += 1
    records.sort(key=lambda r: r.get('ts', 0))
    print(f"Loaded {len(records)} DATCH_RAW records ({errors} parse errors)\n")
    return records


def hex_to_bytes(hex_str):
    return bytes.fromhex(hex_str.replace(' ', ''))


def format_ts(ms):
    return datetime.fromtimestamp(ms / 1000.0).strftime('%H:%M:%S.%f')[:-3]


def xor_bytes(a, b):
    return bytes(x ^ y for x, y in zip(a, b))


def popcount(val):
    return bin(val).count('1')


def byte_entropy(data):
    if not data:
        return 0.0
    freq = Counter(data)
    n = len(data)
    return -sum((c / n) * math.log2(c / n) for c in freq.values())


def split_sessions(records, gap_ms=5000):
    """Split records into sessions by time gap."""
    sessions = []
    cur = []
    for r in records:
        if cur and r['ts'] - cur[-1]['ts'] > gap_ms:
            sessions.append(cur)
            cur = []
        cur.append(r)
    if cur:
        sessions.append(cur)
    return sessions


def get_idle_canonical(all_bytes):
    """Find the most common (idle) payload for the top 2 families."""
    families = defaultdict(list)
    for b in all_bytes:
        sig = b[1:9].hex()
        families[sig].append(b)

    sorted_fams = sorted(families.items(), key=lambda x: -len(x[1]))
    result = {}
    for sig, members in sorted_fams[:3]:  # Top 3 families
        if len(members) < 20:  # Only consider large families as idle
            continue
        payload_counter = Counter(m.hex() for m in members)
        canonical_hex = payload_counter.most_common(1)[0][0]
        result[sig] = {
            'canonical': bytes.fromhex(canonical_hex),
            'count': len(members),
            'sig': sig,
        }
    return result


def strip_framing(b):
    """Remove framing bytes [0, 9, 30, 39] from a 40-byte timeslot."""
    payload = bytearray()
    for j in range(40):
        if j not in [0, 9, 30, 39]:
            payload.append(b[j])
    return bytes(payload)


def get_signature(b):
    """Get the 2-byte family signature (bytes 1-2)."""
    return b[1:3].hex()


def get_full_signature(b):
    """Get the 8-byte family signature (bytes 1-8)."""
    return b[1:9].hex()


def identify_data_bursts(all_bytes, records, idle_sigs):
    """Identify non-idle timeslots and group into bursts."""
    data_slots = []
    for i, b in enumerate(all_bytes):
        sig = b[1:9].hex()
        if sig not in idle_sigs:
            data_slots.append((i, b, records[i]))

    if not data_slots:
        return []

    bursts = []
    cur_burst = [data_slots[0]]
    for ds in data_slots[1:]:
        if ds[0] - cur_burst[-1][0] <= 4:  # Within 4 slots = same burst
            cur_burst.append(ds)
        else:
            bursts.append(cur_burst)
            cur_burst = [ds]
    bursts.append(cur_burst)
    return bursts


# ============================================================================
# ANALYSIS 1: EC-Variant XOR Extraction
# ============================================================================
def analyze_ec_variant_xor(all_bytes, records, idles):
    """XOR EC-variant data timeslots against EC idle to reveal user data."""
    print("=" * 80)
    print("ANALYSIS 1: EC-VARIANT XOR DATA EXTRACTION")
    print("=" * 80)
    print("XOR each data timeslot against EC idle canonical to reveal encoded data.\n")

    # Find EC idle canonical
    ec_idle = None
    for sig, info in idles.items():
        if sig.startswith('ec27'):
            ec_idle = info['canonical']
            print(f"EC idle canonical: {ec_idle.hex(' ')}")
            break

    if ec_idle is None:
        print("  No EC idle family found!\n")
        return

    # Find all EC-variant timeslots (byte[1] starts with 'ec' or 'ed' or 'ee')
    ec_variants = []
    for i, b in enumerate(all_bytes):
        first_sig_byte = b[1]
        full_sig = b[1:9].hex()
        if full_sig in {s for s in idles}:
            continue  # Skip pure idle
        # Check if this is an EC-variant (byte 1 in ec/ed/ee range)
        if first_sig_byte in range(0xEC, 0xF0):
            ec_variants.append((i, b, records[i]))

    print(f"\nEC-variant data timeslots: {len(ec_variants)}")

    if not ec_variants:
        print("  None found.\n")
        return

    # Group by burst
    bursts = []
    cur = [ec_variants[0]]
    for ev in ec_variants[1:]:
        if ev[0] - cur[-1][0] <= 4:
            cur.append(ev)
        else:
            bursts.append(cur)
            cur = [ev]
    bursts.append(cur)

    print(f"  Grouped into {len(bursts)} EC-variant bursts\n")

    for bi, burst in enumerate(bursts):
        first_idx = burst[0][0]
        last_idx = burst[-1][0]
        print(f"  --- EC-Variant Burst {bi + 1} (slots {first_idx}-{last_idx}, {len(burst)} slots) ---")

        # Track signature progression
        sig_progression = []
        xor_payloads = []

        for idx, b, rec in burst:
            ts = "TS1" if 'TS1' in rec.get('sap', '') else "TS2"
            sig2 = b[1:3].hex()
            sig_progression.append(sig2)

            # XOR against EC idle
            xor = xor_bytes(b, ec_idle)
            diff_bits = sum(popcount(x) for x in xor)

            # Strip framing from XOR result to get pure data delta
            xor_stripped = strip_framing(xor)
            xor_payloads.append(xor_stripped)

            print(f"    [{idx:4d}] {ts} sig={sig2} | {diff_bits:3d} bits diff | "
                  f"XOR stripped: {xor_stripped[:16].hex(' ')}...")

        print(f"\n    Signature progression: {' -> '.join(sig_progression)}")

        # Analyze the XOR'd payloads - what byte positions carry data?
        if len(xor_payloads) > 1:
            print(f"\n    XOR payload analysis (36 bytes each, framing removed):")

            # Which positions are non-zero across all burst XORs?
            pos_nonzero = Counter()
            for xp in xor_payloads:
                for j, v in enumerate(xp):
                    if v != 0:
                        pos_nonzero[j] += 1

            active_positions = sorted(pos_nonzero.keys())
            print(f"    Active positions (non-zero in any slot): {len(active_positions)}/36")
            print(f"    Positions: {active_positions}")

            # Concatenate XOR payloads
            concat_xor = b''.join(xor_payloads)
            nonzero_bytes = sum(1 for v in concat_xor if v != 0)
            print(f"\n    Concatenated XOR payload: {len(concat_xor)} bytes, "
                  f"{nonzero_bytes} non-zero ({100 * nonzero_bytes / len(concat_xor):.1f}%)")

            # Show the non-zero bytes as potential data stream
            data_stream = bytearray()
            for xp in xor_payloads:
                for v in xp:
                    if v != 0:
                        data_stream.append(v)

            if data_stream:
                print(f"    Non-zero data stream ({len(data_stream)} bytes):")
                for off in range(0, len(data_stream), 32):
                    chunk = data_stream[off:off + 32]
                    hex_str = chunk.hex(' ')
                    ascii_str = ''.join(chr(c) if 32 <= c < 127 else '.' for c in chunk)
                    print(f"      +{off:3d}: {hex_str}  |{ascii_str}|")

        print()

    # Also show non-EC data families
    print("\n  Non-EC data families (byte[1] NOT in ec-ef range):")
    non_ec_data = []
    idle_sigs_set = set(idles.keys())
    for i, b in enumerate(all_bytes):
        full_sig = b[1:9].hex()
        if full_sig in idle_sigs_set:
            continue
        if b[1] not in range(0xEC, 0xF0):
            non_ec_data.append((i, b, records[i]))

    if non_ec_data:
        # Group by 2-byte signature
        non_ec_fams = defaultdict(list)
        for idx, b, rec in non_ec_data:
            sig = b[1:3].hex()
            non_ec_fams[sig].append((idx, b, rec))

        for sig, entries in sorted(non_ec_fams.items(), key=lambda x: -len(x[1])):
            print(f"\n    Family {sig} ({len(entries)} slots):")
            for idx, b, rec in entries[:3]:
                ts = "TS1" if 'TS1' in rec.get('sap', '') else "TS2"
                stripped = strip_framing(b)
                print(f"      [{idx:4d}] {ts} | {stripped.hex(' ')}")
    print()


# ============================================================================
# ANALYSIS 2: Multi-Slot Burst Reassembly & Protocol Search
# ============================================================================
def analyze_burst_reassembly(all_bytes, records, idles):
    """Reassemble multi-slot bursts and search for protocol signatures."""
    print("=" * 80)
    print("ANALYSIS 2: BURST REASSEMBLY & PROTOCOL SIGNATURE SEARCH")
    print("=" * 80)
    print("Concatenate data slots within each burst and search for known protocols.\n")

    idle_sigs = set(idles.keys())
    bursts = identify_data_bursts(all_bytes, records, idle_sigs)

    print(f"Found {len(bursts)} data bursts\n")

    all_burst_payloads = []

    for bi, burst in enumerate(bursts):
        first_idx = burst[0][0]
        last_idx = burst[-1][0]
        first_ts = burst[0][2].get('ts', 0)
        last_ts = burst[-1][2].get('ts', 0)
        dur_ms = last_ts - first_ts

        # Build concatenated payload (stripped of framing)
        concat = bytearray()
        slot_details = []
        for idx, b, rec in burst:
            stripped = strip_framing(b)
            concat.extend(stripped)
            ts = "TS1" if 'TS1' in rec.get('sap', '') else "TS2"
            sig = get_signature(b)
            slot_details.append((idx, ts, sig))

        all_burst_payloads.append((bi, concat, slot_details, dur_ms))

        # Brief summary
        sigs = [s for _, _, s in slot_details]
        sig_counter = Counter(sigs)
        sig_str = ", ".join(f"{s}x{c}" for s, c in sig_counter.most_common(5))

        print(f"  Burst {bi + 1:3d}: slots [{first_idx:4d}-{last_idx:4d}] "
              f"{len(burst):3d} slots, {dur_ms:5d}ms | families: {sig_str}")

        # Search for protocol signatures in the concatenated payload
        found_sigs = []
        for name, pattern in PROTOCOL_SIGNATURES.items():
            positions = []
            for off in range(len(concat) - len(pattern) + 1):
                if concat[off:off + len(pattern)] == pattern:
                    positions.append(off)
            if positions and name not in ('null_padding', 'ff_padding'):
                found_sigs.append((name, positions))

        if found_sigs:
            for name, positions in found_sigs:
                print(f"         >>> FOUND '{name}' at offsets: {positions[:10]}")

        # Check for null padding regions
        null_runs = find_null_runs(concat, min_len=4)
        if null_runs:
            total_null = sum(l for _, l in null_runs)
            print(f"         Null runs (>=4 bytes): {len(null_runs)}, "
                  f"total {total_null} bytes ({100 * total_null / max(len(concat), 1):.0f}%)")

    # Cross-burst analysis
    print(f"\n\n{'=' * 60}")
    print("CROSS-BURST SIGNATURE ANALYSIS")
    print(f"{'=' * 60}\n")

    # Find common leading bytes across bursts
    if len(all_burst_payloads) >= 2:
        print("First 8 bytes of each burst payload:")
        for bi, concat, details, dur in all_burst_payloads:
            if len(concat) >= 8:
                head = concat[:8]
                print(f"  Burst {bi + 1:3d}: {bytes(head).hex(' ')}")

        # Check if any 2+ byte sequences are common across bursts
        print("\nCommon byte patterns across bursts:")
        all_trigrams = defaultdict(list)
        for bi, concat, _, _ in all_burst_payloads:
            seen = set()
            for off in range(len(concat) - 2):
                trigram = bytes(concat[off:off + 3])
                if trigram not in seen and trigram != b'\x00\x00\x00' and trigram != b'\xff\xff\xff':
                    seen.add(trigram)
                    all_trigrams[trigram].append(bi)

        # Find trigrams that appear in 3+ bursts
        common = [(tg, bis) for tg, bis in all_trigrams.items() if len(bis) >= 3]
        common.sort(key=lambda x: -len(x[1]))
        for tg, bis in common[:20]:
            burst_list = sorted(set(bis))
            print(f"  {tg.hex(' ')} -- in {len(burst_list)} bursts: "
                  f"{[b + 1 for b in burst_list[:10]]}")

    print()


def find_null_runs(data, min_len=4):
    """Find runs of null bytes."""
    runs = []
    i = 0
    while i < len(data):
        if data[i] == 0:
            start = i
            while i < len(data) and data[i] == 0:
                i += 1
            if i - start >= min_len:
                runs.append((start, i - start))
        else:
            i += 1
    return runs


# ============================================================================
# ANALYSIS 3: Data Family Deep Decode
# ============================================================================
def analyze_data_families(all_bytes, records, idles):
    """Deep analysis of each non-idle data family."""
    print("=" * 80)
    print("ANALYSIS 3: DATA FAMILY DEEP DECODE")
    print("=" * 80)
    print("Analyze the structure of each data family's payload content.\n")

    idle_sigs = set(idles.keys())

    # Group all non-idle timeslots by their 2-byte signature
    data_fams = defaultdict(list)
    for i, b in enumerate(all_bytes):
        full_sig = b[1:9].hex()
        if full_sig not in idle_sigs:
            sig2 = b[1:3].hex()
            data_fams[sig2].append((i, b, records[i]))

    print(f"Found {len(data_fams)} distinct data families\n")

    for sig, entries in sorted(data_fams.items(), key=lambda x: -len(x[1])):
        print(f"  Family '{sig}' -- {len(entries)} timeslots")

        # Show byte[0] (header) distribution
        byte0_vals = Counter(b[0] for _, b, _ in entries)
        print(f"    Byte[0] (header): {', '.join(f'0x{v:02X}({c})' for v, c in byte0_vals.most_common(5))}")

        # Header bits analysis
        for v, c in byte0_vals.most_common(3):
            ft = (v >> 6) & 0x3
            lower = v & 0x3F
            print(f"      0x{v:02X}: frame_type={ft} ({ft:02b}), lower6=0x{lower:02X} ({lower:06b})")

        # Show stripped payloads
        stripped_payloads = []
        for idx, b, rec in entries:
            stripped_payloads.append(strip_framing(b))

        # How many unique stripped payloads?
        unique_payloads = len(set(sp.hex() for sp in stripped_payloads))
        print(f"    Unique payloads (after stripping framing): {unique_payloads}/{len(entries)}")

        if unique_payloads == 1:
            print(f"    STATIC payload: {stripped_payloads[0].hex(' ')}")
            # Analyze the static payload
            analyze_static_payload(sig, stripped_payloads[0])
        else:
            # Show the variations
            print(f"    Varying payloads ({unique_payloads} unique):")
            shown = set()
            for sp in stripped_payloads[:5]:
                h = sp.hex()
                if h not in shown:
                    shown.add(h)
                    ascii_repr = ''.join(chr(c) if 32 <= c < 127 else '.' for c in sp)
                    print(f"      {sp.hex(' ')}")
                    print(f"      ASCII: |{ascii_repr}|")

            # XOR the variations to find which positions change
            if len(stripped_payloads) >= 2:
                base = stripped_payloads[0]
                changing_positions = set()
                for sp in stripped_payloads[1:]:
                    for j, (a, b_val) in enumerate(zip(base, sp)):
                        if a != b_val:
                            changing_positions.add(j)
                print(f"    Changing positions: {sorted(changing_positions)}")
                print(f"    Fixed positions: {sorted(set(range(36)) - changing_positions)}")

        # Timeslot distribution
        ts1_ct = sum(1 for _, _, r in entries if 'TS1' in r.get('sap', ''))
        ts2_ct = sum(1 for _, _, r in entries if 'TS2' in r.get('sap', ''))
        print(f"    TS distribution: TS1={ts1_ct}, TS2={ts2_ct}")
        print()


def analyze_static_payload(sig, payload):
    """Analyze a static (repeating) data family payload for protocol clues."""
    # Check for known patterns
    zero_count = sum(1 for b in payload if b == 0)
    ff_count = sum(1 for b in payload if b == 0xFF)

    if zero_count > 20:
        print(f"    >>> SPARSE payload ({zero_count}/36 zero bytes) -- likely teardown/null signaling")
    elif ff_count > 10:
        print(f"    >>> HIGH-FF payload ({ff_count}/36 0xFF bytes) -- likely fill/padding")

    # Look for ASCII content
    ascii_chars = sum(1 for b in payload if 32 <= b < 127)
    if ascii_chars > 10:
        ascii_str = ''.join(chr(c) if 32 <= c < 127 else '.' for c in payload)
        print(f"    >>> Contains ASCII ({ascii_chars}/36): |{ascii_str}|")

    # Check nibble distribution
    hi_nibbles = Counter((b >> 4) & 0xF for b in payload)
    lo_nibbles = Counter(b & 0xF for b in payload)

    # Check for potential radio IDs (24-bit P25 radio IDs)
    # P25 subscriber IDs are typically in range 0x000001 - 0xFFFFFE
    for i in range(len(payload) - 2):
        val = (payload[i] << 16) | (payload[i + 1] << 8) | payload[i + 2]
        if 0x000100 <= val <= 0xFFFF00:
            # Could be a radio ID -- check if it's plausible
            # Most real radio IDs are in specific ranges
            if val < 0x100000:  # Reasonable range
                pass  # Don't print every possible match


# ============================================================================
# ANALYSIS 4: Byte[0] Opcode/Header Deep Analysis
# ============================================================================
def analyze_byte0_opcodes(all_bytes, records, idles):
    """Deep analysis of byte[0] as potential opcode/message type field."""
    print("=" * 80)
    print("ANALYSIS 4: BYTE[0] OPCODE / MESSAGE TYPE ANALYSIS")
    print("=" * 80)
    print("Investigate byte[0] as a protocol opcode field.\n")

    idle_sigs = set(idles.keys())

    # Separate idle and data
    idle_byte0 = Counter()
    data_byte0 = Counter()
    data_byte0_full = defaultdict(list)

    for i, b in enumerate(all_bytes):
        full_sig = b[1:9].hex()
        if full_sig in idle_sigs:
            idle_byte0[b[0]] += 1
        else:
            data_byte0[b[0]] += 1
            data_byte0_full[b[0]].append((i, b, records[i]))

    print("  Idle frame byte[0] distribution:")
    for v, c in idle_byte0.most_common(20):
        ft = (v >> 6) & 0x3
        lower = v & 0x3F
        print(f"    0x{v:02X} ({v:08b}): {c:5d}  ft={ft} lower=0x{lower:02X}")

    print(f"\n  Data frame byte[0] distribution:")
    for v, c in data_byte0.most_common(30):
        ft = (v >> 6) & 0x3
        lower = v & 0x3F
        print(f"    0x{v:02X} ({v:08b}): {c:5d}  ft={ft} lower=0x{lower:02X}")

    # Analyze byte[0] bits[5:0] as potential opcode
    print(f"\n  Byte[0] lower 6 bits as opcode (data frames only):")
    opcodes = Counter()
    for v, c in data_byte0.items():
        opcodes[v & 0x3F] += c

    for opc, c in opcodes.most_common(20):
        # Show which frame types use this opcode
        ft_for_opc = Counter()
        for v, cnt in data_byte0.items():
            if (v & 0x3F) == opc:
                ft_for_opc[(v >> 6) & 0x3] += cnt
        ft_str = ", ".join(f"ft{k}={v}" for k, v in ft_for_opc.most_common(4))
        print(f"    opcode 0x{opc:02X} ({opc:06b}): {c:5d} slots | {ft_str}")

    # Cross-reference byte[0] with byte[1] (first signature byte)
    print(f"\n  Byte[0] vs Byte[1] cross-reference (data frames):")
    b0_b1 = Counter()
    for v, entries in data_byte0_full.items():
        for _, b, _ in entries:
            b0_b1[(b[0], b[1])] += 1

    for (b0, b1), c in b0_b1.most_common(30):
        ft = (b0 >> 6) & 0x3
        opc = b0 & 0x3F
        print(f"    byte0=0x{b0:02X}(ft={ft},opc=0x{opc:02X}) byte1=0x{b1:02X}: {c:5d}")

    print()


# ============================================================================
# ANALYSIS 5: Nibble-Level Pattern Analysis
# ============================================================================
def analyze_nibble_patterns(all_bytes, records, idles):
    """Analyze data at the nibble level for protocol framing clues."""
    print("=" * 80)
    print("ANALYSIS 5: NIBBLE-LEVEL PATTERN ANALYSIS")
    print("=" * 80)
    print("Look for protocol framing at the nibble (4-bit) level.\n")

    idle_sigs = set(idles.keys())

    # Collect data-only timeslots
    data_bytes = []
    for i, b in enumerate(all_bytes):
        if b[1:9].hex() not in idle_sigs:
            data_bytes.append(b)

    if not data_bytes:
        print("  No data timeslots found.\n")
        return

    print(f"  Analyzing {len(data_bytes)} data timeslots\n")

    # For each byte position, show nibble distribution
    print("  Nibble distribution by byte position (data frames only):")
    print(f"  {'Pos':>3s}  {'HiNib Top3':>30s}  {'LoNib Top3':>30s}")

    for pos in range(40):
        hi = Counter((b[pos] >> 4) & 0xF for b in data_bytes)
        lo = Counter(b[pos] & 0xF for b in data_bytes)
        hi_str = " ".join(f"{v:X}:{c}" for v, c in hi.most_common(3))
        lo_str = " ".join(f"{v:X}:{c}" for v, c in lo.most_common(3))
        marker = ""
        if len(hi) <= 2:
            marker += " [HI_FIXED]"
        if len(lo) <= 2:
            marker += " [LO_FIXED]"
        print(f"  [{pos:2d}]  {hi_str:>30s}  {lo_str:>30s}{marker}")

    # Look for nibble-aligned fields (common in Motorola protocols)
    print(f"\n  Potential nibble-aligned fields (data frames):")
    for pos in range(39):
        # Check if consecutive bytes form a stable 16-bit field
        vals_16 = Counter((b[pos] << 8) | b[pos + 1] for b in data_bytes)
        if len(vals_16) <= 5 and len(data_bytes) > 10:
            top = vals_16.most_common(5)
            if top[0][1] > len(data_bytes) * 0.3:  # >30% of data frames
                print(f"    Bytes [{pos}:{pos + 1}] -- {len(vals_16)} unique values: "
                      f"{', '.join(f'0x{v:04X}({c})' for v, c in top)}")

    print()


# ============================================================================
# ANALYSIS 6: EC-Variant Signature Byte Decoding
# ============================================================================
def analyze_ec_signature_progression(all_bytes, records, idles):
    """Decode the EC-variant signature byte progression as sequence/type info."""
    print("=" * 80)
    print("ANALYSIS 6: EC-VARIANT SIGNATURE BYTE PROGRESSION")
    print("=" * 80)
    print("Decode how bytes[1-8] change in EC-variant data frames.\n")

    ec_idle_sig = None
    ec_idle_canonical = None
    for sig, info in idles.items():
        if sig.startswith('ec27'):
            ec_idle_sig = sig
            ec_idle_canonical = info['canonical']
            break

    if ec_idle_canonical is None:
        print("  No EC idle found.\n")
        return

    idle_sigs = set(idles.keys())

    # Find EC-variant frames
    ec_variants = []
    for i, b in enumerate(all_bytes):
        if b[1:9].hex() in idle_sigs:
            continue
        if b[1] in range(0xEC, 0xF0):  # ec, ed, ee, ef
            ec_variants.append((i, b, records[i]))

    if not ec_variants:
        print("  No EC-variant frames found.\n")
        return

    print(f"  Found {len(ec_variants)} EC-variant frames\n")

    # Analyze bytes 1-8 as a structured header
    print("  Byte-by-byte analysis of signature region (bytes 1-8):")
    print(f"  EC idle signature: {ec_idle_canonical[1:9].hex(' ')}\n")

    for pos in range(1, 9):
        idle_val = ec_idle_canonical[pos]
        vals = Counter(b[pos] for _, b, _ in ec_variants)
        xor_vals = Counter(b[pos] ^ idle_val for _, b, _ in ec_variants)

        print(f"  Byte [{pos}]: idle=0x{idle_val:02X}")
        print(f"    Values: {', '.join(f'0x{v:02X}({c})' for v, c in vals.most_common(10))}")
        print(f"    XOR vs idle: {', '.join(f'0x{v:02X}({c})' for v, c in xor_vals.most_common(10))}")

        # Check if XOR values follow a pattern
        xor_unique = sorted(set(v for v, _ in xor_vals.most_common()))
        if len(xor_unique) > 1:
            # Check if they're powers of 2 (single bit changes)
            single_bit = [v for v in xor_unique if v != 0 and popcount(v) == 1]
            if len(single_bit) == len([v for v in xor_unique if v != 0]):
                print(f"    >>> Single-bit changes only! Bit positions: "
                      f"{[v.bit_length() - 1 for v in single_bit]}")

    # Group by burst and show progression
    print(f"\n  EC-variant progression within bursts:")

    bursts = []
    cur = [ec_variants[0]]
    for ev in ec_variants[1:]:
        if ev[0] - cur[-1][0] <= 4:
            cur.append(ev)
        else:
            bursts.append(cur)
            cur = [ev]
    bursts.append(cur)

    for bi, burst in enumerate(bursts[:10]):  # Show first 10
        print(f"\n    Burst {bi + 1} ({len(burst)} slots):")
        for idx, b, rec in burst:
            sig_xor = xor_bytes(b[1:9], ec_idle_canonical[1:9])
            ts = "TS1" if 'TS1' in rec.get('sap', '') else "TS2"
            print(f"      [{idx:4d}] {ts} sig={b[1:9].hex(' ')} XOR={sig_xor.hex(' ')}")

    print()


# ============================================================================
# ANALYSIS 7: Cross-Family Correlation
# ============================================================================
def analyze_cross_family_correlation(all_bytes, records, idles):
    """Look for correlations between different data families in the same burst."""
    print("=" * 80)
    print("ANALYSIS 7: CROSS-FAMILY BURST CORRELATION")
    print("=" * 80)
    print("Examine how different data families co-occur within bursts.\n")

    idle_sigs = set(idles.keys())
    bursts = identify_data_bursts(all_bytes, records, idle_sigs)

    if not bursts:
        print("  No data bursts found.\n")
        return

    # Build a family co-occurrence matrix
    family_cooccurrence = Counter()
    family_ordering = defaultdict(list)

    for bi, burst in enumerate(bursts):
        fams_in_burst = []
        for idx, b, rec in burst:
            sig2 = get_signature(b)
            fams_in_burst.append(sig2)

        unique_fams = set(fams_in_burst)
        for f1 in unique_fams:
            for f2 in unique_fams:
                if f1 < f2:
                    family_cooccurrence[(f1, f2)] += 1

        # Track ordering patterns
        if len(set(fams_in_burst)) > 1:
            family_ordering[tuple(fams_in_burst)].append(bi)

    print(f"  Family co-occurrence (families that appear in the same burst):")
    for (f1, f2), c in family_cooccurrence.most_common(20):
        print(f"    {f1} + {f2}: {c} bursts")

    print(f"\n  Unique family orderings within bursts:")
    for pattern, bis in sorted(family_ordering.items(), key=lambda x: -len(x[1]))[:15]:
        # Compress pattern display
        compressed = []
        for f in pattern:
            if not compressed or compressed[-1] != f:
                compressed.append(f)
        print(f"    Pattern: {' -> '.join(compressed)} (in {len(bis)} bursts)")

    # Burst composition statistics
    print(f"\n  Burst composition:")
    burst_types = Counter()
    for bi, burst in enumerate(bursts):
        fams = tuple(sorted(set(get_signature(b) for _, b, _ in burst)))
        burst_types[fams] += 1

    for fams, c in burst_types.most_common(15):
        print(f"    {'+'.join(fams)}: {c} bursts")

    # Single-family vs multi-family bursts
    single = sum(1 for b in bursts if len(set(get_signature(bx) for _, bx, _ in b)) == 1)
    multi = len(bursts) - single
    print(f"\n  Single-family bursts: {single}")
    print(f"  Multi-family bursts: {multi}")

    print()


# ============================================================================
# ANALYSIS 8: Raw Payload Byte Frequency (Data vs Idle)
# ============================================================================
def analyze_byte_frequency(all_bytes, records, idles):
    """Compare byte frequency distributions between idle and data frames."""
    print("=" * 80)
    print("ANALYSIS 8: BYTE FREQUENCY ANALYSIS (Data vs Idle)")
    print("=" * 80)
    print("Compare byte value distributions to identify protocol characteristics.\n")

    idle_sigs = set(idles.keys())

    idle_bytes_flat = bytearray()
    data_bytes_flat = bytearray()

    for i, b in enumerate(all_bytes):
        stripped = strip_framing(b)
        if b[1:9].hex() in idle_sigs:
            idle_bytes_flat.extend(stripped)
        else:
            data_bytes_flat.extend(stripped)

    print(f"  Idle payload bytes: {len(idle_bytes_flat)}")
    print(f"  Data payload bytes: {len(data_bytes_flat)}")

    if not data_bytes_flat:
        print("  No data bytes to analyze.\n")
        return

    # Byte value distribution comparison
    idle_freq = Counter(idle_bytes_flat)
    data_freq = Counter(data_bytes_flat)

    # Find bytes that are significantly more common in data than idle
    print(f"\n  Bytes significantly more common in DATA than idle:")
    data_total = len(data_bytes_flat)
    idle_total = len(idle_bytes_flat)

    data_enriched = []
    for byte_val in range(256):
        data_pct = data_freq.get(byte_val, 0) / max(data_total, 1)
        idle_pct = idle_freq.get(byte_val, 0) / max(idle_total, 1)
        if data_pct > idle_pct * 2 and data_freq.get(byte_val, 0) >= 5:
            data_enriched.append((byte_val, data_freq.get(byte_val, 0), data_pct, idle_pct))

    data_enriched.sort(key=lambda x: -x[2])
    for val, count, dpct, ipct in data_enriched[:20]:
        ascii_char = chr(val) if 32 <= val < 127 else '.'
        ratio = dpct / max(ipct, 0.0001)
        print(f"    0x{val:02X} ('{ascii_char}'): data={count}({100 * dpct:.2f}%) "
              f"idle={100 * ipct:.2f}% ratio={ratio:.1f}x")

    # Entropy comparison
    data_ent = byte_entropy(data_bytes_flat)
    idle_ent = byte_entropy(idle_bytes_flat)
    print(f"\n  Overall entropy: data={data_ent:.3f}, idle={idle_ent:.3f}")

    # Null byte analysis
    data_nulls = data_freq.get(0, 0)
    idle_nulls = idle_freq.get(0, 0)
    print(f"  Null bytes: data={data_nulls}({100 * data_nulls / data_total:.1f}%), "
          f"idle={idle_nulls}({100 * idle_nulls / idle_total:.1f}%)")

    print()


# ============================================================================
# ANALYSIS 9: Motorola ASTRO 25 Protocol Identification
# ============================================================================
def analyze_motorola_protocol(all_bytes, records, idles):
    """Attempt to identify Motorola ASTRO 25 protocol elements."""
    print("=" * 80)
    print("ANALYSIS 9: MOTOROLA ASTRO 25 PROTOCOL IDENTIFICATION")
    print("=" * 80)
    print("Search for Motorola-specific protocol structures.\n")

    idle_sigs = set(idles.keys())

    # Get EC idle canonical
    ec_idle = None
    for sig, info in idles.items():
        if sig.startswith('ec27'):
            ec_idle = info['canonical']
            break

    # Collect all data timeslots
    data_slots = []
    for i, b in enumerate(all_bytes):
        if b[1:9].hex() not in idle_sigs:
            data_slots.append((i, b, records[i]))

    if not data_slots:
        print("  No data slots.\n")
        return

    print(f"  Total data slots: {len(data_slots)}\n")

    # Analysis A: Check if byte[0] encodes a Motorola message type
    print("  A) Byte[0] as Motorola message type:")
    print("     Motorola TDMA data uses byte[0] for:")
    print("     - bits[7:6]: Frame type (00=data, 01=control, 10=idle, 11=reserved)")
    print("     - bits[5:4]: Priority/sequence")
    print("     - bits[3:0]: Sub-type/opcode\n")

    b0_analysis = defaultdict(list)
    for idx, b, rec in data_slots:
        ft = (b[0] >> 6) & 0x3
        priority = (b[0] >> 4) & 0x3
        subtype = b[0] & 0xF
        b0_analysis[(ft, priority, subtype)].append((idx, b))

    for (ft, pri, sub), entries in sorted(b0_analysis.items()):
        sig_set = set(get_signature(b) for _, b in entries)
        print(f"    ft={ft} pri={pri} sub=0x{sub:X}: {len(entries)} slots, "
              f"families={sig_set}")

    # Analysis B: Look for PDU fragment indicators
    print(f"\n  B) PDU Fragment indicators:")
    print("     In Motorola DATCH, multi-slot data uses fragment indicators:")
    print("     - First fragment: specific bit pattern in header")
    print("     - Continuation: sequence number increments")
    print("     - Last fragment: end-of-PDU marker\n")

    # Check if byte[0] lower bits increment within bursts
    bursts = identify_data_bursts(all_bytes, records, idle_sigs)
    for bi, burst in enumerate(bursts[:10]):
        if len(burst) >= 3:
            b0_seq = [b[0] for _, b, _ in burst]
            # Check lower nibble for sequence
            lower_seq = [v & 0xF for v in b0_seq]
            # Check if incrementing
            is_inc = all(lower_seq[j] == (lower_seq[j - 1] + 1) % 16
                         for j in range(1, len(lower_seq)))
            # Check bits[5:4] for sequence
            mid_seq = [(v >> 4) & 0x3 for v in b0_seq]
            is_mid_inc = all(mid_seq[j] == (mid_seq[j - 1] + 1) % 4
                             for j in range(1, len(mid_seq)))

            if is_inc or is_mid_inc:
                which = "lower nibble" if is_inc else "bits[5:4]"
                print(f"    Burst {bi + 1}: INCREMENTING {which}! "
                      f"byte[0]={[f'0x{v:02X}' for v in b0_seq]}")
            else:
                print(f"    Burst {bi + 1}: byte[0]={[f'0x{v:02X}' for v in b0_seq[:8]]}")

    # Analysis C: Check for Motorola-specific bit patterns
    print(f"\n  C) Motorola-specific bit pattern search:")

    # In Motorola systems, data often has:
    # - Logical Link ID (LLID) at known positions
    # - Network Access Code (NAC) embedding
    # - Manufacturer-specific opcodes

    # Look for repeated 3-byte sequences that could be radio IDs
    print("     Searching for repeated 3-byte values (potential radio IDs):")
    three_byte_counter = Counter()
    for idx, b, _ in data_slots:
        stripped = strip_framing(b)
        for i in range(len(stripped) - 2):
            val = (stripped[i] << 16) | (stripped[i + 1] << 8) | stripped[i + 2]
            if 0x000100 <= val <= 0xFFFFFE:
                three_byte_counter[(i, val)] += 1

    # Find 3-byte values at consistent positions appearing multiple times
    pos_vals = defaultdict(Counter)
    for (pos, val), count in three_byte_counter.items():
        if count >= 3:
            pos_vals[pos][val] += count

    for pos in sorted(pos_vals.keys()):
        top_vals = pos_vals[pos].most_common(5)
        if top_vals[0][1] >= 5:
            vals_str = ", ".join(f"0x{v:06X}({c})" for v, c in top_vals)
            print(f"      Position [{pos}]: {vals_str}")

    # Analysis D: Look for length fields
    print(f"\n  D) Potential length/size fields:")
    for pos in range(36):
        vals = Counter(strip_framing(b)[pos] for _, b, _ in data_slots)
        # A length field would have a distribution concentrated on small values
        small_val_pct = sum(c for v, c in vals.items() if v < 40) / len(data_slots)
        if small_val_pct > 0.5 and len(vals) > 3:
            top = vals.most_common(5)
            print(f"    Stripped position [{pos}]: {small_val_pct:.0%} are <40 -- "
                  f"top: {', '.join(f'{v}({c})' for v, c in top)}")

    print()


# ============================================================================
# Main
# ============================================================================
def main():
    parser = argparse.ArgumentParser(
        description="DATCH Motorola Protocol Deep Analysis"
    )
    parser.add_argument('jsonl_file', help='Path to JSONL corpus file')
    parser.add_argument('--session', type=int, default=None,
                        help='Analyze specific session number (1-based)')
    parser.add_argument('--ec-xor', action='store_true',
                        help='EC-variant XOR data extraction')
    parser.add_argument('--reassemble', action='store_true',
                        help='Burst reassembly and protocol search')
    parser.add_argument('--families', action='store_true',
                        help='Data family deep decode')
    parser.add_argument('--opcodes', action='store_true',
                        help='Byte[0] opcode analysis')
    parser.add_argument('--nibbles', action='store_true',
                        help='Nibble-level pattern analysis')
    parser.add_argument('--ec-sig', action='store_true',
                        help='EC-variant signature progression')
    parser.add_argument('--correlation', action='store_true',
                        help='Cross-family burst correlation')
    parser.add_argument('--frequency', action='store_true',
                        help='Byte frequency analysis')
    parser.add_argument('--motorola', action='store_true',
                        help='Motorola ASTRO 25 protocol identification')
    parser.add_argument('--all', action='store_true',
                        help='Run all analyses')

    args = parser.parse_args()

    if args.all:
        args.ec_xor = args.reassemble = args.families = args.opcodes = True
        args.nibbles = args.ec_sig = args.correlation = args.frequency = True
        args.motorola = True

    if not any([args.ec_xor, args.reassemble, args.families, args.opcodes,
                args.nibbles, args.ec_sig, args.correlation, args.frequency,
                args.motorola]):
        # Default: run the most important analyses
        args.ec_xor = args.reassemble = args.families = args.motorola = True

    records = load_datch_records(args.jsonl_file)
    if not records:
        print("No DATCH_RAW records found.")
        return

    sessions = split_sessions(records)
    print(f"Found {len(sessions)} sessions:\n")
    for i, s in enumerate(sessions):
        freq = s[0].get('freq', 0)
        channel = s[0].get('channel', '?')
        dur = (s[-1]['ts'] - s[0]['ts']) / 1000
        print(f"  Session {i + 1}: {freq / 1e6:.4f} MHz (ch {channel}), "
              f"{len(s)} slots, {dur:.1f}s")
    print()

    # Select sessions to analyze
    if args.session is not None:
        if 1 <= args.session <= len(sessions):
            selected = [sessions[args.session - 1]]
            print(f"Analyzing session {args.session} only.\n")
        else:
            print(f"Invalid session number. Available: 1-{len(sessions)}")
            return
    else:
        selected = sessions

    # Analyze each selected session
    for si, session in enumerate(selected):
        freq = session[0].get('freq', 0)
        print(f"\n{'#' * 80}")
        print(f"# SESSION {si + 1}: {freq / 1e6:.4f} MHz, {len(session)} slots")
        print(f"{'#' * 80}\n")

        all_bytes = [hex_to_bytes(r['hex']) for r in session]
        idles = get_idle_canonical(all_bytes)

        print(f"Idle families identified:")
        for sig, info in idles.items():
            print(f"  {sig[:16]}...: {info['count']} slots")
        print()

        if args.ec_xor:
            analyze_ec_variant_xor(all_bytes, session, idles)
        if args.reassemble:
            analyze_burst_reassembly(all_bytes, session, idles)
        if args.families:
            analyze_data_families(all_bytes, session, idles)
        if args.opcodes:
            analyze_byte0_opcodes(all_bytes, session, idles)
        if args.nibbles:
            analyze_nibble_patterns(all_bytes, session, idles)
        if args.ec_sig:
            analyze_ec_signature_progression(all_bytes, session, idles)
        if args.correlation:
            analyze_cross_family_correlation(all_bytes, session, idles)
        if args.frequency:
            analyze_byte_frequency(all_bytes, session, idles)
        if args.motorola:
            analyze_motorola_protocol(all_bytes, session, idles)


if __name__ == '__main__':
    main()
