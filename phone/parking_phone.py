"""Replay authored parking photos -> visible phone reminder -> same stored record.
No OCR, descent sensor, cloud push or vehicle acquisition is asserted.
"""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,uuid,re
ROOT=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--check',action='store_true',help='Verify packaged files without operating a device')
 p.add_argument('--serial',help='Explicit phone emulator serial; automotive devices are rejected')
 p.add_argument('--install',action='store_true',help='Update the phone debug app without deleting its data')
 p.add_argument('--allow-notifications',action='store_true',help='Grant local app notifications on this emulator')
 a=p.parse_args()
 manifest=json.loads((ROOT/'assets.json').read_text())
 for name,digest in manifest.items():
  f=ROOT/name
  if not f.is_file() or hashlib.sha256(f.read_bytes()).hexdigest()!=digest:raise RuntimeError('Packaged file changed: '+name)
 if a.check:print('PASS phone APK and four parking photos; no device operated');return
 if not a.serial:p.error('--serial is required; select the phone emulator explicitly')
 A=[os.environ.get('ROAD_GUARDIAN_ADB','adb'),'-s',a.serial]
 def adb(*args):return subprocess.check_output(A+list(args),stderr=subprocess.STDOUT,timeout=120)
 assert adb('get-state').strip()==b'device'
 assert b'android.hardware.type.automotive' not in adb('shell','pm','list','features'),'Automotive device rejected'
 # Do not install to a physical phone by accident.
 assert adb('shell','getprop','ro.kernel.qemu').strip()==b'1','This authored launcher is for an emulator'
 if a.install:print(adb('install','-r',str(ROOT/'R-phone.apk')).decode())
 if a.allow_notifications:adb('shell','pm','grant','ai.teraq.roadguardian','android.permission.POST_NOTIFICATIONS')
 assert b'ai.teraq.roadguardian/.ParkingRepresentativeProbe' in adb('shell','pm','list','instrumentation'),'Install packaged phone APK first'
 runid=uuid.uuid4().hex[:12];remote='/data/local/tmp/r-phone-'+runid
 output=ROOT/'runs'/runid;output.mkdir(parents=True)
 adb('shell','mkdir','-p',remote)
 for d in ['front','rear','left','right']:adb('push',str(ROOT/'photos'/(d+'.png')),remote+'/'+d+'.png')
 log=adb('shell','am','instrument','-w','-e','source',remote,'ai.teraq.roadguardian/.ParkingRepresentativeProbe').decode()
 (output/'instrumentation.log').write_text(log)
 match=re.search(r'output=(/[^\r\n]+)',log)
 if match:
  where=match.group(1).strip()
  for name in ['saved-four-photo-record.png','location-reminder.png','location-reminder-settled.png','notification-visible-labels.json','destination-visible-labels.json','same-location-opened.png','result.json']:
   try:(output/name).write_bytes(adb('exec-out','run-as','ai.teraq.roadguardian','cat',where+'/'+name))
   except subprocess.CalledProcessError:pass
 assert 'PASS saved four photos → visible SystemUI reminder' in log,log
 result=json.loads((output/'result.json').read_text())
 assert result['photo_count']==4 and result['same_record_intent'] and result['notification_title_location_visible'] and result['destination_visible']
 (output/'launcher-result.json').write_text(json.dumps(dict(serial=a.serial,device='phone emulator',source='authored representative',files_verified=True,record_id=result['record_id'],external_dispatch=False,descent_sensor_test=False,ocr_test=False),ensure_ascii=False,indent=2)+'\n')
 print('PASS representative parking reminder. Evidence:',output)
if __name__=='__main__':main()
