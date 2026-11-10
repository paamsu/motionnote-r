"""Explicitly inject one preserved synthetic event into a local contest session."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse,json,os,socket,subprocess,sys,threading,time,urllib.request,urllib.error
from replay import ROOT,CAT,prepare

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--scene',choices=['road','crash','diagnosis'],default='road')
 p.add_argument('--serial',default='emulator-5554');p.add_argument('--user',default='10')
 args=p.parse_args();row=next(r for r in CAT if r['scene']==args.scene);dest=prepare(row)
 adb=[os.environ.get('ROAD_GUARDIAN_ADB','adb'),'-s',args.serial]
 with socket.socket() as probe:
  if probe.connect_ex(('127.0.0.1',9460))==0:raise RuntimeError('Port 9460 occupied; existing server preserved')
 if b'tcp:9460' in subprocess.check_output(adb+['reverse','--list']):raise RuntimeError('Existing reverse mapping preserved')
 with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
 feed=[];forwarded=False;proxy=None
 class Proxy(BaseHTTPRequestHandler):
  def relay(self):
   if self.command=='GET' and self.path=='/api/events':
    body=json.dumps(feed).encode();status=200;kind='application/json'
   else:
    try:
     length=int(self.headers.get('Content-Length','0'))
     if length>8*1024*1024:self.send_error(413);return
     req=urllib.request.Request(f'http://127.0.0.1:{port}'+self.path,
      data=self.rfile.read(length) if length else None,method=self.command,
      headers={'Content-Type':self.headers.get('Content-Type','application/json')})
     try:response=urllib.request.urlopen(req,timeout=15)
     except urllib.error.HTTPError as error:response=error
     with response:body=response.read();status=response.status;kind=response.headers.get('Content-Type','application/octet-stream')
    except Exception:self.send_error(502);return
   self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
  do_GET=relay;do_POST=relay
  def log_message(self,*args):pass
 backend=subprocess.Popen([sys.executable,'-m','backend.server','--port',str(port),'--database',str(dest/'events.sqlite3')],env=dict(os.environ,PYTHONPATH=str(ROOT/'src')),stdout=(dest/'notice-server.log').open('w'),stderr=subprocess.STDOUT)
 try:
  for _ in range(50):
   if backend.poll() is not None:raise RuntimeError('Backend stopped')
   try:urllib.request.urlopen(f'http://127.0.0.1:{port}/health',timeout=1).close();break
   except OSError:time.sleep(.2)
  else:raise RuntimeError('Backend not ready')
  event=json.load(urllib.request.urlopen(f'http://127.0.0.1:{port}/api/events/'+row['event_id']))
  assert event['evidence_valid']
  proxy=ThreadingHTTPServer(('127.0.0.1',9460),Proxy);threading.Thread(target=proxy.serve_forever,daemon=True).start()
  subprocess.run(adb+['reverse','tcp:9460','tcp:9460'],check=True);forwarded=True
  subprocess.run(adb+['shell','am','start','--user',args.user,'--activity-clear-top','-n','ai.teraq.roadguardian.contest.release/ai.teraq.roadguardian.MainActivity'],check=True)
  print('합성 사진·영상과 센서 입력으로 상황을 재현하며, 보험·예약·기관 접수는 시연용 처리입니다.',flush=True)
  print('설정 → 다른 앱 위 이벤트 안내를 켜고, 내비 배경 시연 또는 음악 앱을 여세요. 7초 이상 지난 뒤 Enter를 누르면 사건 1건을 표시합니다.',flush=True)
  input()
  feed.append(event)
  print('EVENT_RELEASED '+row['event_id'],flush=True)
  print('알림 후 사건 근거에서 사진·지도·후속 행동을 확인하세요. 종료: Ctrl+C',flush=True)
  while backend.poll() is None:time.sleep(1)
 except (KeyboardInterrupt,EOFError):pass
 finally:
  if forwarded:subprocess.run(adb+['reverse','--remove','tcp:9460'],check=False)
  if proxy:proxy.shutdown();proxy.server_close()
  backend.terminate();backend.wait(timeout=10)
if __name__=='__main__':main()
