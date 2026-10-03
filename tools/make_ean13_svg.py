#!/usr/bin/env python3
"""Render one valid EAN-13 as a simple offline SVG acceptance target."""
from pathlib import Path
import sys

L = ["0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011"]
G = ["0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111"]
R = ["1110010", "1100110", "1101100", "1000010", "1011100", "1001110", "1010000", "1000100", "1001000", "1110100"]
PARITY = ["LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"]

value = sys.argv[1] if len(sys.argv) > 1 else "7340002119380"
if len(value) != 13 or not value.isascii() or not value.isdigit(): raise SystemExit("EAN-13 must contain 13 ASCII digits")
check = (10 - sum(int(c) * (1 if i % 2 == 0 else 3) for i, c in enumerate(value[:12])) % 10) % 10
if check != int(value[-1]): raise SystemExit("Invalid EAN-13 check digit")
left = "".join((L if kind == "L" else G)[int(digit)] for kind, digit in zip(PARITY[int(value[0])], value[1:7]))
bits = "101" + left + "01010" + "".join(R[int(digit)] for digit in value[7:]) + "101"
module, quiet, height = 7, 12, 420
rects = "".join(f'<rect x="{(quiet+i)*module}" y="30" width="{module}" height="{height}"/>' for i, bit in enumerate(bits) if bit == "1")
width = (len(bits) + quiet * 2) * module
svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="520" viewBox="0 0 {width} 520"><rect width="100%" height="100%" fill="white"/><g fill="black">{rects}</g><text x="{width/2}" y="495" text-anchor="middle" font-family="sans-serif" font-size="42" letter-spacing="7">{value}</text></svg>\n'
Path(sys.argv[2] if len(sys.argv) > 2 else "work/gtin/camera-acceptance-ean13.svg").write_text(svg)
