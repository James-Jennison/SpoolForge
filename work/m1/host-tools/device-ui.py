import subprocess,sys,json,xml.etree.ElementTree as E
from pathlib import Path
adb=['adb','-s','192.168.1.64:43299']
model=subprocess.check_output(adb+['shell','getprop','ro.product.model'],text=True).strip()
assert model=='motorola razr 2023',model
subprocess.run(adb+['shell','uiautomator','dump','/data/local/tmp/spoolio-check.xml'],stdout=subprocess.DEVNULL,check=True)
raw=subprocess.check_output(adb+['shell','cat','/data/local/tmp/spoolio-check.xml'])
subprocess.run(adb+['shell','rm','/data/local/tmp/spoolio-check.xml'],check=True)
nodes=[n.attrib for n in E.fromstring(raw).iter('node') if n.get('package')=='net.jamesjennison.filamajignfc']
if not nodes: raise SystemExit('Spoolio not visible; unrelated UI discarded')
Path('work/m1/host-evidence/'+sys.argv[1]+'.json').write_text(json.dumps(nodes,indent=2)+'\n')
for n in nodes:
 if n.get('text') or n.get('content-desc') or n.get('class')=='android.widget.EditText':print(n.get('text') or n.get('content-desc') or 'INPUT',n.get('bounds'))
