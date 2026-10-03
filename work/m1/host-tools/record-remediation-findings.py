import json,subprocess
from pathlib import Path
base=Path('work/m1/host-evidence')
cycles={'storage':'review-cycle-8b02c7b33686','nfc':'review-cycle-d04a66d06bba'}
reasons={
 ('storage','deepseek',1):('REJECTED','NOT APPLICABLE: Owner requires exact package selection, not equality-only search. Prefix discovery plus exact package identity is intentional; observed SUNLU Orange selection is exact.',True),
 ('storage','deepseek',2):('CONFIRMED','Missing-field provenance corrected; targeted regression added. Pending closure review.',False),
 ('storage','deepseek',3):('CONFIRMED','CONFIRMED WITH NARROWER SCOPE: malformed NFC handling already catches read/decode errors. A late callback after executor disposal could throw; RejectedExecutionException now contained. Pending closure review.',False),
 ('storage','deepseek',4):('CONFIRMED','CONFIRMED WITH NARROWER SCOPE: real 2023 legacy catalog recent hydrated and persisted. Matching generation is required to avoid fabricating old data; unavailable legacy source rows remain stored. Custom fallback and test added.',False),
 ('storage','deepseek',5):('CONFIRMED','Unused store and tests removed; real Android Room coverage retained.',False),
 ('nfc','deepseek',1):('REJECTED','No Owner20-entry eviction policy exists. Retention now includes readable history and atomic archive. Silent eviction would lose unresolved evidence; growth is a residual optional retention design.',True),
 ('nfc','deepseek',2):('CONFIRMED','CONFIRMED WITH NARROWER SCOPE: converter now validates all additional colors. Vendor-neutral catalog retains all colors; PAXX four-additional-color export bound remains strict, canonical omission explicit. Regression added.',False),
 ('nfc','deepseek',3):('CONFIRMED','Unused implementation and its tests removed. Actual Android catalog store is separately tested for corruption recovery.',False),
 ('nfc','claude',1):('CONFIRMED','CONFIRMED WITH NARROWER SCOPE: added readable timestamp UID and intended payload history, including after restart. No silent deletion or claim of physical reconciliation; deletion/retention policy is optional separate scope.',False),
 ('nfc','claude',2):('CONFIRMED','Archive and active-entry removal now share one SharedPreferences commit. Never clear active before durable archive. Regression asserts cleared active and all archived payloads retrievable.',False),
 ('nfc','claude',3):('CONFIRMED','Removed unreachable second ambiguity branch; conservative multiple-record rejection retained.',False),
 ('nfc','claude',4):('OUT_OF_SCOPE','Optional convention improvement, no current correctness defect. Existing import/validation/export and source oracle normalize safely; preserving asset convention avoids an unnecessary catalog format migration.',True),
 ('storage','claude',1):('CONFIRMED','Legacy custom fallback implemented through UserDao.byId and successful hydration regression with preserved data/source.',False),
 ('storage','claude',2):('CONFIRMED','CONFIRMED WITH NARROWER SCOPE: readable history implemented; silent eviction rejected because no Owner eviction policy exists. No removal or false resolution.',False),
 ('storage','claude',3):('NEEDS_INVESTIGATION','Actual-device existing-catalog integrity and warm-query benchmark being collected; do not weaken corruption checks without performance evidence.',False),
 ('storage','claude',4):('CONFIRMED','Catalog migration now seeded with existing row and asserts preserved values plus new column. Room also validates complete schema on open.',False),
 ('storage','claude',5):('REJECTED','NOT APPLICABLE as stated: a user-entered replacement remains locally edited even if equal to catalog. This records authorship accurately. Original package link and pinned catalog remain preserved; no requirement silently reattributes user edits to OFD.',True),
}
for part,reviewer in [('storage','deepseek'),('nfc','deepseek'),('storage','claude'),('nfc','claude')]:
 raw=json.loads((base/f'remediation-{part}-{reviewer}.json').read_text());response=raw['response']['response']
 if 'result' in response:
  response=json.loads(response['result'].strip().removeprefix('```json').removesuffix('```').strip())
 fs=response.get('findings',response.get('new_findings',[]))
 for i,f in enumerate(fs,1):
  f={k.lower().replace('_',' '):v for k,v in f.items()};fid=f'spoolio-m1-r2-{part}-{reviewer}-{i}'
  item={'finding_id':fid,'change_id':'spoolio-m1-20260906','cycle_id':cycles[part],'reviewer':reviewer,'severity':f['severity'],'category':f['area'],'summary':f['finding'],'claim':f['consequence'],'evidence':{'source':f['evidence'],'response_sha256':raw['response_sha256']},'affected_components':['app','core','catalog-tool'],'suggested_remediation':f['required correction'],'suggested_validation':f['acceptance test'],'reviewer_confidence':str(f['confidence']).split()[0].strip('—')}
  status,reason,resolved=reasons[(part,reviewer,i)]
  disposition={'finding_id':fid,'disposition':status,'reason':reason,'evidence_reference':'work/m1/host-evidence/remediation-round3-build.log; app/src/test/kotlin/net/jamesjennison/spoolio/StorageAcceptanceTest.kt; work/m1/host-evidence/migrated-user-data/summary.json','resolved':resolved}
  for suffix,data,command in [('finding',item,'finding'),('disposition',disposition,'disposition-finding')]:
   p=base/(fid+'-'+suffix+'.json');p.write_text(json.dumps(data,indent=2)+'\n');r=subprocess.run(['reviewer',command,str(p)],capture_output=True,text=True)
   if r.returncode:raise SystemExit(r.stdout+r.stderr)
  print(fid,status)
