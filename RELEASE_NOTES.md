# v1.0.0

首个公开版本：把安卓手机变成 GRBL / Marlin / EBB 的 GCode 发送端。

## 功能

- USB-OTG 串口直连：CH340/CH341、CP2102、FTDI、PL2303、CDC-ACM（usb-serial-for-android 3.8.1 内联）
- 三种流控：等待 `ok` / 字符计数（GRBL 缓冲）/ 定时发送（EBB）
- GCode 文件播放：自动跳过空行、`(...)` 与 `;` 注释，实时进度与 ETA
- 暂停 / 继续 / 停止，暂停在当前行发完后生效
- 开始前、结束后附加指令（`;` 分隔多条）
- 手动命令与快捷按钮：`$$` `$X` `$H` `G28` `M5` `M3 S1000` `G92 X0 Y0` `G0 X0 Y0` `G21` `G90`
- 参数记忆、屏幕常亮、插拔感知、滚动日志

## 安装

下载 `gcode-sender-v1.0.0.apk`，允许未知来源安装。

- minSdk 21（Android 5.0+），targetSdk 34
- 需要手机支持 USB Host / OTG

## 已知限制

- 只发送，不生成 GCode（排版仍在电脑端完成）
- 未实现断点续刻；中断后需重发或手工裁剪文件
- 暂不支持蓝牙串口

## 校验

```
sha256  SHA256SUMS.txt 内附
```

完整使用说明见 [README.md](README.md)。
