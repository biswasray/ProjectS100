#!/usr/bin/env python3
"""fbdump.py - render a raw framebuffer dump (fake fb file or `cat /dev/graphics/fb0`)
as a PNG, stdlib only.

    fbdump.py <raw> <out.png> [WxHxBPP] [--bgrx]

Default geometry is 240x320x16 (RGB565). For 32 bpp the default byte order is
B,G,R,X (msm_fb / XRGB8888 little-endian); --rgbx selects R,G,B,X.
"""
import struct
import sys
import zlib


def png(path, w, h, rows):
    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    raw = b''.join(b'\x00' + r for r in rows)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)))
        f.write(chunk(b'IDAT', zlib.compress(raw, 9)))
        f.write(chunk(b'IEND', b''))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    flags = [a for a in sys.argv[1:] if a.startswith('--')]
    if len(args) < 2:
        sys.exit(__doc__)
    src, dst = args[0], args[1]
    w, h, bpp = (240, 320, 16)
    if len(args) > 2:
        w, h, bpp = (int(x) for x in args[2].lower().split('x'))
    data = open(src, 'rb').read()
    stride = w * bpp // 8
    if len(data) < stride * h:
        sys.exit(f'{src}: {len(data)} bytes, need {stride * h}')
    rows = []
    for y in range(h):
        line = data[y * stride:(y + 1) * stride]
        out = bytearray()
        if bpp == 16:
            for (p,) in struct.iter_unpack('<H', line):
                r = (p >> 11) & 0x1f
                g = (p >> 5) & 0x3f
                b = p & 0x1f
                out += bytes(((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)))
        else:
            for i in range(0, len(line), 4):
                b0, b1, b2 = line[i], line[i + 1], line[i + 2]
                out += bytes((b0, b1, b2)) if '--rgbx' in flags else bytes((b2, b1, b0))
        rows.append(bytes(out))
    png(dst, w, h, rows)
    print(f'{dst}: {w}x{h} from {src}')


if __name__ == '__main__':
    main()
