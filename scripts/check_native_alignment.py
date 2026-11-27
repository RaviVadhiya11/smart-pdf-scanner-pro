#!/usr/bin/env python3
"""Check ELF LOAD alignment in shipped 64-bit APK libraries (16 KiB)."""
import struct
import sys
import zipfile

def alignment(data):
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] not in (1, 2):
        raise ValueError("Not a 64-bit ELF")
    endian = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(endian + "Q", data, 32)[0]
    entsize, count = struct.unpack_from(endian + "HH", data, 54)
    if entsize < 56 or phoff + entsize * count > len(data):
        raise ValueError("Invalid program header table")
    segments = []
    for i in range(count):
        kind, flags, offset, vaddr, paddr, filesz, memsz, align = struct.unpack_from(endian + "IIQQQQQQ", data, phoff + entsize * i)
        if kind == 1:
            segments.append((align, (vaddr - offset) % 16384 == 0))
    if not segments:
        raise ValueError("No LOAD segments")
    return min(x[0] for x in segments), all(x[1] for x in segments)

def main():
    failed = False
    checked = 0
    with zipfile.ZipFile(sys.argv[1]) as apk:
        for name in apk.namelist():
            if not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not name.endswith(".so"):
                continue
            checked += 1
            try:
                align, congruent = alignment(apk.read(name))
                ok = align >= 16384 and congruent
                print(f"{'PASS' if ok else 'FAIL'} {name}: LOAD alignment {align}, congruent={congruent}")
                failed |= not ok
            except (ValueError, struct.error) as e:
                print(f"FAIL {name}: {e}")
                failed = True
    print(f"Checked {checked} 64-bit native libraries. Result: {'FAIL' if failed else 'PASS'}")
    return int(failed)

if __name__ == "__main__":
    sys.exit(main())
