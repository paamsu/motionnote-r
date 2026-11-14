# 차량 사건 재생

미리 만든 사건 25개 중 하나를 Pleos 에뮬레이터 앱에서 엽니다. 사건 목록은 `scenes.json`에 있습니다.

```sh
python replay.py --check
python replay.py --scene road --serial emulator-5554 --user 10
```

- `--check`는 기기를 건드리지 않고 장면 파일이 손상되지 않았는지만 확인합니다.
- 요약 장면(`summary`)은 FFmpeg의 `ffprobe`가 필요합니다.
- 차량 앱은 `../apk/MotionNote_R_release.apk`를 먼저 설치합니다.
- `notice.py`는 저장된 사건을 앱에 넣어 주행 중 알림을 띄우는 도구입니다.
- 시연 서버는 9460 포트를 씁니다. 종료는 Ctrl+C입니다.

휴대폰 주차 위치 시연은 `../phone/parking_phone.py`로 따로 실행합니다.
