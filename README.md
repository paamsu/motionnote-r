# 모션노트 R

R은 포트홀, 주차 중 충격, 사고 같은 차량 사건이 생기면 당시 기록을 모아 신고, 보험, 정비 접수로 이어 주는 Pleos Connect 차량 앱입니다. 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 시연 범위

사건 장면은 합성 사진·영상과 미리 만든 입력값으로 재현합니다. 신고, 보험, 예약, 휴대폰 알림은 로컬 시연용으로 처리하며 외부 기관이나 원격 서버로 보내지 않습니다.

## APK

APK는 [GitHub Releases](../../releases)에 올려 두었습니다. 차량 APK가 100MB를 넘어 저장소에는 넣지 않았습니다.

| 파일 | 내용 | 넣을 위치 |
|---|---|---|
| `모션노트_R_release.apk` | 차량 앱(Pleos Connect 에뮬레이터용) | `apk/` |
| `R-phone.apk` | 휴대폰 주차 위치 시연 앱(Android 휴대폰 에뮬레이터용) | `phone/` |

## 테스트 환경

| 항목 | 버전 |
|---|---|
| 차량 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14, API 34, x86_64) |
| 차량 화면 규격 | 2560 × 1440 가로, 160 dpi (AVD 설정 `hw.lcd.width=2560`, `hw.lcd.height=1440`, `hw.lcd.density=160`) |
| 휴대폰 에뮬레이터 | Android Studio 기본 Medium Phone (API 37) |
| 빌드 | Android Studio, JDK 17, Android SDK 35 |
| 시연 스크립트 | Python 3.11 이상(3.14.6에서 확인), ADB, FFmpeg |

Pleos 에뮬레이터 설치는 [Pleos Connect Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) 문서를 따릅니다.

## 실행 방법

### 차량 앱

Pleos 에뮬레이터를 켠 뒤 실행합니다. `emulator-5554`와 사용자 번호 `10`은 에뮬레이터에 맞게 바꿉니다.

```sh
adb -s emulator-5554 install -r --user 10 apk/모션노트_R_release.apk
cd demo
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python replay.py --check
.venv/bin/python replay.py --scene road --serial emulator-5554 --user 10
```

Windows에서는 `python3` 대신 `py`, `.venv/bin/python` 대신 `.venv\Scripts\python`을 씁니다.

- `--check`는 `demo/scenes.json`에 있는 사건 25개의 자료와 무결성 값을 확인합니다. 에뮬레이터는 쓰지 않습니다.
- `--scene`으로 고른 사건 하나를 에뮬레이터 앱에서 엽니다.
- 요약 장면(`summary`)은 영상 정보를 다시 읽기 때문에 FFmpeg의 `ffprobe`가 PATH에 있어야 합니다.

### 휴대폰 앱

별도의 Android 휴대폰 에뮬레이터에서 실행합니다. 주차 사진 네 장 저장, 주차 위치 알림, 같은 주차 기록 열기를 차례로 보여 줍니다.

```sh
python3 phone/parking_phone.py --check
python3 phone/parking_phone.py --serial emulator-5556 --install --allow-notifications
```

## 빌드

```sh
# 차량 앱
./gradlew :app:assembleContestRelease -ProadGuardianPleosDemoHeadsUp=true
# 휴대폰 앱
./gradlew :app:assembleDebug -ProadGuardianDebugManifest=phone/PhoneManifest.xml
```

- release 빌드에는 서명 설정이 없습니다. Releases의 차량 APK는 팀 키로 서명했습니다. 직접 빌드하면 아래처럼 자신의 키로 서명합니다.
- 다른 키로 서명한 APK가 이미 설치돼 있으면 `adb uninstall --user 10 ai.teraq.roadguardian.contest.release`로 지운 뒤 설치합니다.
- Windows에서는 경로에 한글이 있으면 빌드가 멈출 수 있으니 영문 경로에서 빌드합니다.

```sh
apksigner sign --ks 내_키.jks --out apk/모션노트_R_release.apk \
  src/ui/android/build/outputs/apk/contestRelease/app-contestRelease-unsigned.apk
```

## 사용한 차량 API

| 데이터 | 경로 | 쓰는 곳 |
|---|---|---|
| 기어 | AAOS `GEAR_SELECTION` | 정차(P단) 중에만 상세 화면 열기 |
| 속도 | AAOS `PERF_VEHICLE_SPEED` | 사건 기록 |
| 주행거리, 와이퍼 | Pleos Vehicle SDK 2.3.2 | 사건 맥락, 정비 기록 |
| 타이어 공기압, 에어백 | Pleos Vehicle SDK 2.3.2 | 사고·포트홀 기록 보강 |

## 폴더 구성

| 폴더 | 내용 |
|---|---|
| `src/ui/android` | 차량·휴대폰 앱(Kotlin) |
| `src/backend` | 시연 서버(Python) |
| `src/incident`, `src/evidence` | 사건 판정, 근거 정리 |
| `src/acquisition`, `src/integrations` | 시연 입력 재현, 외부 데이터 연결 |
| `src/parking`, `src/diagnostics` | 주차 기록, 정비 측정 |
| `demo` | 사건 재생 스크립트와 합성 장면 |
| `phone` | 휴대폰 시연 스크립트와 사진 |

## 글꼴

휴대폰 화면에는 Asta Sans를 씁니다. 라이선스는 SIL Open Font License이며 `src/ui/android/src/main/assets/AstaSans-OFL.txt`에 있습니다.
