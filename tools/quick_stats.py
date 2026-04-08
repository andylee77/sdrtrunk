#!/usr/bin/env python3
"""Quick statistical overview of capture files."""
import json, sys, os, io
from collections import Counter

# Force UTF-8 output on Windows
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

files = [
    'logs/p25_data_Jacksonville_City_-_First_Coast_Radio_20260325.jsonl',
    'logs/p25_data_Clay_County_20260325.jsonl',
]

for fname in files:
    if not os.path.exists(fname):
        continue
    print(f'\n{"="*60}')
    print(f'  {os.path.basename(fname)}')
    print(f'{"="*60}')
    types = Counter()
    protos = Counter()
    zero_len = 0
    total = 0
    has_ip = 0
    saps = Counter()
    classes = Counter()
    hex_nonzero = 0
    all_zero_hex = 0
    
    with open(fname, 'r', encoding='utf-8') as f:
        for line in f:
            try:
                r = json.loads(line)
                total += 1
                types[r.get('type','')] += 1
                protos[r.get('proto','')] += 1
                saps[r.get('sap','')] += 1
                classes[r.get('class','')] += 1
                ln = r.get('len', 0)
                hx = r.get('hex', '')
                if ln == 0:
                    zero_len += 1
                # Check if hex is all zeros
                if hx and all(c in '0 ' for c in hx):
                    all_zero_hex += 1
                if hx and not all(c in '0 ' for c in hx):
                    hex_nonzero += 1
                d = r.get('details','')
                if 'IPV4' in d or 'UDP' in d or 'IP:' in d or 'SNDCP' in d:
                    has_ip += 1
            except:
                pass
    
    print(f'Total records: {total:,}')
    print(f'Zero-length payload: {zero_len:,} ({100*zero_len/max(total,1):.1f}%)')
    print(f'All-zero hex: {all_zero_hex:,}')
    print(f'Non-zero hex: {hex_nonzero:,}')
    print(f'IP/SNDCP-related: {has_ip:,}')
    
    print(f'\nBy TYPE:')
    for k, v in types.most_common(20):
        print(f'  {k:25s} {v:>8,}  ({100*v/total:.1f}%)')
    
    print(f'\nBy PROTOCOL:')
    for k, v in protos.most_common(20):
        print(f'  {k:25s} {v:>8,}  ({100*v/total:.1f}%)')
    
    print(f'\nTop SAP/OPCODE:')
    for k, v in saps.most_common(30):
        print(f'  {k:45s} {v:>8,}')
    
    print(f'\nTop CLASSES:')
    for k, v in classes.most_common(20):
        print(f'  {k:45s} {v:>8,}')
