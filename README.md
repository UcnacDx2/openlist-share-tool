# OpenList 快传

Android 原生分享/打开文件 → OpenList 上传 → 获取直链 → 自动复制/再次分享。

## 当前功能
- OpenList 地址 + Authorization Token 本地保存
- Android 原生「分享」接收文件 URI
- Android「用其他应用打开/打开方式」接收 `content://` / `file://`
- 可配置目标目录、覆盖同名文件
- 流式 PUT `/api/fs/put` 上传
- 上传完成后 POST `/api/fs/get` 获取 `raw_url` / `url`
- 自动把常见 `/p/` 直链转换为 `/d/`
- 一键复制直链、系统分享直链
- 不需要文件读写权限，只使用系统传入的 URI

## 构建
需要 Android SDK 35、Build Tools、Gradle 8.9+ 与 AGP 8.7.3。

## 一键 CI 构建
项目带有 `.github/workflows/build-apk.yml`。推送到 GitHub 后，在 Actions 中手动运行 `Build APK`，构建出的 `app-debug.apk` 已由 Android 调试密钥签名，可直接安装测试；构建产物会作为 Artifact 提供下载。
