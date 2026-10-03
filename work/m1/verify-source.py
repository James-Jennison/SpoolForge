"""Verify the immutable reviewed manifest, then every listed source file."""
import hashlib
from pathlib import Path
import subprocess
import sys
manifest = Path('work/m1/source-SHA256SUMS')
if hashlib.sha256(manifest.read_bytes()).hexdigest() != sys.argv[1]:
    raise SystemExit('Reviewed source manifest changed')
subprocess.run(['sha256sum', '--quiet', '-c', str(manifest)], check=True)
print('Exact reviewed source manifest and all source hashes match')
