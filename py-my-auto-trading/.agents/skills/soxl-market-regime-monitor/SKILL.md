---
name: soxl-market-regime-monitor
description: >-
  Diagnoses and detects the 4 early-warning anomaly signals (ATR daily amplitude drought, 200MA whipsaw zone, RSI momentum regime, and liquidity spread) for SOXL/SOXS dual momentum trading. Use whenever the user asks to check market regime health, detect anomalies, evaluate if current market conditions suit the quant swing engine, or verify trading profitability conditions.
---

# 🚦 SOXL/SOXS 퀀트스윙 4대 이상 징후 및 시장 적합도(Regime) 진단 스킬

본 스킬은 3배 레버리지 퀀트스윙 엔진(v2.1)의 수익률을 훼손할 수 있는 **4대 시장 이상 징후(진폭 가뭄, 200MA 휩소 함정, RSI 과열, 유동성 위축)**를 사전에 정량적으로 감지하여 시장 적합도(Regime Health)를 판정합니다.

---

## 1. 실행 방법

사용자가 *"시장 상태 점검해줘"*, *"이상 징후 있는지 확인해줘"*, *"지금 우리 알고리즘 돌리기 좋은 시장이야?"*라고 묻거나 정기 점검 시 아래 스크립트를 즉시 실행합니다:

```powershell
python .agents/skills/soxl-market-regime-monitor/scripts/check_regime.py
```

---

## 2. 4대 이상 징후 판정 기준 및 임계치

| 이상 징후 항목 | 정량 측정 지표 | 🟢 정상 (Green) | 🟡 주의 (Yellow) | 🔴 경보 (Red) |
| :--- | :--- | :---: | :---: | :---: |
| **1. 일중 진폭 가뭄** | SOXL 20일 ATR(Average True Range) % | **≥ 5.0%**<br/>(스윙 파동 충분) | **3.5% ~ 5.0%**<br/>(진폭 축소 조짐) | **< 3.5%**<br/>(진폭 가뭄, 목표 도달 불가) |
| **2. 200MA 휩소 함정** | SOXX 지수와 200MA 이격도 및 30일 교차 수 | **이격도 ≥ 3.0%**<br/>(교차 ≤ 1회) | **이격도 1.5%~3.0%**<br/>(교차 ≤ 2회) | **이격도 < 1.5%**<br/>(교차 ≥ 3회, 톱니바퀴) |
| **3. RSI 모멘텀 국면** | SOXX 일봉 14일 RSI | **< 37 (과매도 기회)**<br/>또는 37~65 (안정) | **> 65 (과열)**<br/>추격매수 차단 | **> 75 (극단적 과열)**<br/>급락 위험 |
| **4. 유동성 및 슬리피지** | SOXL 5일 평균 일거래대금 | **> $500M**<br/>(호가 스프레드 극소) | **$200M ~ $500M** | **< $200M**<br/>(슬리피지 확대 위험) |

---

## 3. 최종 신호등 판정 및 권고 행동 지침

* 🟢 **GREEN (최적 가동 - Optimal Regime)**:
  * 조건: 4개 지표 모두 정상.
  * 권고: **예산 100% 정상 가동**. 공격적인 눌림목 스윙으로 알파 극대화.
* 🟡 **YELLOW (주의 - Caution Regime)**:
  * 조건: 2개 이상의 지표가 '주의'이거나 200MA 경계선 부근.
  * 권고: **예산 50% 축소 운용**. 포지션 진입 시 목표 익절선 및 손절선 타이트하게 관리.
* 🔴 **RED (비적합 - Adverse Regime)**:
  * 조건: 1개 이상의 지표가 '경보(진폭 가뭄, 잦은 휩소)' 발생.
  * 권고: **신규 매매 일시 중단 및 100% 현금 관망**. 박스권 횡보가 끝나고 변동성이 다시 살아날 때까지 대기.
