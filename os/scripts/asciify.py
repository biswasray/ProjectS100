#!/usr/bin/env python3
"""asciify.py - rewrite non-ASCII characters in the os/port Java sources as
\\uXXXX escapes (the MIDP build compiles with the platform default
encoding and check_port.sh uses -encoding ascii).

    python os/scripts/asciify.py            all of os/port/**/*.java
    python os/scripts/asciify.py file.java  just these files
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = os.path.join(os.path.dirname(HERE), "port")


def asciify(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    out = []
    changed = 0
    for ch in text:
        if ord(ch) < 128:
            out.append(ch)
        else:
            out.append("\\u%04x" % ord(ch))
            changed += 1
    if changed:
        with open(path, "w", encoding="ascii", newline="") as f:
            f.write("".join(out))
    return changed


def main():
    files = sys.argv[1:]
    if not files:
        for root, _, names in os.walk(PORT):
            files += [os.path.join(root, n) for n in names if n.endswith(".java")]
    for p in files:
        n = asciify(p)
        if n:
            print("%s: %d characters escaped" % (os.path.relpath(p), n))


if __name__ == "__main__":
    main()
