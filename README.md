# TapRead · 点读

<p align="center">
  <strong>English</strong> | <a href="./README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/DLWangSan/tapread/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/DLWangSan/tapread?style=flat-square"></a>
  <a href="./LICENSE"><img alt="License" src="https://img.shields.io/github/license/DLWangSan/tapread?style=flat-square"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%208%2B-green?style=flat-square">
  <img alt="API" src="https://img.shields.io/badge/minSdk-26-blue?style=flat-square">
</p>

<p align="center">
  <b>Tap anywhere you can copy — TapRead speaks it aloud.</b><br/>
  A lightweight Android reader for elders and anyone who finds on-screen text hard to read.
  No OCR. Uses the system Text-to-Speech engine.
</p>

<p align="center">
  <img src="./images/screenshot-home.jpg" alt="TapRead home" width="280">
  &nbsp;
  <img src="./images/screenshot-voice.jpg" alt="TapRead voice settings" width="280">
</p>

## Why TapRead

Android 10+ blocks background apps from reading the clipboard. Care Mode in WeChat can read chats but not Moments. TapRead keeps one simple habit:

> If you cannot read it — copy it, then listen.

## Features

- **Android 9 and below** — copy → auto read
- **Android 10+** — copy → tap the floating ball, or enable Accessibility for auto read
- System TTS with speech rate / pitch and a shortcut into system voice settings
- Skips very short text and simple verification-code patterns
- Clipboard text stays on device — never uploaded

## Modes

| Android version | Recommended mode |
| --- | --- |
| ≤ 9 | Clipboard monitor — copy and listen |
| ≥ 10 | Floating ball (default) + optional Accessibility auto-read |

## Install

1. Download the latest APK from [Releases](https://github.com/DLWangSan/tapread/releases/latest)
2. Install on Android 8.0+ (API 26+)
3. Grant **Display over other apps**, then start the floating ball
4. (Optional) Enable the **TapRead** Accessibility service for copy-to-read

> After updating Accessibility-related builds, turn the TapRead service **off and on again** in system settings.

## Quick start

1. Copy text in WeChat / browser / any app  
2. Tap the green speaker ball → hear it  
3. Tap again while speaking to stop  

## Build

```bash
./gradlew :app:assembleRelease
# or debug build
./gradlew :app:assembleDebug
```

Requirements: JDK 17+, Android SDK 35.

## Privacy

- Clipboard content is used only for on-device speech
- No account, no analytics SDK, no network upload of copied text
- Accessibility mode only watches copy-related events / clipboard changes

## Star History

> **Note (2026):** GitHub restricted the public stargazers API to repository admins/collaborators.
> Live `api.star-history.com` embeds often render blank. This repo refreshes a static chart
> via [star-history-action](https://github.com/narayann7/star-history-action) with the repo’s own token.

<!-- star-history:start -->
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/star-history/star-history-dark.svg">
  <img alt="Star history" src="assets/star-history/star-history-light.svg">
</picture>
<!-- star-history:end -->

## License

[MIT](./LICENSE) © DLWangSan
