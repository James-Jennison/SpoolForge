import hashlib,subprocess,sys
from pathlib import Path
p=Path('work/gtin/source-SHA256SUMS')
assert hashlib.sha256(p.read_bytes()).hexdigest()==sys.argv[1], 'Source manifest changed'
subprocess.run(['sha256sum','--quiet','-c',str(p)],check=True)
print('Exact GTIN source and bundled asset match')
