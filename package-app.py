from pathlib import Path
import subprocess,os,json,hashlib,zipfile,shutil,re
import xml.etree.ElementTree as ET
root=Path(__file__).parent
version=re.search(r'versionName = "([^"]+)"',(root/'app/build.gradle.kts').read_text()).group(1)
out=root/'outputs'/f'whatsapp-auto-replay-{version}'
out.mkdir(parents=True,exist_ok=True)
sdk=Path(os.environ['ANDROID_HOME'])
env=os.environ.copy()
analyzer=next(sdk.glob('cmdline-tools/**/bin/apkanalyzer.bat'))
signer=sorted(sdk.glob('build-tools/*/apksigner.bat'))[-1]
def run(exe,*args):
 return subprocess.run([str(exe),*map(str,args)],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,encoding='utf-8',errors='replace').stdout
ns='{http://schemas.android.com/apk/res/android}'
audit={}
for variant in ['automatic','noAccessibility']:
 apk=root/f'app/build/outputs/apk/{variant}/debug/app-{variant.lower()}-debug.apk'
 if not apk.exists(): apk=next((root/f'app/build/outputs/apk/{variant}/debug').glob('*.apk'))
 raw=run(analyzer,'manifest','print',apk)
 manifest=raw[raw.index('<?xml'):raw.index('</manifest>')+11]
 xml=ET.fromstring(manifest)
 assert xml.attrib['package'].startswith('com.privateapps.autoreplay')
 assert xml.find('application').attrib[ns+'label'].startswith('Whatsapp Auto replay')
 (out/f'{variant}-manifest.xml').write_text(manifest,encoding='utf-8')
 sig=run(signer,'verify','--verbose','--print-certs',apk)
 (out/f'{variant}-signature.txt').write_text(sig,encoding='utf-8')
 suites=[ET.parse(f).getroot().attrib for f in (root/f'app/build/test-results/test{variant[0].upper()+variant[1:]}DebugUnitTest').glob('TEST-*.xml')]
 count=sum(int(s['tests']) for s in suites)
 assert count>=86
 assert all(int(s['errors'])==int(s['failures'])==int(s['skipped'])==0 for s in suites)
 lint=ET.parse(root/f'app/build/reports/lint-results-{variant}Debug.xml').getroot()
 assert not any(i.attrib['severity'] in ['Error','Fatal'] for i in lint)
 dex=run(analyzer,'dex','packages','--defined-only',apk)
 assert ('com.privateapps.autoreplay.ReplyAccessibilityService' in dex)==(variant=='automatic')
 with zipfile.ZipFile(apk) as z:
  assert z.testzip() is None
  assert any('reply_accessibility' in n for n in z.namelist())==(variant=='automatic')
 name=f'Whatsapp-Auto-replay-{version}-experimental.apk' if variant=='automatic' else f'Whatsapp-Auto-replay-{version}-NO-ACCESSIBILITY.apk'
 shutil.copy2(apk,out/name)
 audit[variant]={'package':xml.attrib['package'],'version':xml.attrib.get(ns+'versionName'),'permissions':[n.attrib[ns+'name'] for n in xml.findall('uses-permission')], 'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'tests':count,'lintErrors':0,'certificate':re.search(r'certificate SHA-256 digest: (\w+)',sig).group(1)}
assert audit['automatic']['certificate']==audit['noAccessibility']['certificate']
(out/'apk-audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
shutil.copy2(root/'README.md',out/'README.md')
with zipfile.ZipFile(out/'Whatsapp-Auto-replay-source.zip','w',zipfile.ZIP_DEFLATED) as z:
 for name in run('git','-C',root,'ls-files','--cached','--others','--exclude-standard').splitlines():
  f=root/name
  if f.is_file(): z.write(f,'WhatsappAutoReplay/'+f.relative_to(root).as_posix())
with zipfile.ZipFile(out/'verification-reports.zip','w',zipfile.ZIP_DEFLATED) as z:
 for directory in ['app/build/reports','app/build/test-results','app/build/outputs/logs']:
  for f in (root/directory).rglob('*'):
   if f.is_file(): z.write(f,f.relative_to(root).as_posix())
(out/'SHA256SUMS.txt').write_text('\n'.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name for f in out.iterdir() if f.suffix in ['.apk','.zip']),encoding='utf-8')
print(json.dumps(audit,indent=2))
