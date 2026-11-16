# 览 Lan — Chrome 内核浏览器（系统 WebView 壳）

包名 `com.suanran.lan`，v0.7.0 (10)。

## 内核说明

不自带 Chromium 源码编译。直接用手机上 Android System WebView，
即 Chrome 内核 Blink + V8，随系统 WebView 更新而更新。

## 功能

- 主页：览原创 7 套主题（雾蓝/墨夜/星野/青瓷/暮山/纸鸢/素白），
  大字标 + 一体搜索栏 + 常用功能 + 快捷方式 + 最近访问
- 菜单底部弹窗：头像行 + 拓展/书签/下载/历史 + 常用功能九宫格
- 智能播放器：底部弹窗（视频卡 + 本页视频列表 + 分站设置），
  也可切全屏播放 / 悬浮小窗，支持断点续播
- 智能下载器：玻璃悬浮按钮（可拖动、松手磁吸贴边），
  只在页面视频正在播时出现；点开是玻璃卡片列表，每张卡可下载/播放
- 视频嗅探：网络请求 + video 标签双路抓 m3u8/mp4 等直链；
  blob 与加密流抓不到
- 扩展程序 + 扩展商店：用户脚本式（Tampermonkey 风格），
  商店里有去广告增强 / 深色模式 / 视频 2 倍速 / 网页翻译 / 阅读模式 / 解除复制限制，
  也支持从链接安装 .user.js、粘贴代码安装（要开开发者模式），
  自动更新扩展开了会在启动时重拉从链接装的脚本
- 应用内检测更新：每天自动查一次 version.json，有新版弹框下载；
  菜单/主页里也能手动检查更新
- 日志：菜单 → 日志，查看 / 清空 / 分享
- 多标签（会话恢复）、收藏、历史、桌面版 UA、去广告、下载走 DownloadManager、
  全屏视频、文件上传、清除浏览数据、分享、用系统浏览器打开

## 限制

WebView 跑不了 Chrome 应用商店扩展，需要自带 Chromium 重编才行。
翻译 / 阅读 / 去广告这类需求都用用户脚本实现。

## 更新通道

应用内检测更新读仓库根目录的 `version.json`：

```json
{"version": "0.7.0", "version_code": 10, "apk": "https://raw.githubusercontent.com/anransu983-sketch/lan/main/releases/lan-0.7.0.apk", "notes": "……"}
```

发新版时把 APK 放进 `releases/` 并更新 `version.json` 即可。

## 构建

本机 Gradle 8.7（`~/workspace/tools/gradle-8.7/bin/gradle`），
GRADLE_USER_HOME=`~/workspace/tools/gradle-home`，
JDK 17，AGP 8.5.2 / Kotlin 2.0.20。构建前须在同一次 exec 里
刷新 gradle-home 里的代理密码并用 truststore5（当前 Hatch CA）。

```
python3 ~/workspace/tools/refresh-gradle-proxy.py
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djavax.net.ssl.trustStore=/home/hatch/workspace/tools/hatch-truststore5.jks -Djavax.net.ssl.trustStorePassword=changeit"
export GRADLE_USER_HOME=/home/hatch/workspace/tools/gradle-home
export JAVA_HOME=/home/hatch/workspace/tools/jdk17
~/workspace/tools/gradle-8.7/bin/gradle assembleDebug
```
