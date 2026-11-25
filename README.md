# GCode Sender for Android

![minSdk](https://img.shields.io/badge/minSdk-21-brightgreen)
![license](https://img.shields.io/badge/license-MIT-green)

> 用安卓手机通过 OTG 直接驱动雕刻机 / 写字机 / 绘图仪：把桌面软件导出的 GCode 文件流式发送给
> **GRBL / Marlin / EBB** 主板。手机端只管发送，排版与出图仍在电脑上做。

An Android **GCode sender** over USB-OTG for GRBL / Marlin / EBB controllers.
No root, no cloud, no third-party service — just a serial port and a file.

---

## 为什么

桌面刻字/雕刻软件（CAM、字体排版、图片转路径）几乎只有 Windows 版，而机器就在旁边。
把「生成 GCode」与「发送 GCode」拆开之后，现场只需要一台手机：

```
电脑：设计排版 → 导出 .gcode       手机：OTG 接机器 → 播放 → 完成
```

## 特性

- **USB 串口直连**：内置 [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) 3.8.1（源码内联），
  支持 **CH340/CH341、CP2102/CP2104、FTDI、PL2303、CDC-ACM**，无需 root
- **三种流控**
  | 模式 | 适用 | 说明 |
  | --- | --- | --- |
  | 等待 ok | GRBL / Marlin | 每行等到 `ok` 再发下一行，最稳 |
  | 字符计数 | GRBL | 利用控制器 RX 缓冲（默认 120 B），速度最快 |
  | 定时发送 | EBB / 不发 ok 的固件 | 固定间隔推流，可调 20–50 ms |
- **文件播放**：任意目录选 `.gcode / .nc / .tap / .txt`，自动跳过空行、`(...)` 与 `;` 注释
- **进度可读**：行号 / 百分比 / 已用时间 / 预计剩余 / 超时次数
- **安全暂停**：暂停在当前行发完之后生效，不会把一条指令切成两半
- **前后附加指令**：`开始前` / `结束后` 各一格，`;` 分隔多条
- **手动控制**：自由命令输入 + 一键 `$$` `$X` `$H` `G28` `M5` `M3 S1000` `G92 X0 Y0` `G0 X0 Y0` `G21` `G90`
- **省心细节**：参数记忆、连接后屏幕常亮、插拔自动感知、日志滚动查看

## 下载

到 [Releases](../../releases) 取最新 `gcode-sender-vX.Y.Z.apk`。

- 系统要求：Android 5.0 (API 21) 及以上，手机需支持 **USB Host / OTG**
- 首次安装需允许「未知来源」

## 快速开始

1. **接线**：手机 → OTG 转接头 → 机器主板 USB 口
2. **连接**：App 第 1 区「刷新」→ 选中如 `Ch34x [0x1A86:0x7523]` → 选波特率 → 「连接」→ 弹窗点允许
3. **自检**：第 4 区输入 `$$` 发送；日志里出现 `<< ok`（GRBL 会返回一串 `$` 参数）说明链路通了
4. **播放**：第 2 区选文件 → 第 3 区选流控 → 「开始」

推荐起点：

| 主板 | 波特率 | 流控 |
| --- | --- | --- |
| GRBL (Arduino UNO / 3D 打印主板刷 GRBL) | 115200 | 等待 ok，或字符计数 + 缓冲 120 |
| Marlin | 250000（部分 115200） | 等待 ok |
| EBB (EiBotBoard / AxiDraw 类) | 115200 | 定时发送 30 ms |

## 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 下拉只有「不支持 0x…」 | 芯片不在支持表，或线是充电线；换数据线 |
| 连接后没有 `<< ok` | 波特率不对；行尾 LF 换 CRLF；TX/RX 接反；固件没起来 |
| 发几行就卡住 | 换「等待 ok」；GRBL 缓冲降到 100–120；超时调到 30 s |
| 走位错乱 / 丢步 | 机器进给太快，回电脑端降速重新导出 |
| 权限弹窗不出现 | 拔插一次 → 刷新；或系统设置里撤销该 USB 设备默认应用 |
| 手机完全没反应 | 手机不支持 OTG，或 OTG 线损坏 |

## 自行构建

依赖：**JDK 17**、**Android SDK platform 34 + build-tools 34.0.0**、**Gradle 8.7**

```bash
git clone https://github.com/leezoneline/android-gcode-sender.git
cd android-gcode-sender
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleRelease          # 产物：app/build/outputs/apk/release/
./build-apk.sh                  # 或者用脚本（Windows/Git-Bash 亦可）
```

签名：仓库不含私钥。想签自己的包就生成一份 `keystore.jks` 放在项目根目录，
口令默认 `gcodesender`，也可用环境变量覆盖：

```bash
keytool -genkeypair -keystore keystore.jks -alias gcodesender \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass gcodesender -keypass gcodesender \
        -dname "CN=Your Name, O=Your Org, C=CN"

export KEYSTORE_PASSWORD=*** KEY_ALIAS=gcodesender KEY_PASSWORD=***
gradle assembleRelease
```

没有 `keystore.jks` 时构建会自动退回 debug 签名，CI 与首次 clone 都能直接出包。

## 目录结构

```
.
├── app/
│   ├── build.gradle                     # 应用模块，签名可缺省
│   └── src/main/
│       ├── AndroidManifest.xml          # USB Host 声明、插拔自动启动
│       ├── java/com/dsh/gcodesender/
│       │   ├── MainActivity.java        # 界面、USB 权限、文件、日志
│       │   ├── SerialLink.java          # OTG 串口封装
│       │   └── GcodePlayback.java       # 播放引擎（三种流控）
│       └── res/                         # 布局、样式、图标、USB 设备过滤
├── third_party/usbSerialJava/           # usb-serial-for-android 3.8.1（MIT）
├── .github/workflows/build-apk.yml      # 打 tag 自动构建并发布 Release
├── build-apk.sh
└── README.md / LICENSE / NOTICE
```

## 设计要点

- **不碰 `SerialInputOutputManager` 之外的线程模型**：读写各一条线程，播放引擎只做流控决策
- **暂停语义**：`pause()` 只设置标志，等当前行 `ok` 后阻塞，避免半行指令
- **超时不停机**：等待 `ok` 超时会把流控窗口清零并继续，避免一根脏数据导致整份文件作废
- **纯 ASCII 发送**：注释被剥离后再编码，杜绝中文注释把串口写脏

## 路线图

- [ ] 断点续刻（记录行号，断电后接着发）
- [ ] 多文件队列
- [ ] 蓝牙串口（HC-05 / SPP）
- [ ] EBB 专用指令面板（`SM`/`XM`/`EM`）
- [ ] 深浅色主题

## 协议

[MIT License](LICENSE)。第三方组件与版权声明见 [NOTICE](NOTICE)。

## 范围说明

本项目只做「GCode 文本 → 串口字节流」这一件事，不含任何排版、字体、图像转路径或授权逻辑；
与桌面端刻字软件互不隶属，也不需要它们的存在即可工作。
