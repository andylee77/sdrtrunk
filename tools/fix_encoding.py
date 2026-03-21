"""Fix non-UTF-8 bytes in Java source files after patching."""
import sys
import os

def fix_file(path):
    if not os.path.exists(path):
        print(f"File not found: {path}")
        return False
    with open(path, 'rb') as f:
        data = f.read()
    count = data.count(b'\x97')
    if count == 0:
        print(f"{path}: clean (no bad bytes)")
        return True
    print(f"{path}: found {count} bad 0x97 bytes, replacing with '--'")
    data = data.replace(b'\x97', b'--')
    with open(path, 'wb') as f:
        f.write(data)
    # Verify
    with open(path, 'rb') as f:
        verify = f.read()
    remaining = verify.count(b'\x97')
    print(f"{path}: after fix, {remaining} bad bytes remain")
    return remaining == 0

files = [
    'src/main/java/io/github/dsheirer/module/decode/p25/P25TrafficChannelManager.java',
    'src/main/java/io/github/dsheirer/module/decode/p25/session/P25CallSessionManager.java',
]

all_ok = True
for f in files:
    if not fix_file(f):
        all_ok = False

sys.exit(0 if all_ok else 1)
