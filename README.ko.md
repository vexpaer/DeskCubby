<div align="center">

<img src="https://raw.githubusercontent.com/vexpaer/DeskCubby/main/.github/logo.png" width="96" alt="DeskCubby" />

# DeskCubby

**Find. File. Forever.**

Android와 Windows를 위한 로컬 우선(Local-first) 일기·지식 관리 앱. 당신의 일기, 메모, 생각과 기록은
모두 당신의 파일 속에 저장됩니다 — 계정도, 클라우드도 필요 없습니다.

<p>
  <a href="https://github.com/vexpaer/DeskCubby/releases/latest">
    <img src="https://img.shields.io/badge/Download%20DeskCubby-%EC%B5%9C%EC%8B%A0%20%EB%B0%B0%ED%8F%AC%ED%8C%90%20%EB%8B%A4%EC%9A%B4%EB%A1%9C%EB%93%9C-4f46e5?style=for-the-badge&logo=github" alt="DeskCubby 다운로드" />
  </a>
</p>

[![최신 릴리스](https://img.shields.io/github/v/release/vexpaer/DeskCubby?display_name=release&style=flat-square&label=%EC%B5%9C%EC%8B%A0%20%EB%A6%B4%EB%A6%AC%EC%8A%A4&color=4f46e5)](https://github.com/vexpaer/DeskCubby/releases/latest)
[![다운로드](https://img.shields.io/github/downloads/vexpaer/DeskCubby/total?style=flat-square&label=Downloads&color=4f46e5)](https://github.com/vexpaer/DeskCubby/releases)
[![플랫폼](https://img.shields.io/badge/platform-Android%208%2B%20%7C%20Windows%2010%2F11-4f46e5?style=flat-square)](https://github.com/vexpaer/DeskCubby/releases/latest)
[![라이선스](https://img.shields.io/github/license/vexpaer/DeskCubby?style=flat-square&color=4f46e5)](LICENSE)
[![Stars](https://img.shields.io/github/stars/vexpaer/DeskCubby?style=flat-square&color=4f46e5)](https://github.com/vexpaer/DeskCubby)

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · **한국어** · [日本語](README.ja.md)

</div>

---

## DeskCubby란 무엇인가요?

DeskCubby는 Android와 Windows용 **로컬 우선** 일기·개인 지식 베이스 앱입니다.

- 일기, 메모, 미디어는 **실제 파일**입니다 — 직접 고른 폴더의 일반 Markdown 파일이죠.
- 생각, 날짜, 시, 기록과 진행 상황은 기기 내 데이터베이스에 저장됩니다.
- 완전히 오프라인으로 작동합니다. 백업이나 여러 기기 동기화가 필요할 때만 WebDAV / S3 동기화를 선택할 수 있습니다.

## 주요 기능

| | |
| --- | --- |
| **Markdown 일기** | 템플릿, 식사 달력, 사진 기록과 작성 통계를 갖춘 일일 일기 — 어디서나 열 수 있는 일반 `.md` 파일로 저장됩니다. |
| **노트 라이브러리** | 직접 고른 폴더에 정리하는 Obsidian 호환 Markdown 노트. 미리보기, 링크, 미디어 지원. |
| **TXT / PDF 리더** | 책을 가져오고, 멈춘 지점에서 정확히 이어 읽고, 전체 텍스트 검색과 목차 이동을 지원합니다. |
| **AI 에이전트** | OpenAI 호환 모델과 대화하세요. 에이전트는 네이티브 도구 호출로 *당신의* 기록을 검색·읽고, 모든 변경 전에 승인을 요청하며, 실행 취소 가능한 Review를 남깁니다. |
| **기록 키트** | 빠른 생각, 중요한 날짜, 일상 기록, 시 모음, RSS 구독, 비밀번호 보호 보관함, 그리고 여덟 가지 미니 게임. |
| **홈 화면 위젯** | 일기·통계·게임·클라우드 동기화용 크기 조절 가능한 Android 위젯 — 레이아웃과 색상은 당신의 선택입니다. |
| **취향대로** | 세 가지 비주얼 스타일(Material, Liquid Glass, Organic Future), 라이트/다크 모드, 커스텀 테마, 한·영 이중 언어 UI. |
| **설계부터 프라이버시** | 계정 불필요, 텔레메트리 없음, 강제 클라우드 없음. 파일은 당신의 폴더에 남고, 동기화는 언제나 선택 사항입니다. |

## 설치 및 사용

| 플랫폼 | 방법 |
| --- | --- |
| **Android** | [Releases](https://github.com/vexpaer/DeskCubby/releases/latest)에서 `DeskCubby.apk`(Android 8.0+)를 다운로드해 설치하세요. 안내가 나오면 "출처를 알 수 없는 앱 설치"를 허용합니다. 앱 내 업데이트 확인으로 최신 버전을 유지하세요. |
| **Windows** | [Releases](https://github.com/vexpaer/DeskCubby/releases/latest)에서 설치형 또는 포터블 빌드를 다운로드하세요(Windows 10/11 x64). |
| **첫 실행** | 일기 폴더를 고르고, 첫 일기를 쓰고, 생각을 기록하고, 책을 가져오세요. 모든 기록은 당신이 소유한 실제 Markdown 파일입니다. |

> 플랫폼 참고: 내장 브라우저와 홈 화면 위젯은 Android 전용입니다. Windows는 핵심 경험을 동일하게 제공하며, 휴대폰 사용 시간 / 건강 데이터를 보여주기만 하고 수집하지 않습니다.

## 문서

- [TUTORIAL.md](TUTORIAL.md) — 단계별 사용 가이드(앱 내 정보 페이지에서도 연결)
- [overview.md](overview.md) — 아키텍처 및 데이터 흐름

## 라이선스

[MIT](LICENSE) © DeskCubby contributors
