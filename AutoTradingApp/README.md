# Auto Trading App (안드로이드 클라이언트)

본 프로젝트는 주식 및 가상화폐(코인) 자동매매를 모니터링하고 제어하기 위한 안드로이드 애플리케이션입니다.
Firebase Realtime Database를 통해 Python 기반의 백엔드 트레이딩 엔진과 실시간으로 연동됩니다.

## 🚀 주요 기능

- **실시간 잔고 및 수익률 모니터링:** 주식(한국투자증권)과 코인(업비트)의 현재 보유 자산, 실시간 가격, 수익률을 한눈에 확인할 수 있습니다.
- **수동 매수/매도 주문:** 앱 내에서 지정가 또는 시장가(지정가 0원 입력 시)로 즉시 주문을 실행할 수 있습니다.
- **자동매매 제어:** 전체 자동매매 기능을 ON/OFF 할 수 있습니다.
- **급등 코인/주식 감시 설정:** 특정 종목에 대해 '시간(초)', '급등 퍼센트(%)', '매수 수량' 조건을 다중으로 설정하여, 조건 충족 시 자동으로 매수되도록 엔진에 명령을 전달합니다. 다중 감시 설정 및 팝업 스크롤 기능을 지원합니다.

## 🛠 기술 스택

- **언어:** Kotlin
- **UI 프레임워크:** Jetpack Compose
- **아키텍처:** MVVM (ViewModel, StateFlow)
- **백엔드 연동:** Firebase Realtime Database
- **비동기 처리:** Kotlin Coroutines

## ⚙️ 설치 및 빌드 방법

1. **저장소 클론:**
   ```bash
   git clone <repository-url>
   ```
2. **Firebase 연동:**
   - Firebase Console에서 안드로이드 프로젝트를 생성합니다.
   - `google-services.json` 파일을 다운로드하여 `app/` 디렉터리 내에 배치합니다. (보안상 GitHub에는 `google-services_template.json`만 제공됩니다.)
3. **빌드:**
   - Android Studio에서 프로젝트를 엽니다.
   - 상단 메뉴에서 `Build > Build Bundle(s) / APK(s) > Build APK(s)`를 선택하거나 아래 명령어로 빌드합니다.
   ```bash
   ./gradlew assembleDebug
   ```

## ⚠️ 보안 주의 사항
- `app/google-services.json` 파일은 Firebase 접속 정보를 담고 있으므로 절대 공개 저장소에 커밋하지 마세요. (본 저장소의 `.gitignore`에 추가되어 있습니다.)
