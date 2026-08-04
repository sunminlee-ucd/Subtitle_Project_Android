# Subtitle Overlay Android

로컬 SRT 파일을 Netflix, YouTube 등 다른 Android 앱 위에 표시하는 오프라인 자막
오버레이 MVP입니다. 번역, 계정, 서버, 네트워크 기능은 포함하지 않습니다.

## MVP 기능

- Android 파일 선택기로 로컬 `.srt` 열기
- `TYPE_APPLICATION_OVERLAY` 투명 자막 표시
- Foreground Service를 통한 앱 전환 중 타이머 유지
- 재생/일시정지, 앞뒤 5초 이동
- 0.5초 단위 자막 싱크 보정
- `A−` / `A+` 자막 크기 조절과 설정 자동 저장
- Netflix 기본 자막 영역에 가까운 하단 기본 위치
- `Pos ↓` / `Pos ↑` 위치 조절, `Reset` 기본 위치 복원
- Netflix/YouTube가 공개하는 MediaSession 재생 위치 자동 감지
- 자동 위치를 제공하지 않는 기기를 위한 수동 타이머 fallback
- 작고 깔끔한 3단 플로팅 컨트롤러와 드래그 이동
- 영상 시청 중 컨트롤 완전 숨김, 알림의 **Controls**로 복원
- 최근 선택한 SRT 자동 재사용
- 자막 텍스트 폭에 맞춘 반투명 배경
- SRT 내용은 기기 내부에서만 처리

## 개발 환경

- Android Studio Quail 2 이상 권장
- Android Gradle Plugin 9.3.0
- Gradle Wrapper 9.5.0
- JDK 17
- compileSdk / targetSdk 37
- minSdk 26 (Android 8.0)

Android Studio에서 이 폴더를 열고 SDK 37을 설치한 뒤 Gradle Sync를 실행하세요.
프로젝트에 포함된 Gradle Wrapper가 빌드에 필요한 Gradle 9.5.0을 자동으로 사용합니다.

## 실행 방법

1. Android Studio에서 `SubtitleOverlayAndroid` 폴더를 엽니다.
2. 실제 Android 기기를 연결하고 `app`을 실행합니다.
3. **다른 앱 위에 표시 권한 허용**을 누르고 앱 권한을 켭니다.
4. 자동 싱크를 사용하려면 **Enable automatic playback sync**를 누르고
   `Subtitle Overlay`의 알림 접근 권한을 켭니다.
5. 번역된 SRT 파일을 선택합니다. 선택한 파일은 다음 실행부터 자동으로 다시 불러옵니다.
6. **Start subtitle overlay**를 누른 뒤 Netflix 또는 YouTube를 엽니다.
7. 플레이어가 재생 위치를 공개하면 컨트롤에 `AUTO · NETFLIX` 또는 `AUTO · YOUTUBE`가
   표시됩니다. `MANUAL`이면 영상 시작 시점에 맞춰 재생 버튼을 누릅니다.

영상 없이 기능만 확인하려면 `sample_subtitles.srt`를 휴대폰으로 복사해 선택하세요.
재생 버튼을 누르면 1초 후부터 테스트 자막이 순서대로 표시됩니다.

`Δ`는 자막 싱크 오프셋입니다. `+0.5`는 자막 타이밍을 0.5초 늦추고, `-0.5`는
0.5초 앞당깁니다.

**Hide**는 컨트롤을 완전히 숨깁니다. Android 알림창의 Subtitle Overlay 알림에서
**Controls**를 누르면 다시 표시됩니다.

작은 `a`와 큰 `A`는 자막 크기를 14sp부터 36sp까지 2sp 단위로 조절합니다. 마지막 크기는
자동으로 저장되어 다음 실행에도 유지됩니다.

`↓`는 자막을 화면 아래쪽으로, `↑`는 위쪽으로 12dp씩 이동합니다. `↺`는
Netflix 기본 자막 영역에 가까운 하단 24dp 위치로 되돌립니다. 마지막 위치는 자동으로
저장됩니다.

## 알려진 제약

- 자동 싱크는 영상 앱이 Android MediaSession에 유효한 재생 위치를 공개할 때만 동작합니다.
  공개하지 않는 앱이나 기기에서는 수동 타이머를 사용합니다.
- Android 보안 정책상 저장소의 모든 SRT를 무단 검색하지 않습니다. 사용자가 시스템 파일
  선택기에서 한 번 선택한 SRT만 기억하고 다시 사용합니다.
- Android 12 이상에서는 영상 앱이 보안상 제3자 오버레이를 차단할 수 있습니다.
- 제조사 절전 정책이 Foreground Service 동작에 영향을 줄 수 있습니다.
