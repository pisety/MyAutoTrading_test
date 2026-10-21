# py-my-auto-trading (Python 자동매매 백엔드 엔진)

본 프로젝트는 주식(한국투자증권, 메리츠증권, 키움증권)과 가상화폐(업비트)의 실시간 가격을 모니터링하고, 사용자가 설정한 조건에 따라 자동으로 매매를 수행하는 Python 기반 백엔드 엔진입니다.
Firebase Realtime Database와 연동되어 안드로이드 앱 클라이언트의 명령을 실시간으로 수신받아 동작합니다.

## 🚀 주요 기능

- ⚡ **SOXL / SOXS 양방향 퀀트 스윙 매매 엔진 (`dual_momentum_engine.py`, `dual_momentum_controller.py`):**
  - 미국 필라델피아 반도체 3배 레버리지(`SOXL`)와 인버스 3배(`SOXS`) 중 1개만 상호배타적으로 집중 보유 운용
  - 거시 국면(전일 `SOXX > 200MA` 시 상승장/하락장 판정) 및 RSI 역추세 함정 방어 필터 적용
  - Condition 1 / Condition 2 4단계 분할 매수 및 잔여 현금 비례 사이징
  - 순수 평가손실 `-5.0%` 이하 2분봉 연속 지속 시 손절 (-6.5% 즉시 안전핀 손절)
  - 목표가 도달 시 50% 분할 익절 및 0.7% 트레일링 갭 추종 익절
- 🏛️ **멀티 브로커 비동기(asyncio) 연동 (`brokers/`):**
  - **한국투자증권 (`kis_broker.py`):** 국내/해외 주식 REST 주문, 잔고조회, 웹소켓 실시간 체결가 구독, Access Token 파일 캐싱
  - **메리츠증권 (`meritz_broker.py`):** JWT `exp` 클레임 기반 토큰 만료 사전 감지 및 자동 재발급, 국내/해외(미국 나스닥/NYSE/AMEX) 주식 매수/매도/취소/체결조회, 외화/원화 예수금 조회, 웹소켓 실시간 체결가 구독
  - **키움증권 (`kiwoom_broker.py`):** 실전/모의 자동 분기 REST API 주문 및 잔고조회
  - **업비트 (`upbit_broker.py`):** 가상화폐 실시간 웹소켓 시세 수신 및 시장가/지정가 주문
- 👥 **다중 사용자 세션 관리 (`user_manager.py`):**
  - Firebase `users` 노드를 감시하여 등록된 모든 사용자/가족의 주식 및 코인 `UserSession`을 24시간 독립 병렬 구동
  - 하위 경로 삭제 시 세션 보호 및 삭제 계정 영구 퍼지(Purge) 동기화
- 🌅 **장전 시간외 매수 주문예약 스케줄러 (`reservation_manager.py`):**
  - 08:19:50 Pre-fetch로 토큰 갱신 및 데이터 로드 후, 08:20:00.020 정각에 비동기 병렬 발주하여 매수 1순위 대기열 선점 (한국투자 및 메리츠 지원)
  - 08:00 미국 본장 마감 시황 분석 스케줄러: ETF 구성 종목의 밤사이 등락률 산술 평균 후 기준치 미달 시 자동 취소
- 🤖 **다중 급등 감시 및 자동매매:** 초 단위 가격 변화율(N초 내 M%) 감지 시 시장가/지정가 즉시 매수 (60초 쿨다운 적용)
- ♾️ **라오어 무한매수 실계좌 동기화:** 증권사 계좌 실잔고와 매개 변수를 1:1로 조회·동기화하여 평단가 및 보유수량의 정확도 보장
- 📊 **ETF 구성 종목 분석 (`etf_analyzer.py`):** 미국 및 국내 대표 ETF의 구성 종목 및 비중 정보 크롤링 및 분석
- 📱 **실시간 FCM 푸시 알림:** 주문 체결 및 손절매, 급등주 감지 시 사용자별 FCM 토큰으로 푸시 전송

## 🛠 기술 스택

- **언어:** Python 3.10+
- **비동기 처리:** `asyncio`, `aiohttp`, `websockets`
- **외부 API:** 한국투자증권 OpenAPI, 메리츠증권 Open API, 키움증권 REST API, Upbit API
- **데이터베이스:** Firebase Realtime Database (`firebase-admin`)
- **데이터 분석:** `pandas`, `numpy`, `FinanceDataReader`

## 🏦 지원 증권사 / 거래소

| 증권사/거래소 | 자산 유형 | 실전/모의 | API 방식 |
|---|---|---|---|
| **한국투자증권 (KIS)** | 국내주식, 해외주식 | ✅ 실전 + 모의 | REST + WebSocket |
| **메리츠증권** | 국내주식, 해외주식 | ✅ 실전 | REST + WebSocket |
| **키움증권** | 국내주식 | ✅ 실전 + 모의 | REST + WebSocket |
| **업비트 (Upbit)** | 가상화폐 | ✅ 실전 | REST + WebSocket |

## ⚙️ 설치 및 실행 방법

1. **저장소 클론:**
   ```bash
   git clone <repository-url>
   cd py-my-auto-trading
   ```
2. **패키지 설치:**
   ```bash
   pip install -r requirements.txt
   ```
3. **환경 설정 (보안 파일):**
   - API 키 설정: `keys.json` 파일을 생성하여 거래소/증권사 API 키를 입력합니다. (템플릿: `keys_template.json` 참고)
   - Firebase Admin SDK 설정: `database/` 경로에 `serviceAccountKey.json`을 배치합니다.
   - 환경변수: `.env` 파일에 `AES_SECRET_KEY` 등을 설정합니다.
4. **엔진 실행:**
   ```bash
   python my-auto-trading.py
   ```
5. **서버 배포 (Oracle Cloud 등):**
   ```bash
   deploy.bat
   ```

## ⚠️ 보안 주의 사항
- `keys.json`, `serviceAccountKey.json`, `.env`, 클라우드 SSH 키 등은 절대 공개 저장소에 커밋하지 마세요. (본 저장소의 `.gitignore`에 추가되어 있습니다.)
