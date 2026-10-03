"""Build the offline candidate index from the retained public snapshots. No network access.
Requires Python 3 + PyYAML. Never execute downloaded source or collapse source claims.
"""
from pathlib import Path
import collections, gzip, hashlib, json, re, sqlite3, tempfile
from decimal import Decimal
import yaml
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'research/sources'
OUT = ROOT / 'app/src/main/assets'
REVISIONS = {'ofd': 'e3888b68f1684f49ec1683022e66b9753d9db3cf', 'opt': 'f2fd57dd736cb26a4ff056b3c2a5d1eeaf18e4e5', 'community': None}
URLS = {'ofd': 'https://github.com/OpenFilamentCollective/open-filament-database', 'opt': 'https://github.com/OpenPrintTag/openprinttag-database', 'community': 'https://icezaza2543.github.io/SpoolmanDB-Community/filaments.json'}
LABELS = {'ofd': 'OFD', 'opt': 'OpenPrintTag', 'community': 'SpoolmanDB Community'}
inputs = {}

def read(path):
    data = path.read_bytes(); inputs[str(path.relative_to(ROOT))] = hashlib.sha256(data).hexdigest()
    return json.loads(data) if path.suffix == '.json' else yaml.load(data, Loader=yaml.CSafeLoader)

def dump(v): return json.dumps(v, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
def decimal(v): return '' if v is None else format(Decimal(str(v)).normalize(), 'f')
def normalize(raw):
    # OPT schema uses a number; retain raw type in evidence. Never infer arbitrary truncated codes.
    text = str(raw).strip()
    if not re.fullmatch(r'[0-9]{8}|[0-9]{12,14}', text): return None
    if set(text) == {'0'}: return None
    if (sum(int(c) * (3 if i % 2 == 0 else 1) for i, c in enumerate(text[-2::-1])) + int(text[-1])) % 10: return None
    return text.zfill(14)

def fields(**kw):
    return {k: str(v) if v is not None else '' for k, v in kw.items()}

def package_identity(source, identity, pointer, scope):
    if source == 'ofd': return identity
    discriminator = ('community|' + identity + '|' + scope) if source == 'community' else pointer
    return source + ':' + identity + ':' + hashlib.sha256(discriminator.encode()).hexdigest()[:12]

MIT_BODY = '''Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.'''

def is_canonical_mit(text):
    normalized = text.translate(str.maketrans({'“':'"','”':'"','‘':"'",'’':"'"})).replace('\r\n','\n')
    marker = 'Permission is hereby granted, free of charge'
    if marker not in normalized: return False
    prefix = [line.strip() for line in normalized[:normalized.index(marker)].splitlines() if line.strip()]
    if prefix and prefix[0] == 'MIT License': prefix.pop(0)
    if len(prefix) != 1 or not re.fullmatch(r'Copyright(?: \(c\)| ©)? \d{4}(?:-\d{4})? .+', prefix[0]): return False
    return ' '.join(normalized[normalized.index(marker):].split()) == ' '.join(MIT_BODY.split())

def identify_license(data):
    if not is_canonical_mit(data.decode('utf-8')):
        raise ValueError('Source LICENSE is not the canonical MIT license body')
    return 'MIT'

def main():
    records = []; rejected = []
    source_subdirs = {'ofd':'ofd','opt':'opt-db','community':'spoolmandb'}
    license_bytes = {source:(SRC/sub/'LICENSE').read_bytes() for source,sub in source_subdirs.items()}
    licenses = {source:identify_license(data) for source,data in license_bytes.items()}
    for source,sub in source_subdirs.items(): inputs[str((SRC/sub/'LICENSE').relative_to(ROOT))]=hashlib.sha256(license_bytes[source]).hexdigest()
    community = read(SRC / 'spoolmandb/compiled.json')
    if len({str(row.get('id')) for row in community}) != len(community): raise ValueError('Community IDs are not unique; stable candidate identity cannot be guaranteed')
    artifact_hash = inputs['research/sources/spoolmandb/compiled.json']
    def emit(source, identity, raw, pointer, values, origins, flags=(), scope='package', original=None):
        key = normalize(raw)
        if key is None:
            rejected.append({'source': source, 'raw': raw, 'pointer': pointer, 'reason': 'invalid length, digits, or check digit'}); return
        revision = REVISIONS[source] or 'artifact:' + artifact_hash
        snapshot = {'source': LABELS[source], 'revision': revision, 'url': URLS[source], 'license': licenses[source], 'build_commit_proven': source != 'community'}
        source_fields = {k: {'value': v, 'origin': origins.get(k, pointer), 'kind': 'compiled origin unverified' if source == 'community' else 'source value / documented unit conversion'} for k, v in values.items() if v != ''}
        evidence = {'snapshot': snapshot, 'source_record': identity, 'record_pointer': pointer, 'raw_identifier': raw, 'gtin14': key, 'scope': scope, 'flags': sorted(set(flags)), 'fields': source_fields}
        evidence['record_sha256'] = hashlib.sha256(dump(original).encode()).hexdigest()
        package_id = package_identity(source, identity, pointer, scope)
        records.append((key, source, package_id, dump(values), dump(evidence), dump(original)))
    # OFD package records, matching the existing importer hierarchy and identifiers.
    for p in sorted((SRC / 'ofd/data').rglob('sizes.json')):
        sizes = read(p); variant = read(p.parent / 'variant.json'); product = read(p.parent.parent / 'filament.json')
        material = read(p.parent.parent.parent / 'material.json'); brand = read(p.parent.parent.parent.parent / 'brand.json')
        for i, size in enumerate(sizes):
            raw = size.get('gtin') or size.get('ean')
            if not raw: continue
            pointer = str(p.relative_to(SRC / 'ofd')) + f'#/{i}'
            color = variant.get('color_hex') or ''
            colors = color if isinstance(color, list) else [color]
            values = fields(brand=brand.get('name'), material=material.get('name') or p.parent.parent.parent.name, product=product.get('name'), colorName=variant.get('name'), colorHex=colors[0].lstrip('#').upper(), additionalColorHexes=','.join(c.lstrip('#').upper() for c in colors[1:]), diameter=decimal(size.get('diameter')), mass=decimal(size.get('filament_weight')), nozzleMin=product.get('min_print_temperature'), nozzleMax=product.get('max_print_temperature'), bedMin=product.get('min_bed_temperature'), bedMax=product.get('max_bed_temperature'), variantId=variant.get('uuid'), productId=product.get('uuid'), articleNumber=size.get('article_number'))
            origins = {k: str(q.relative_to(SRC / 'ofd')) for keys, q in [(['brand'],p.parent.parent.parent.parent/'brand.json'),(['material'],p.parent.parent.parent/'material.json'),(['product','productId','nozzleMin','nozzleMax','bedMin','bedMax'],p.parent.parent/'filament.json'),(['colorName','colorHex','additionalColorHexes','variantId'],p.parent/'variant.json')] for k in keys}
            emit('ofd', size['uuid'], raw, pointer, values, origins, original={'size':size,'variant':variant,'product':product,'material':material,'brand':brand})
    # OPT: only FFF package records. Never import linked photos or download artwork.
    for p in sorted((SRC / 'opt-db/data/material-packages').rglob('*.yaml')):
        package = read(p)
        if package.get('class') != 'FFF' or package.get('gtin') is None: continue
        mp = SRC / 'opt-db/data/materials' / p.parent.name / (package['material']['slug'] + '.yaml')
        mat = read(mp); bp = SRC / 'opt-db/data/brands' / (mat['brand']['slug'] + '.yaml'); brand = read(bp)
        prop = mat.get('properties') or {}; rgba = (mat.get('primary_color') or {}).get('color_rgba','').lstrip('#').upper()
        flags = []
        color = rgba[:6] if len(rgba) == 8 and rgba.endswith('FF') else ''
        if rgba and not color: flags.append('Color requires confirmation: non-opaque RGBA')
        extras = []
        for c in mat.get('secondary_colors') or []:
            v = c.get('color_rgba','').lstrip('#').upper()
            if len(v)==8 and v.endswith('FF'): extras.append(v[:6])
            else: flags.append('Additional color requires confirmation')
        values = fields(brand=brand.get('name'), material=mat.get('type'), product=mat.get('name'), colorName='', colorHex=color, additionalColorHexes=','.join(extras), diameter=decimal(Decimal(str(package['filament_diameter']))/1000), mass=decimal(package.get('nominal_netto_full_weight')), nozzleMin=prop.get('min_print_temperature'), nozzleMax=prop.get('max_print_temperature'), bedMin=prop.get('min_bed_temperature'), bedMax=prop.get('max_bed_temperature'), variantId=mat.get('uuid'), productId=mat.get('uuid'), articleNumber=package.get('brand_specific_id'))
        pointer = str(p.relative_to(SRC / 'opt-db'))
        origins = {k: str(mp.relative_to(SRC / 'opt-db')) for k in values};origins.update({'brand':str(bp.relative_to(SRC/'opt-db')),'diameter':pointer+'#/filament_diameter (µm / 1000 = mm)','mass':pointer+'#/nominal_netto_full_weight','articleNumber':pointer+'#/brand_specific_id'})
        emit('opt',package['uuid'],package['gtin'],pointer,values,origins,flags,original={'package':package,'material':{k:v for k,v in mat.items() if k!='photos'},'brand':{k:v for k,v in brand.items() if k!='logo'}})
    for i, row in enumerate(community):
        for kind in ['eans','eans_refill']:
            for raw in row.get(kind) or []:
                # Preserve expanded candidates, but never attach a refill-only barcode to a spooled variant (or vice versa).
                refill = row.get('is_refill')
                if refill is not None and (kind == 'eans_refill') != refill: continue
                pointer = f'spoolmandb/compiled.json#/{i}'
                def bounds(name):
                    r=row.get(name+'_range');return r if isinstance(r,list) and len(r)==2 else [None,None]
                nozzle= bounds('extruder_temp');bed=bounds('bed_temp');colors=row.get('color_hexes') or []
                values=fields(brand=row.get('manufacturer'),material=row.get('material'),product=row.get('name'),colorName='',colorHex=(row.get('color_hex') or '').lstrip('#').upper(),additionalColorHexes=','.join(x.lstrip('#').upper() for x in colors[1:]),diameter=decimal(row.get('diameter')),mass=decimal(row.get('weight')),nozzleMin=nozzle[0],nozzleMax=nozzle[1],bedMin=bed[0],bedMax=bed[1],variantId='',productId='',articleNumber='')
                emit('community',row['id'],raw,pointer,values,{},['Compiled barcode association; package assignment requires confirmation'],scope='refill' if kind=='eans_refill' else 'spooled package',original=row)
    # Repeated claims for an identical source/package/key do not duplicate a candidate.
    unique = {}
    for r in records:
        k=r[:3]
        if k in unique:
            if unique[k][3] != r[3]: raise ValueError('Conflicting values for same source ID and GTIN')
            continue
        unique[k]=r
    records = sorted(unique.values())
    groups=collections.defaultdict(list)
    for r in records: groups[r[0]].append(r)
    with tempfile.TemporaryDirectory() as temp:
        dbpath=Path(temp)/'index.sqlite';db=sqlite3.connect(dbpath)
        db.executescript('PRAGMA user_version=1; CREATE TABLE candidates (gtin14 TEXT NOT NULL, source TEXT NOT NULL, package_id TEXT NOT NULL, values_json TEXT NOT NULL, evidence_json TEXT NOT NULL, source_json TEXT NOT NULL, PRIMARY KEY(gtin14,source,package_id));')
        for r in records:
            ev=json.loads(r[4]);group=groups[r[0]]
            conflicts=[field for field in ['brand','material','diameter','mass','colorHex'] if len({json.loads(x[3]).get(field,'').casefold() for x in group if json.loads(x[3]).get(field,'')})>1]
            ev['conflicting_fields']=conflicts;ev['candidate_count']=len(group)
            db.execute('INSERT INTO candidates VALUES (?,?,?,?,?,?)',(*r[:4],dump(ev),r[5]))
        db.commit();assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok';db.close()
        raw=dbpath.read_bytes();compressed=gzip.compress(raw,mtime=0);OUT.mkdir(parents=True,exist_ok=True)
        (OUT/'gtin-index.sqlite.gzip').write_bytes(compressed)
    for key,data in license_bytes.items(): (OUT/f'GTIN-{key}-LICENSE.txt').write_bytes(data)
    manifest={'format':'spoolio-gtin-sqlite-v1','sha256':hashlib.sha256(raw).hexdigest(),'compressed_sha256':hashlib.sha256(compressed).hexdigest(),'uncompressed_bytes':len(raw),'candidates':len(records),'distinct_gtins':len(groups),'sources':{k:{'url':URLS[k],'revision':REVISIONS[k],'compiled_sha256':artifact_hash if k=='community' else None} for k in LABELS},'input_manifest_sha256':hashlib.sha256(dump(inputs).encode()).hexdigest()}
    (OUT/'gtin-index-manifest.json').write_text(dump(manifest)+'\n')
    (ROOT/'work/gtin/input-hashes.json').write_text(dump(inputs)+'\n');(ROOT/'work/gtin/rejected-identifiers.json').write_text(dump(rejected)+'\n')
    print(dump(manifest))
if __name__=='__main__':main()
