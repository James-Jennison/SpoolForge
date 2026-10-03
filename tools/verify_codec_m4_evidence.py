#!/usr/bin/env python3
import json
from pathlib import Path

d=json.loads(Path('work/codec-m4/device-acceptance.json').read_text())
assert d['model']=='motorola razr 2023'
assert d['codec_id']=='openspool-paxx-u1-1.0'
assert d['firmware_target']=='v1.5.2-paxx12-21'
assert 0 < d['payload_bytes'] < d['ndef_message_bytes'] <= d['ntag215_capacity']==492
assert d['semantic_round_trip']=='PASS'
assert d['physical_write_required'] is True

p=json.loads(Path('work/codec-m4/physical-acceptance.json').read_text())
assert p['tag']['type']=='NTAG215'
assert p['tag']['uid_recorded'] is False
assert p['tag']['capacity_bytes']==492
assert p['codec']['id']=='openspool-paxx-u1-1.0'
assert p['codec']['target']=='Snapmaker U1 v1.5.2-paxx12-21'
assert p['phone_acceptance']['fresh_readback']=='PASS'
assert p['phone_acceptance']['write_result']=='Write verified'
assert p['phone_acceptance']['single_tag_completion']=='Completed with one verified tag'
assert p['printer_acceptance']['state']=='PASS'
assert p['printer_acceptance']['firmware']=='v1.5.2-paxx12-21'
assert p['printer_acceptance']['mode']=='OpenRFID'
assert p['printer_acceptance']['bay']==1
assert p['printer_acceptance']['recognized_profile'] == {
    'brand': 'MARSWORK',
    'material': 'PLA',
    'color': 'Cyan',
    'nozzle_min_c': 190,
    'nozzle_max_c': 230,
}
print('M4 codec, physical NTAG215, and U1 evidence: PASS')
