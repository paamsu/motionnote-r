# 휴대폰 주차 위치 시연

주차 사진 네 장을 저장하고, 주차 위치 알림(B2 · 24번 기둥)을 띄운 뒤, 같은 주차 기록을 엽니다. Android 휴대폰 에뮬레이터에서 실행합니다.

```sh
python3 parking_phone.py --check
python3 parking_phone.py --serial emulator-5556 --install --allow-notifications
```

- Python 3과 ADB가 필요합니다. ADB가 PATH에 없으면 `ROAD_GUARDIAN_ADB`에 경로를 넣습니다.
- `R-phone.apk`는 GitHub Releases에서 받아 이 폴더에 넣습니다. `--install`이 이 파일을 설치합니다.
- `--allow-notifications`는 시연 앱의 알림 권한을 켭니다.
- 실제 휴대폰과 차량 에뮬레이터에서는 실행되지 않게 막아 두었습니다.
- 결과 화면과 알림 내용은 `runs/<실행 번호>/`에 저장됩니다.

APK를 직접 빌드하려면 저장소 루트에서 실행합니다.

```sh
./gradlew :app:assembleDebug -ProadGuardianDebugManifest=phone/PhoneManifest.xml
```

빌드 결과는 `src/ui/android/build/outputs/apk/debug/app-debug.apk`입니다. 이 파일을 `R-phone.apk`로 이름을 바꿔 이 폴더에 넣습니다.
