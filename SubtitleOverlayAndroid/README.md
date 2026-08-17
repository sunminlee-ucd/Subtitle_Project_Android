# Subtitle Overlay Android

로컬 SRT 파일을 Netflix, YouTube 등 다른 Android 앱 위에 표시하는 오프라인 자막
오버레이 MVP입니다. 번역, 계정, 서버, 네트워크 기능은 포함하지 않습니다.

## MVP 기능

- Android 파일 선택기로 로컬 `.srt` 열기
- `TYPE_APPLICATION_OVERLAY` 투명 자막 표시
- Foreground Service를 통한 앱 전환 중 타이머 유지
- 재생/일시정지, 앞뒤 5초 이동
- 재생 속도 순환 조절: `0.5× → 0.75× → 1× → 1.25× → 1.5× → 2×`
- 0.5초 단위 자막 싱크 보정
- `A−` / `A+` 자막 크기 조절과 설정 자동 저장
- Netflix 기본 자막 영역에 가까운 하단 기본 위치
- `Pos ↓` / `Pos ↑` 위치 조절, `Reset` 기본 위치 복원
- Netflix/YouTube가 공개하는 MediaSession 재생 위치 자동 감지
- 자동 위치를 제공하지 않는 기기를 위한 수동 타이머 fallback
- Playback / Subtitle timing & size / Subtitle position / Study mode 기능별 접기·펼치기 컨트롤
- Watch mode / Study mode 전환 상태 저장
- Study mode에서 현재 표시 중인 자막을 직접 눌러 학습 목록에 저장/해제
- 저장된 자막은 SRT fingerprint별로 복원하며 최근 20개 SRT의 선택 목록 유지
- 자막별 반복 횟수 1~20회 설정, 기본값 5회
- 현재 자막 반복 재생 및 저장한 자막들을 시간순으로 반복 재생
- 저장한 Study playlist의 마지막 자막까지 끝나면 영상 일시정지
- Watch mode로 돌아갈 때 반복만 종료하고 일반 영상 재생은 계속 유지
- 영상 시청 중 컨트롤 완전 숨김, 알림의 **Controls**로 복원
- 최근 선택한 SRT 자동 재사용
- 자막 텍스트 폭에 맞춘 반투명 배경
- SRT 내용은 기기 내부에서만 처리

## 개발 환경

- Android Studio Quail 2 이상 권장
- Android Gradle Plugin 9.3.0
- Gradle Wrapper 9.5.0
- JDK 17
- compileSdk / targetSdk 36
- minSdk 26 (Android 8.0)

Android Studio에서 이 폴더를 열고 SDK 36을 설치한 뒤 Gradle Sync를 실행하세요.
프로젝트에 포함된 Gradle Wrapper가 빌드에 필요한 Gradle 9.5.0을 자동으로 사용합니다.

## 실행 방법

1. Android Studio에서 `SubtitleOverlayAndroid` 폴더를 엽니다.
2. 실제 Android 기기를 연결하고 `app`을 실행합니다.
3. **다른 앱 위에 표시 권한 허용**을 누르고 앱 권한을 켭니다.
4. 자동 싱크와 Study 반복 재생을 사용하려면 **Enable automatic playback sync**를 누르고
   `Subtitle Overlay`의 알림 접근 권한을 켭니다.
5. 번역된 SRT 파일을 선택합니다. 선택한 파일은 다음 실행부터 자동으로 다시 불러옵니다.
6. **Start subtitle overlay**를 누른 뒤 Netflix 또는 YouTube를 엽니다.
7. 플레이어가 재생 위치를 공개하면 컨트롤에 `AUTO · N`, `AUTO · Y` 또는 `AUTO`가
   표시됩니다. `MANUAL`이면 영상 시작 시점에 맞춰 재생 버튼을 누릅니다.

영상 없이 기본 자막 표시 기능만 확인하려면 `sample_subtitles.srt`를 휴대폰으로 복사해 선택하세요.
재생 버튼을 누르면 1초 후부터 테스트 자막이 순서대로 표시됩니다.

`Δ`는 자막 싱크 오프셋입니다. `+0.5`는 자막 타이밍을 0.5초 늦추고, `-0.5`는
0.5초 앞당깁니다.

상단의 Playback / Subtitle timing & size / Subtitle position / Study mode 헤더를 누르면
각 기능의 컨트롤을 독립적으로 접거나 펼칠 수 있습니다.

Playback의 속도 버튼을 누르면 지원 속도를 순환합니다. 영상 앱이 Android MediaSession을
통해 속도 변경을 공개하면 실제 영상 속도를 변경하고, 수동 모드에서는 자막 타이머 속도를
변경합니다. 영상 앱이 외부 속도 제어를 허용하지 않으면 안내 메시지를 표시합니다.

## Study mode

웹 `Subtitle_Project/chrome_extension`의 Study mode 동작을 Android에 맞게 포팅했습니다.

1. Study mode 섹션에서 **Study**를 누릅니다.
2. 영상 위에 번역 자막이 표시될 때 자막 자체를 누르면 해당 cue가 학습 목록에 저장됩니다.
   다시 누르면 해제됩니다. Study mode에서 저장된 자막은 녹색 테두리/배경으로 구분됩니다.
3. `−`, `+`로 각 자막의 반복 횟수를 1~20회 사이에서 조절합니다. 기본값은 5회입니다.
4. **Repeat current**는 현재 표시 중인 자막 구간을 설정한 횟수만큼 반복한 뒤 일반 재생을
   계속합니다.
5. **Play saved**는 저장한 자막들을 원래 타임라인 순서로 정렬해 각각 설정한 횟수만큼
   반복합니다. 마지막 저장 자막이 끝나면 영상이 일시정지됩니다.
6. **Stop**은 반복을 즉시 중단하고 영상을 일시정지합니다.
7. **Clear**는 현재 SRT의 저장된 Study cue 목록을 비웁니다.
8. **Watch**로 돌아가면 진행 중인 Study 반복은 끝나지만 영상을 강제로 일시정지하지는 않습니다.

선택 목록은 `파일명 + SRT 내용`의 SHA-256 fingerprint를 기준으로 저장됩니다. 같은 SRT를
다시 열면 이전에 저장한 cue가 복원됩니다. 저장 공간 증가를 막기 위해 최근 20개 SRT의
선택 목록만 유지합니다.

웹 버전은 브라우저 `<video>`를 직접 제어하지만 Android는 다른 앱의 비디오 객체에 직접
접근할 수 없습니다. 따라서 Android에서 실제 영상 구간 반복은 대상 앱이 MediaSession을
통해 seek 제어를 제공할 때만 정확하게 동작합니다. 해당 제어를 제공하지 않는 앱에서는
Study cue 저장/복원은 사용할 수 있지만 반복 재생 버튼을 누르면 지원되지 않는다는 안내가
표시됩니다.

**Hide**는 컨트롤을 완전히 숨깁니다. Android 알림창의 Subtitle Overlay 알림에서
**Controls**를 누르면 다시 표시됩니다.

작은 `a`와 큰 `A`는 자막 크기를 14sp부터 36sp까지 2sp 단위로 조절합니다. 마지막 크기는
자동으로 저장되어 다음 실행에도 유지됩니다.

`↓`는 자막을 화면 아래쪽으로, `↑`는 위쪽으로 12dp씩 이동합니다. `↺`는
Netflix 기본 자막 영역에 가까운 하단 24dp 위치로 되돌립니다. 마지막 위치는 자동으로
저장됩니다.

## 고객 배포용 APK

고객에게는 일반 debug APK가 아니라 `.github/workflows/build-signed-release.yml`에서 생성한
**signed release APK만 배포**하세요. 이 워크플로는 고정된 release signing key를 GitHub
Actions Secrets에서 읽어 사용하고, 매 실행마다 `versionCode`를 증가시켜 기존 고객이 앱을
삭제하지 않고 업데이트할 수 있도록 구성되어 있습니다.

최초 배포 전에 저장소에 다음 GitHub Actions Secrets를 한 번 설정해야 합니다.

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

release keystore 파일과 비밀번호는 저장소에 커밋하지 마세요. 같은 앱의 업데이트를 계속
배포하려면 최초 고객 배포에 사용한 signing key를 계속 보관하고 사용해야 합니다.
자세한 절차는 `RELEASE_SIGNING.md`를 참고하세요.

## 알려진 제약

- 자동 싱크는 영상 앱이 Android MediaSession에 유효한 재생 위치를 공개할 때만 동작합니다.
  공개하지 않는 앱이나 기기에서는 수동 타이머를 사용합니다.
- 실제 영상 재생 속도 변경과 Study mode 반복 재생은 영상 앱이 MediaSession에서 해당
  제어를 허용하는 경우에만 동작합니다.
- Android 보안 정책상 저장소의 모든 SRT를 무단 검색하지 않습니다. 사용자가 시스템 파일
  선택기에서 한 번 선택한 SRT만 기억하고 다시 사용합니다.
- Android 12 이상에서는 영상 앱이 보안상 제3자 오버레이를 차단할 수 있습니다.
- 제조사 절전 정책이 Foreground Service 동작에 영향을 줄 수 있습니다.
