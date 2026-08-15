# TV Shell

Android TV 极简 GeckoView 浏览器。不是通用浏览器，只做三件事：

1. 用自带 GeckoView 打开现代网页，不依赖系统 WebView
2. 遥控器全屏展示数据大屏
3. 同一局域网内手机扫码遥控电视

## 使用

```text
首次启动 → 输入网址 → 访问 → 全屏打开
再次启动 → 自动恢复上次成功打开的页面

首页：输入网址 / 手机遥控 / 设置
设置：语言（中/英）、开机网页、网页显示方式、遥控网卡、遥控密钥
手机遥控：二维码和局域网地址
浏览中按返回键 → 返回菜单（后退 / 前进 / 刷新，首页 / 设置 / 退出）；再按返回回到网页
浏览中方向键移动光标，上/下到顶或底再滚动；确定点击
```

遥控器：浏览时方向键移动光标；只有上/下到页面边缘才滚动，左右不滚页。确定点击光标位置。返回打开返回菜单。

手机扫电视上的二维码即可用浏览器遥控（推网址、滚动、刷新、打开菜单/设置）。遥控地址为 `http://<电视IP>:8765/?token=...`，控制接口必须带 token。

无协议的地址默认补 `http://`，允许局域网明文 HTTP，不忽略 HTTPS 证书错误。

## 要求

- Android TV 8.0+（API 26）
- 必须提供 `armeabi-v7a`（不少旧电视用户空间仍是 32 位）
- 同时输出 `arm64-v8a`
- 另打包 `x86` / `x86_64`，供模拟器测试
- 按 2GB RAM 级设备保持轻量：一个 GeckoRuntime、一个 Session、一个页面

## 构建

### Debug 签名

`debugConfig` 使用根目录 `debug.keystore`（密码 `android`，alias `androiddebugkey`）。

本地没有该文件时，从 `debug.keystore.base64` 恢复：

```powershell
$base64 = Get-Content -Raw -Path debug.keystore.base64
[IO.File]::WriteAllBytes((Join-Path (Get-Location) 'debug.keystore'), [Convert]::FromBase64String($base64.Trim()))
```

Android Studio Run 或：

```bash
./gradlew assembleDebug
```

### Release

默认读取根目录 `release.jks`，也可通过环境变量 / `.env`：

- `KEYSTORE_PATH`
- `STORE_PASSWORD`
- `KEY_PASSWORD`
- `KEY_ALIAS`

```powershell
.\package.ps1
```

APK 输出到 `build/release/`（按 ABI 分包）。真机 32 位电视装 `armeabi-v7a`，模拟器按镜像选 `x86` 或 `x86_64`。

## 技术

Kotlin + 传统 View/XML。内核 Mozilla GeckoView 115。二维码 ZXing。配置 SharedPreferences。

不做：标签页、搜索、收藏、下载、账号、系统 WebView、手机 App、WebSocket、云端遥控。
