# 톡톡 (toktok)

오픈소스 BYOK 음성 입력 앱 — Wispr Flow 클론 (Android)

키보드 위에 떠 있는 플로팅 버튼으로 말하면 글로 바꿔주는 앱입니다.
STT와 문장 다듬기(윤문) 모두 OpenRouter를 사용하며, 본인의 API 키를 직접 넣는 BYOK 방식입니다.

## 주요 기능

- **플로팅 버튼**: 키보드 상단에 떠 있는 버튼. 드래그로 위치 이동 가능
  - **꾹 누르기**: 누르고 있는 동안 녹음 → 놓으면 STT → 윤문 → 입력
  - **짧게 누르기**: 녹음 모드 진입 → 정지 버튼으로 종료
- **STT + 윤문**: OpenRouter 경유 (모델 직접 지정 가능)
  - STT 기본값: `openai/whisper-large-v3`
  - 윤문 기본값: `deepseek/deepseek-v4.1-flash`
  - 선호 프로바이더 지정 가능 (예: `Groq` — 속도 우선 라우팅)
  - 윤문 건너뛰기 토글 (STT 결과 바로 입력)
- **텍스트 삽입**: AccessibilityService로 현재 커서 위치에 삽입 (실패 시 클립보드 폴백)
- **처리 중 취소**: STT/윤문이 느릴 때 취소 버튼으로 즉시 중단

## 요구 사항

- Android 8.0 (API 26) 이상
- OpenRouter API 키 ([openrouter.ai](https://openrouter.ai))

## 설정

1. 앱 설치 후 실행
2. OpenRouter API 키 입력
3. 필요 시 STT/윤문 모델 변경
4. 오버레이 권한 + 접근성 서비스 활성화
5. "플로팅 버튼 시작"

## 빌드

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
./gradlew :app:assembleDebug
```

## 라이선스

MIT
