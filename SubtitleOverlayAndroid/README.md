# Subtitle Overlay Android

`Subtitle_Project`의 고객 계정과 연결되어, 관리자에게 접근 권한을 받은 자막만 Android에서
불러와 Netflix, YouTube 등 다른 앱 위에 표시하는 자막 오버레이 앱입니다.

## 핵심 MVP 흐름

1. 고객이 Subtitle Companion 고객 포털에서 자막을 요청합니다.
2. 관리자가 번역된 SRT를 private Supabase Storage에 저장합니다.
3. 관리자가 해당 `subtitle_track`에 고객의 `subtitle_grants` 접근 권한을 부여합니다.
4. 고객은 Android 앱에 같은 계정으로 로그인합니다.
5. 앱의 **My subtitles**에는 그 고객에게 허용된 자막만 표시됩니다.
6. 고객이 자막을 선택하고 **Start subtitle overlay**를 누르면 앱이 private SRT를 인증된 세션으로 읽어 overlay에 적용합니다.

고객용 UI에는 SRT 저장, 내보내기, 공유 기능이 없습니다. Supabase bucket은 private이며 서버의
RLS 정책이 고객별 접근 권한을 검사합니다.

> Android에서 자막을 화면에 표시하려면 앱 프로세스가 평문 자막 내용을 받아야 합니다.
> 따라서 이 구조는 일반적인 파일 다운로드/내보내기를 차단하고 서버 접근 권한을 강제하지만,
> rooted/compromised device에서 평문 추출을 암호학적으로 불가능하게 만드는 구조는 아닙니다.

## 고객 기능

- 고객 포털과 동일한 Supabase 이메일/비밀번호 로그인
- 고객별 authorized subtitle library
- private `subtitle-files` Storage에서 인증된 SRT 로드
- `TYPE_APPLICATION_OVERLAY` 자막 표시
- Foreground Service를 통한 앱 전환 중 오버레이 유지
- Netflix/YouTube MediaSession 재생 위치 자동 감지
- 자동 위치를 제공하지 않는 경우 수동 타이머 fallback
- 재생/일시정지, 앞뒤 5초 이동
- 재생 속도 조절
- 0.5초 단위 자막 싱크 보정
- 자막 크기/위치 조절
- 기능별 접기·펼치기 컨트롤
- Watch / Study mode
- Study cue 저장, 현재 cue 반복, 저장한 cue 순차 반복
- 앱 안에서 바로 고객 요청 포털 열기

## 자막 데이터 보안

- Supabase `subtitle-files` bucket은 public이 아닙니다.
- 앱은 publishable key와 로그인 사용자의 access token만 사용합니다.
- 고객이 허용되지 않은 `subtitle_track`이나 Storage object를 요청하면 Supabase RLS가 거부합니다.
- Service role/secret key는 APK에 포함하지 않습니다.
- Android backup은 비활성화되어 있습니다.
- 다운로드/공유/파일 선택 UI는 제공하지 않습니다.
- overlay를 시작할 때 기존 OverlayService를 재사용하기 위해 SRT를 앱의 private cache에 잠시 생성하고,
  non-exported `FileProvider`를 통해 같은 앱의 서비스에 전달한 뒤 삭제합니다.

## 개발 환경

- Android Studio Quail 2 이상 권장
- Android Gradle Plugin 9.3.0
- Gradle Wrapper 9.5.0
- JDK 17
- compileSdk / targetSdk 36
- minSdk 26 (Android 8.0)

Android Studio에서 `SubtitleOverlayAndroid` 폴더를 열고 SDK 36을 설치한 뒤 Gradle Sync를 실행하세요.

## 실행 방법

1. 앱을 실행합니다.
2. 고객 포털에서 만든 계정으로 로그인합니다.
3. **Allow display over other apps**에서 overlay 권한을 허용합니다.
4. 자동 싱크/Study 반복 기능이 필요하면 **Enable automatic playback sync**에서 알림 접근 권한을 허용합니다.
5. 관리자가 접근 권한을 준 자막이 **My subtitles**에 표시되는지 확인합니다.
6. 자막을 선택하고 **Start subtitle overlay**를 누릅니다.
7. Netflix 또는 YouTube에서 해당 영상을 재생합니다.
8. 플레이어가 MediaSession 위치를 공개하면 `AUTO · N`, `AUTO · Y` 또는 `AUTO`가 표시됩니다. 그렇지 않으면 `MANUAL` 모드로 사용할 수 있습니다.

자막이 아직 없다면 앱 상단의 **Request a subtitle**에서 고객 요청 페이지를 열 수 있습니다.

## Study mode

Study mode에서는 현재 표시 중인 자막을 눌러 학습 목록에 저장하거나 제거할 수 있습니다.
반복 횟수는 1~20회 범위에서 설정할 수 있으며, **Repeat current**와 **Play saved**로 반복 학습할 수 있습니다.

Android는 다른 앱의 `<video>` 객체에 직접 접근하지 않으므로 실제 seek/repeat는 대상 앱이
MediaSession에서 해당 제어를 허용할 때 가장 정확하게 동작합니다.

## CI

Pull Request와 `master` push에서 `.github/workflows/ci.yml`이 다음을 검사합니다.

- `./gradlew test`
- `./gradlew lint`
- `./gradlew assembleDebug`

## 고객 배포용 APK

고객에게는 일반 debug APK가 아니라 `.github/workflows/build-signed-release.yml`에서 생성한
**signed release APK**를 배포하세요.

최초 배포 전에 저장소에 다음 GitHub Actions Secrets가 필요합니다.

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

같은 앱의 업데이트를 계속 설치할 수 있도록 최초 배포에 사용한 signing key를 계속 보관해야 합니다.
자세한 절차는 `RELEASE_SIGNING.md`를 참고하세요.

## 알려진 제약

- 자동 싱크는 영상 앱이 Android MediaSession에 유효한 재생 위치를 공개할 때만 동작합니다.
- 재생 속도 변경과 Study 반복은 영상 앱이 해당 MediaSession 제어를 허용하는 경우에만 동작합니다.
- Android 12 이상에서는 대상 앱의 보안 정책에 따라 제3자 오버레이가 제한될 수 있습니다.
- 제조사별 절전 정책이 Foreground Service에 영향을 줄 수 있습니다.
- 앱에서 다운로드 UI를 제거해도, 자막을 렌더링하는 클라이언트가 평문을 전혀 보지 않게 만드는 것은 불가능합니다.
