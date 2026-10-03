"""Read-only independent checks of bundle, source inputs, candidate identities and conflicts."""
from pathlib import Path
import gzip, hashlib, json, re, sqlite3, tempfile
from decimal import Decimal
from functools import lru_cache
import yaml
if not __debug__: raise SystemExit('Verification refuses optimized Python because its invariant checks require assertions')
ROOT=Path(__file__).resolve().parents[1]
a=ROOT/'app/src/main/assets';m=json.loads((a/'gtin-index-manifest.json').read_text())
inputs=json.loads((ROOT/'work/gtin/input-hashes.json').read_text())
MIT_BODY='''Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.'''
def is_canonical_mit(data):
 normalized=data.decode().translate(str.maketrans({'“':'"','”':'"','‘':"'",'’':"'"})).replace('\r\n','\n');marker='Permission is hereby granted, free of charge'
 if marker not in normalized:return False
 prefix=[line.strip() for line in normalized[:normalized.index(marker)].splitlines() if line.strip()]
 if prefix and prefix[0]=='MIT License':prefix.pop(0)
 if len(prefix)!=1 or not re.fullmatch(r'Copyright(?: \(c\)| ©)? \d{4}(?:-\d{4})? .+',prefix[0]):return False
 return ' '.join(normalized[normalized.index(marker):].split())==' '.join(MIT_BODY.split())
def require_mit(data):assert is_canonical_mit(data)
def community_package_id(identity,scope):
 discriminator='community|'+identity+'|'+scope
 return 'community:'+identity+':'+hashlib.sha256(discriminator.encode()).hexdigest()[:12]
def opt_package_id(identity,pointer):
 return 'opt:'+identity+':'+hashlib.sha256(pointer.encode()).hexdigest()[:12]
def normalize_source_gtin(raw):
 text=str(raw).strip()
 if not re.fullmatch(r'[0-9]{8}|[0-9]{12,14}',text) or set(text)=={'0'}:return None
 total=sum(int(c)*(3 if i%2==0 else 1) for i,c in enumerate(text[-2::-1]))+int(text[-1])
 return text.zfill(14) if total%10==0 else None
def source_binding_valid(source,e,o,gtin):
 if source=='ofd':
  raw=o['size'].get('gtin') or o['size'].get('ean')
  return e.get('scope')=='package' and e['source_record']==o['size'].get('uuid') and normalize_source_gtin(raw)==gtin
 if source=='opt':
  return e.get('scope')=='package' and e['source_record']==o['package'].get('uuid') and normalize_source_gtin(o['package'].get('gtin'))==gtin
 if source=='community':
  scope=e.get('scope')
  if scope not in {'refill','spooled package'}:return False
  refill=o.get('is_refill')
  if (scope=='refill' and refill is False) or (scope=='spooled package' and refill is True):return False
  kind='eans_refill' if scope=='refill' else 'eans'
  return str(e['source_record'])==str(o.get('id','')) and gtin in {normalize_source_gtin(x) for x in o.get(kind) or []}
 return False
def decimal_value(v):return '' if v is None else format(Decimal(str(v)).normalize(),'f')
def scalar(v):return '' if v is None else str(v)
@lru_cache(maxsize=None)
def load_source(path):
 p=ROOT/path;data=p.read_bytes()
 return json.loads(data) if p.suffix=='.json' else yaml.load(data,Loader=yaml.CSafeLoader)
def input_key(source,relative):
 base={'ofd':'research/sources/ofd/','opt':'research/sources/opt-db/','community':'research/sources/'}[source]
 return base+relative.split('#/',1)[0].split(' (',1)[0]
def reconstruct_original(source,pointer):
 if source=='ofd':
  relative,index=pointer.rsplit('#/',1);p=Path('research/sources/ofd')/relative
  size=load_source(str(p))[int(index)]
  return {'size':size,'variant':load_source(str(p.parent/'variant.json')),'product':load_source(str(p.parent.parent/'filament.json')),'material':load_source(str(p.parent.parent.parent/'material.json')),'brand':load_source(str(p.parent.parent.parent.parent/'brand.json'))}
 if source=='opt':
  p=Path('research/sources/opt-db')/pointer;package=load_source(str(p))
  mp=Path('research/sources/opt-db/data/materials')/p.parent.name/(package['material']['slug']+'.yaml');mat=load_source(str(mp))
  bp=Path('research/sources/opt-db/data/brands')/(mat['brand']['slug']+'.yaml');brand=load_source(str(bp))
  return {'package':package,'material':{k:v for k,v in mat.items() if k!='photos'},'brand':{k:v for k,v in brand.items() if k!='logo'}}
 relative,index=pointer.rsplit('#/',1)
 return load_source('research/sources/'+relative)[int(index)]
def expected_values(source,o,pointer):
 if source=='ofd':
  s=o['size'];v=o['variant'];p=o['product'];color=v.get('color_hex') or '';colors=color if isinstance(color,list) else [color]
  material=o['material'].get('name') or Path(pointer.split('#/',1)[0]).parent.parent.parent.name
  return {'brand':scalar(o['brand'].get('name')),'material':scalar(material),'product':scalar(p.get('name')),'colorName':scalar(v.get('name')),'colorHex':scalar(colors[0]).lstrip('#').upper(),'additionalColorHexes':','.join(scalar(x).lstrip('#').upper() for x in colors[1:]),'diameter':decimal_value(s.get('diameter')),'mass':decimal_value(s.get('filament_weight')),'nozzleMin':scalar(p.get('min_print_temperature')),'nozzleMax':scalar(p.get('max_print_temperature')),'bedMin':scalar(p.get('min_bed_temperature')),'bedMax':scalar(p.get('max_bed_temperature')),'variantId':scalar(v.get('uuid')),'productId':scalar(p.get('uuid')),'articleNumber':scalar(s.get('article_number'))}
 if source=='opt':
  p=o['package'];m=o['material'];prop=m.get('properties') or {};rgba=(m.get('primary_color') or {}).get('color_rgba','').lstrip('#').upper();color=rgba[:6] if len(rgba)==8 and rgba.endswith('FF') else ''
  extras=[x.get('color_rgba','').lstrip('#').upper()[:6] for x in m.get('secondary_colors') or [] if len(x.get('color_rgba','').lstrip('#'))==8 and x.get('color_rgba','').lstrip('#').upper().endswith('FF')]
  return {'brand':scalar(o['brand'].get('name')),'material':scalar(m.get('type')),'product':scalar(m.get('name')),'colorName':'','colorHex':color,'additionalColorHexes':','.join(extras),'diameter':decimal_value(Decimal(str(p['filament_diameter']))/1000),'mass':decimal_value(p.get('nominal_netto_full_weight')),'nozzleMin':scalar(prop.get('min_print_temperature')),'nozzleMax':scalar(prop.get('max_print_temperature')),'bedMin':scalar(prop.get('min_bed_temperature')),'bedMax':scalar(prop.get('max_bed_temperature')),'variantId':scalar(m.get('uuid')),'productId':scalar(m.get('uuid')),'articleNumber':scalar(p.get('brand_specific_id'))}
 colors=o.get('color_hexes') or [];nozzle=o.get('extruder_temp_range') or [None,None];bed=o.get('bed_temp_range') or [None,None]
 return {'brand':scalar(o.get('manufacturer')),'material':scalar(o.get('material')),'product':scalar(o.get('name')),'colorName':'','colorHex':scalar(o.get('color_hex')).lstrip('#').upper(),'additionalColorHexes':','.join(scalar(x).lstrip('#').upper() for x in colors[1:]),'diameter':decimal_value(o.get('diameter')),'mass':decimal_value(o.get('weight')),'nozzleMin':scalar(nozzle[0]),'nozzleMax':scalar(nozzle[1]),'bedMin':scalar(bed[0]),'bedMax':scalar(bed[1]),'variantId':'','productId':'','articleNumber':''}
def expected_origins(source,o,pointer):
 if source=='community':return {k:pointer for k in expected_values(source,o,pointer)}
 p=Path(pointer.split('#/',1)[0])
 if source=='ofd':
  files={'brand':p.parent.parent.parent.parent/'brand.json','material':p.parent.parent.parent/'material.json','product':p.parent.parent/'filament.json','productId':p.parent.parent/'filament.json','nozzleMin':p.parent.parent/'filament.json','nozzleMax':p.parent.parent/'filament.json','bedMin':p.parent.parent/'filament.json','bedMax':p.parent.parent/'filament.json','colorName':p.parent/'variant.json','colorHex':p.parent/'variant.json','additionalColorHexes':p.parent/'variant.json','variantId':p.parent/'variant.json'}
  return {k:str(files[k]) if k in files else pointer for k in expected_values(source,o,pointer)}
 package=o['package'];material=o['material'];mp=Path('data/materials')/p.parent.name/(package['material']['slug']+'.yaml');bp=Path('data/brands')/(material['brand']['slug']+'.yaml')
 origins={k:(str(bp) if k=='brand' else str(mp)) for k in expected_values(source,o,pointer)}
 origins.update({'diameter':pointer+'#/filament_diameter (µm / 1000 = mm)','mass':pointer+'#/nominal_netto_full_weight','articleNumber':pointer+'#/brand_specific_id'})
 return origins
# These checks are deliberately independent of the builder implementation.
assert community_package_id('test-row','spooled package') != community_package_id('test-row','refill')
valid=('Copyright 2026 Example Author\n\n'+MIT_BODY).encode();require_mit(valid)
try:require_mit(valid+b'\nCommercial use prohibited.');raise AssertionError('appended restrictive license accepted')
except AssertionError as error:
 if str(error)=='appended restrictive license accepted':raise
try:require_mit(('Commercial use prohibited.\nCopyright 2026 Example Author\n\n'+MIT_BODY).encode());raise AssertionError('prepended restrictive license accepted')
except AssertionError as error:
 if str(error)=='prepended restrictive license accepted':raise
licenses={}
source_subdirs={'ofd':'ofd','opt':'opt-db','community':'spoolmandb'}
for source,subdir in source_subdirs.items():
 original=(ROOT/'research/sources'/subdir/'LICENSE').read_bytes();shipped=(a/f'GTIN-{source}-LICENSE.txt').read_bytes()
 assert shipped==original,(source,'shipped license differs from source')
 require_mit(original)
 licenses[source]='MIT'
assert hashlib.sha256(json.dumps(inputs,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()==m['input_manifest_sha256']
for name,h in inputs.items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==h,name
compressed=(a/'gtin-index.sqlite.gzip').read_bytes();assert hashlib.sha256(compressed).hexdigest()==m['compressed_sha256'];raw=gzip.decompress(compressed);assert len(raw)==m['uncompressed_bytes'];assert hashlib.sha256(raw).hexdigest()==m['sha256']
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'index.sqlite';p.write_bytes(raw);db=sqlite3.connect('file:'+str(p)+'?mode=ro',uri=True)
 assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
 assert db.execute('SELECT count(*) FROM candidates').fetchone()[0]==m['candidates']
 assert db.execute('SELECT count(DISTINCT gtin14) FROM candidates').fetchone()[0]==m['distinct_gtins']
 binding_samples={}
 for gtin,source,pid,values,evidence,original in db.execute('SELECT * FROM candidates'):
  e=json.loads(evidence);v=json.loads(values);o=json.loads(original)
  assert e['gtin14']==gtin and len(gtin)==14 and gtin.isdigit()
  assert (sum(int(x)*(1 if i%2 else 3) for i,x in enumerate(gtin)))%10==0
  assert e['snapshot']['license']==licenses[source] and e['record_pointer']
  assert hashlib.sha256(json.dumps(o,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()==e['record_sha256']
  assert all(k in e['fields'] for k,val in v.items() if val!='')
  assert normalize_source_gtin(e['raw_identifier'])==gtin
  assert source_binding_valid(source,e,o,gtin)
  assert input_key(source,e['record_pointer']) in inputs
  actual=reconstruct_original(source,e['record_pointer'])
  assert actual==o and hashlib.sha256(json.dumps(actual,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()==e['record_sha256']
  expected=expected_values(source,actual,e['record_pointer']);origins=expected_origins(source,actual,e['record_pointer'])
  assert all(expected.get(k)==val for k,val in v.items() if val!='')
  for field,meta in e['fields'].items():
   assert meta['origin']==origins[field],(source,field,meta['origin'],origins[field])
   assert input_key(source,meta['origin']) in inputs,(source,field,meta['origin'])
  binding_samples.setdefault(source,(gtin,e,o))
  if source=='ofd':
   assert pid==e['source_record']==o['size']['uuid']
   assert normalize_source_gtin(o['size'].get('gtin') or o['size'].get('ean'))==gtin
  else:assert pid.startswith(source+':')
  if source=='opt':
   assert e['source_record']==o['package']['uuid']
   assert pid==opt_package_id(str(e['source_record']),e['record_pointer'])
   assert normalize_source_gtin(o['package']['gtin'])==gtin
   assert v['diameter']==format((__import__('decimal').Decimal(str(o['package']['filament_diameter']))/1000).normalize(),'f')
  if source=='community':
   assert e['snapshot']['build_commit_proven'] is False
   assert e['record_pointer'].startswith('spoolmandb/compiled.json#/')
   assert str(o.get('id',''))==str(e['source_record'])
   assert pid==community_package_id(str(e['source_record']),str(e['scope']))
   raw_identifiers=o.get('eans_refill' if e['scope']=='refill' else 'eans') or []
   assert gtin in {normalize_source_gtin(x) for x in raw_identifiers}
 # Prove the oracle rejects a valid-but-unrelated GTIN and a changed OPT source UUID.
 for source,(gtin,e,o) in binding_samples.items():
  other='06938936717461' if gtin!='06938936717461' else '07340002119380'
  assert normalize_source_gtin(other)==other and not source_binding_valid(source,e,o,other)
  assert not source_binding_valid(source,dict(e,scope='invalid'),o,gtin)
 opt_gtin,opt_e,opt_o=binding_samples['opt'];wrong=dict(opt_e,source_record='00000000-0000-0000-0000-000000000000')
 assert not source_binding_valid('opt',wrong,opt_o,opt_gtin)
 expected_opt_id=opt_package_id(str(opt_e['source_record']),opt_e['record_pointer']);mutated_opt_id=expected_opt_id[:-1]+('0' if expected_opt_id[-1]!='0' else '1')
 assert mutated_opt_id!=expected_opt_id
 community_gtin,community_e,community_o=binding_samples['community'];incompatible=dict(community_o,is_refill=community_e['scope']=='spooled package')
 assert not source_binding_valid('community',community_e,incompatible,community_gtin)
 try:reconstruct_original('ofd','data/does-not-exist/sizes.json#/0');raise AssertionError('missing record pointer accepted')
 except FileNotFoundError:pass
 rows=db.execute("SELECT values_json,evidence_json FROM candidates WHERE gtin14='07340002119380' AND source='community'").fetchall();assert len(rows)==4
 assert {(json.loads(v)['mass'],json.loads(v)['diameter']) for v,e in rows}=={('1000','1.75'),('1000','2.85'),('2500','1.75'),('2500','2.85')}
 assert all({'mass','diameter'}<=set(json.loads(e)['conflicting_fields']) for v,e in rows)
 print(json.dumps({'integrity':'PASS','input_files':len(inputs),'candidates':m['candidates'],'keys':m['distinct_gtins'],'ambiguous_fixture':'PASS'}))
