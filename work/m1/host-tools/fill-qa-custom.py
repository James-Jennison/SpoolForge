import subprocess,re,xml.etree.ElementTree as E
adb=['adb','-s','192.168.1.64:43299']
assert subprocess.check_output(adb+['shell','getprop','ro.product.model'],text=True).strip()=='motorola razr 2023'
def tree():
 subprocess.run(adb+['shell','uiautomator','dump','/data/local/tmp/spoolio-form.xml'],stdout=subprocess.DEVNULL,check=True)
 raw=subprocess.check_output(adb+['shell','cat','/data/local/tmp/spoolio-form.xml'])
 subprocess.run(adb+['shell','rm','/data/local/tmp/spoolio-form.xml'],check=True)
 return E.fromstring(raw)
for label,value in [('Brand','Spoolio QA'),('Material','PLA'),('Product (optional label)','Persistence QA'),('Color name (optional label)','Orange'),('Color hex','FF8800'),('Diameter mm','1.75'),('Mass g','1000'),('Nozzle minimum °C','200'),('Nozzle maximum °C','220'),('Bed minimum °C','50')]:
 root=tree();fields=[n for n in root.iter('node') if n.get('package')=='net.jamesjennison.filamajignfc' and n.get('class')=='android.widget.EditText' and any(c.get('text')==label for c in n.iter('node'))]
 assert len(fields)==1,(label,'No unique visible field')
 x1,y1,x2,y2=map(int,re.findall(r'\d+',fields[0].get('bounds')))
 subprocess.run(adb+['shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)],check=True)
 subprocess.run(adb+['shell','input','text',value.replace(' ','%s')],check=True)
 subprocess.run(adb+['shell','input','keyevent','4'],check=True)
 print('Filled',label,flush=True)
