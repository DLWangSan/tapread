# 点读 TapRead

面向长辈与阅读困难用户的 Android 全局文字朗读工具：复制文字后朗读，无需 OCR。

> Tap anywhere you can copy — TapRead speaks it aloud.

## 两种模式

| 模式 | 用法 | 权限 |
|------|------|------|
| **悬浮球（默认推荐）** | 任意 App 中复制文字 → 点悬浮球 → 朗读 | 显示在其他应用上层 + 通知 |
| **无障碍复制即读** | 复制后自动朗读 | 无障碍服务 |

两种模式共用系统 TTS，剪贴板内容仅本机朗读，不会上传。

## 功能

- 复制后点悬浮球朗读；朗读中再点可停止
- 悬浮球可拖动
- 可选无障碍「复制即读」
- 自动跳过过短文本与简单验证码数字
- 语速调节、试听

## 快速开始

1. 用 Android Studio 打开本仓库
2. 连接手机或模拟器（Android 8.0+ / API 26+）
3. Run `app`

或命令行：

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

1. 打开「点读」
2. 授予「显示在其他应用上层」
3. 点击「开启悬浮球」
4. 去微信等 App 长按复制文字，再点绿色喇叭

若要「复制即读」：

1. 打开系统无障碍设置，启用「点读」
2. 回到 App 打开「启用复制即读」

## 技术说明

- Kotlin + Material 3
- `TextToSpeech` 系统朗读
- `SYSTEM_ALERT_WINDOW` 悬浮球
- `AccessibilityService` 在 Android 10+ 下可靠读取剪贴板并自动朗读
- 不做 OCR、不读取屏幕控件内容

## 版本规划

- **v0.1** 悬浮球 + 无障碍复制即读 + TTS（当前）
- **v0.2** 朗读历史、忽略名单、更多无障碍启发式
- 不做 OCR（按产品选择）

## License

MIT
