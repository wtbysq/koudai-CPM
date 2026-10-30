# 口袋 CPM（Koudai CPM）

一台安卓手机上的**完全离线 AI 聊天应用**。会话即联系人：每个 AI 是有名字、头像、人设的独立角色，可以单聊，也可以把多个角色拉进群里聊。模型、语音、数据全部留在本机，除 `127.0.0.1:8080` 外不发起任何网络请求。

- 当前版本：**1.0.0**（versionCode 39，minSdk 28 / targetSdk 34）
- 模型：MiniCPM-V 4.7 **1B**（Q4_K_M）+ 视觉投影器，Apache-2.0
- 语音：SenseVoice 离线识别 + VITS 本地朗读（6 个音色）
- 界面：原生 JS 单文件，无框架、无构建步骤

## 功能

| 模块 | 说明 |
|---|---|
| 联系人 | 名字（全局唯一）、头像（内置 12 款 + 相册导入）、人设、长期记忆，独立音色与自动朗读 |
| 群聊 | 2–10 人，成员各自保留人设与记忆；点头像 / 长按头像 @ 指定谁回答；一条消息可 @ 多人，按序各答一次 |
| 记忆 | 人设与长期记忆每次注入 system；长按 AI 回复可「存为长期记忆」（≤60 字/条，≤30 条），不占聊天历史 |
| 看图 | 支持发图问答，视觉编码走 Adreno GPU（OpenCL），可退回纯 CPU |
| 语音 | 麦克风口述转文字；按联系人音色朗读，可自动朗读 |
| 存档 | 一键导出 `Download/口袋CPM/koudai-存档-时间戳.json`，换机可完整恢复 |
| 检索与管理 | 联系人页「联系人 / 群聊」双标签 + 按名字搜索；长按行删除需二次确认 |

## 目录

```
.
├── project/
│   ├── app/                  应用源码与构建脚本
│   │   ├── src/com/koudai/cpm/   MainActivity · SpeechEngine · EngineHolder · EngineService
│   │   ├── assets/           前端单文件 index.html + 角色形象
│   │   ├── res/              图标资源
│   │   ├── AndroidManifest.xml
│   │   ├── build-apk.sh      六步构建：aapt2 → javac → d8 → 组包 → zipalign → 签名
│   │   ├── pack.py           组装 APK（资源 + dex + assets + 原生库）
│   │   ├── make-model-zip.py 生成模型包
│   │   └── host-tc.cmake / host-toolchain.cmake   宿主编译配置
│   └── tools/                图片裁切、音色包打包、模型下载
└── docs/
    ├── 01-项目计划书.md
    ├── 02-技术验证报告.md
    ├── 03-结构说明.md        架构、数据模型、桥接口、打包、检查清单
    └── 04-使用说明.md        安装、导入、功能、FAQ
```

## 构建

### 依赖

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | 17 | javac / d8 / apksigner |
| Android SDK build-tools | 34/35 | aapt2、zipalign、apksigner |
| Android NDK | r28b | 重新编译 llama.cpp 原生库时使用 |

仓库**不包含**以下二进制（体积原因），需自行准备：

- `app-ocl/`：llama.cpp b11669 的 arm64 原生库（含 OpenCL），由 NDK 交叉编译产出
- `speech/`：SenseVoice 识别模型、VITS 音色、sherpa-onnx Java API 源码与 `libonnxruntime.so`
- 模型权重：MiniCPM-V 4.7 1B GGUF + mmproj

### 步骤

```bash
cd project
BUNDLE_MODELS=0 TARGET_SDK=34 OUT_NAME=koudai-cpm-1.0.0-lite.apk bash app/build-apk.sh
```

产物在 `project/out/`。`BUNDLE_MODELS=0` 出轻量包（约 17 MB，模型另行导入）；`=1` 会把模型分片打进 APK。

模型包与音色包：

```bash
MODEL_PKG_VER=1.0.0 python app/make-model-zip.py
VOICE_PKG_VER=1.0.0 python tools/make-voices-zip.py
```

### 路径注意事项

aapt2 / javac 等工具**不支持非 ASCII 路径**。若工程放在中文目录下（例如 `D:\口袋CPM\project`），构建时需先用 ASCII 目录联接作为入口：

```powershell
New-Item -ItemType Junction -Path 'D:\kcpm' -Target 'D:\口袋CPM\project'
```

然后 `cd /d/kcpm` 执行构建（可用环境变量 `KCPM_ROOT` 指定其它根目录，脚本会在缺失时提示）。

## 安装与使用

安装 APK 后，需在「设置 → 模型包 → 导入 .zip」导入模型包（约 1.8 GB），完成后即可离线对话。详细步骤见 [docs/04-使用说明.md](docs/04-使用说明.md)。

安装包与模型包体积较大，不放进仓库，随 Release 附带。

## 技术要点

- **零网络**：除本机 `127.0.0.1:8080` 的推理进程外不发请求；WebView 只放行 `android_asset` 与本机地址
- **热启动**：引擎由前台服务保活，退出界面不杀进程，重入约 1 秒；通知栏可「结束引擎」立即释放
- **上下文**：8192 tokens，KV cache 用 q8 量化（同内存装两倍上下文），启动失败自动回退 4096
- **群聊身份隔离**：只有当前发言人的话算 `assistant`，其他人的话放在用户侧并带名字，避免多个 AI 串成一个口吻
- **无 XSS 面**：Markdown 先转义再处理格式，不生成 `<a>`/`<img>`

## 数据与隐私

| 数据 | 位置 |
|---|---|
| 联系人、群聊、聊天记录、设置 | 应用内部存储（WebView localStorage） |
| 模型 / 语音模型 | 应用私有目录，非 root 不可读 |
| 存档 | `/sdcard/Download/口袋CPM/`（可拷走） |

卸载即清空内部数据，请先导出存档。

## 第三方

- [llama.cpp](https://github.com/ggml-org/llama.cpp)（MIT）
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）
- MiniCPM-V 4.7 模型（Apache-2.0，版权归 OpenBMB）
- 音色：VITS 中文模型，来自 [csukuangfj](https://huggingface.co/csukuangfj) 的公开权重

## 文档

- [结构说明](docs/03-结构说明.md)：分层、数据模型、前端函数地图、20 个原生接口、打包与签名、发布检查清单
- [使用说明](docs/04-使用说明.md)：安装、导入、逐项功能、常见问题
