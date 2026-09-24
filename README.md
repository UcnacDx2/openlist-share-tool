# OpenList 快传

Android 原生分享/打开文件 → OpenList 上传 → 获取 OpenList 的 `/d/...` 下载入口。

## 功能

- 从系统「分享」或「打开方式」接收单个/多个文件
- 上传任务运行在 Android 前台服务中，退出应用后仍可在通知栏查看整体进度与当前文件进度
- 上传完成后，通知栏提供「复制直链」操作
- 应用内永久显示最近一次 OpenList 直链，可手动复制或再次分享
- 默认同名文件不覆盖：自动生成 `文件名 (yyyy-MM-dd HH-mm-ss).ext`
- 同一秒再次冲突时追加 `#2`、`#3`……
- 「直接覆盖」可手动开启
- 直链使用 OpenList 的 `/d/...` 下载入口，不使用上游网盘 `raw_url`
- 若 OpenList 返回签名，会自动追加 `?sign=...`

## 为什么自己处理重名

OpenList 的 `Overwrite: false` API 行为是发现目标文件存在后拒绝上传；OpenList 存储层的 `NoOverwriteUpload` 机制主要用于临时改名旧对象并在新文件成功后删除旧对象，并不是「保留两个同名文件」。

因此本项目在真正上传前检查目录，仅在冲突时给新文件增加人类可读时间戳。

## 直链与有效期

应用复制的是 OpenList 自己的 `/d/...` 下载入口。对非代理下载场景，OpenList 的该入口会向上游链接返回 HTTP 302；因此复制的不是易过期的上游网盘 URL。

OpenList 的签名有效期由服务端 `link_expiration`（小时）控制。普通文件要带签名，需要 OpenList 开启 `sign_all`；应用会优先使用 API 返回的 `sign` 参数。服务端没有返回签名时，应用仍使用 OpenList `/d/...` 入口，但该链接不会因为本应用而强制产生过期时间。

## 构建

需要 Android SDK 35、Java 17、Gradle 8.9、AGP 8.7.3。

GitHub Actions 会自动构建 `app-debug.apk`。
