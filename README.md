<div align="center">

<img src="https://raw.githubusercontent.com/vexpaer/DeskCubby/main/.github/logo.png" width="96" alt="DeskCubby" />

# DeskCubby

**Find. File. Forever.**

A local-first journaling and knowledge app for Android and Windows. Your diaries, notes,
thoughts and records live in your own files — no account, no cloud required.

<p>
  <a href="https://github.com/vexpaer/DeskCubby/releases/latest">
    <img src="https://img.shields.io/badge/Download%20DeskCubby-Latest%20Release-4f46e5?style=for-the-badge&logo=github" alt="Download DeskCubby" />
  </a>
</p>

[![Latest Release](https://img.shields.io/github/v/release/vexpaer/DeskCubby?display_name=release&style=flat-square&label=Latest%20Release&color=4f46e5)](https://github.com/vexpaer/DeskCubby/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/vexpaer/DeskCubby/total?style=flat-square&label=Downloads&color=4f46e5)](https://github.com/vexpaer/DeskCubby/releases)
[![Platform](https://img.shields.io/badge/platform-Android%208%2B%20%7C%20Windows%2010%2F11-4f46e5?style=flat-square)](https://github.com/vexpaer/DeskCubby/releases/latest)
[![License](https://img.shields.io/github/license/vexpaer/DeskCubby?style=flat-square&color=4f46e5)](LICENSE)
[![Stars](https://img.shields.io/github/stars/vexpaer/DeskCubby?style=flat-square&color=4f46e5)](https://github.com/vexpaer/DeskCubby)

**English** · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · [한국어](README.ko.md) · [日本語](README.ja.md)

</div>

---

## What is DeskCubby?

DeskCubby is a **local-first** journal and personal knowledge base for Android and Windows.

- Diaries, notes and media are **real files** — plain Markdown in folders you choose.
- Thoughts, dates, poems, records and progress live in an on-device database.
- Works fully offline. Optional WebDAV / S3 sync, only when you want backups or another device.

## Core features

| | |
| --- | --- |
| **Desk** | Your personal digital desk — today's diary, ideas, photos, events and traces laid out spatially as an editorial page instead of a list. It reuses your real data and routes every tap into the existing editor and pages. |
| **Markdown journals** | Daily diaries with templates, a meal calendar, photo records and writing statistics — stored as plain `.md` files you can open anywhere. Meal capture becomes ready again as soon as the photo and diary entry are safely written; optional AI estimation continues without holding the capture UI busy. |
| **Note library** | Obsidian-compatible Markdown notes organized in your own folders, with previews, links and media. |
| **TXT / PDF reader** | Import books, resume exactly where you stopped, search the full text and jump via the table of contents; PDF pinch-zoom renders beyond 100%, supports single-finger free 2D panning, stays centered after shrinking and saves your zoom. Missing provider thumbnails safely fall back to a bounded first-page cover, whose displayed text can be edited or hidden. |
| **AI Agent** | Chat with any OpenAI-compatible model. Tool-capable models can search and change *your* records with approval and an undoable review; older non-tool configurations still work as ordinary chat. Runs use a durable per-task WorkManager queue, survive page/process recreation, and do not block meal-image AI work. |
| **Capture kit** | Quick thoughts, important dates, structured records, poems, RSS feeds, a password-protected vault and eight mini-games. |
| **Structured records** | Replace plain daily templates with typed field values (word / number / type / time / duration) embedded in your Markdown diary via stable hidden comments. Home/widgets write to the real local date; the diary-editor entry writes to the diary currently open. Single-file Room projections update immediately after writes and edits, while a rebuildable `.deskcubby/` index powers statistics and optional on-device wake/sleep estimation. |
| **Home-screen widgets** | Android cards with rounded or square corners, a consistent adjustable card scale across every 1×1/2×1/1×2/2×2/long layout, fixed-size adjustable app icons, full-bleed music visuals, screen-time charts, meal capture, quick thoughts, structured-record entry, desktop games, reader progress and cloud sync — your layout, your colors. |
| **Optional cloud sync** | Sync selected content through WebDAV or S3. Choosing thoughts or poems automatically includes their categories, so relationships remain intact without separate category switches. |
| **Make it yours** | Three visual styles (Material, Liquid Glass, Organic Future), light/dark mode, custom themes and a five-language UI (Simplified/Traditional Chinese, English, Korean, Japanese) chosen on first launch. |
| **Workspace on tablets** | Adaptive landscape layout for Pads and big screens: a fixed left navigation rail, master–detail panes, and a context panel for diary, AI, and ideas. Reader contents and thought categories open flush beside the rail, while closed drawers stay entirely out of its way. |
| **Private by design** | No account, no telemetry, no mandatory cloud. Files stay in your folders; sync is always opt-in. |

## Install & use

| Platform | How |
| --- | --- |
| **Android** | Download `DeskCubby.apk` from [Releases](https://github.com/vexpaer/DeskCubby/releases/latest) (Android 8.0+) and install it, allowing “install unknown apps” when prompted. The in-app update check keeps you current. |
| **Windows** | Download the setup or portable build from [Releases](https://github.com/vexpaer/DeskCubby/releases/latest) (Windows 10/11 x64). |
| **Self-hosted Web** | Run the `web/` folder on your own server or NAS (`docker compose up` or Python + Node directly) and open it from any browser; see [web/README.md](web/README.md). Data stays in your server's data directory, with optional password protection. |
| **First run** | Pick a folder for your journals, write your first diary, add a thought, import a book. Every entry is a real Markdown file you own. |

> Platform notes: the built-in browser and home-screen widgets are Android-only. Windows mirrors the core experience and shows Phone Usage / Health data, but never collects it. The Web app replicates the full Android feature set for personal/self-hosted use; system-home widgets still require Android.

## Documentation

- [README_for_ai.md](README_for_ai.md) — step-by-step user guide (read inline from the in-app About page, and available to the Agent as the read-only "App guide" source)
- [overview.md](overview.md) — architecture and data flow
- [web/README.md](web/README.md) — self-hosted web app: local run, Docker deployment, reverse proxy & password setup
- [web/docs/CONVENTIONS.md](web/docs/CONVENTIONS.md) — web API contract, data-fidelity rules and file ownership

## License

[MIT](LICENSE) © DeskCubby contributors
