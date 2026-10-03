#!/usr/bin/env python3
import json
from pathlib import Path
p=Path('work/portable-identity/device-acceptance.json')
d=json.loads(p.read_text())
assert d == {
  'model':'motorola razr 2023', 'schema':'filamajig.spool', 'version':1,
  'payload_bytes':575, 'profile_id_distinct_from_spool_id':True,
  'qr_decode':'PASS', 'unicode_round_trip':'PASS', 'network_required':False,
}
print('portable identity device evidence: PASS')
