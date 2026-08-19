# 生产代码中文注释实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为项目自有生产代码补充简洁、准确的中文注释，并保持运行行为完全不变。

**Architecture:** 按通信/UI、语音 Kotlin、原生推理与构建三个边界分批添加注释。注释聚焦职责、数据流、状态机、并发、资源生命周期和协议约束，不逐行复述代码。

**Tech Stack:** Kotlin、Jetpack Compose、Kotlin Coroutines、Android JNI、C++17、CMake、Gradle Kotlin DSL

## Global Constraints

- 只修改 `app/src/main`、`whisper-native/src/main` 内的 Kotlin、C++ 和 CMake 源码。
- 不修改 `src/test`、`src/androidTest`、`third_party`、资源 XML、模型文件和生成目录。
- 只增加或调整注释，不改变函数签名、常量、配置值或控制流。
- 注释使用简洁中文，说明设计意图和不明显的约束，避免逐行翻译代码。
- 不新增或修改测试代码；使用已有测试和构建验证改动。

---

### Task 1: UI 与 TCP 通信代码注释

**Files:**
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt`
- Modify: `app/src/main/java/com/example/myapp/CommunicationDialogs.kt`
- Modify: `app/src/main/java/com/example/myapp/ParameterAdjuster.kt`
- Modify: `app/src/main/java/com/example/myapp/communication/EndpointStore.kt`
- Modify: `app/src/main/java/com/example/myapp/communication/ParameterCommand.kt`
- Modify: `app/src/main/java/com/example/myapp/communication/TcpEndpoint.kt`
- Modify: `app/src/main/java/com/example/myapp/communication/TcpParameterClient.kt`
- Modify: `app/src/main/java/com/example/myapp/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/example/myapp/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/example/myapp/ui/theme/Type.kt`

**Interfaces:**
- Consumes: 现有 Compose 状态、`DetectionParameters`、换行分帧 TCP 协议。
- Produces: 不改变任何接口；仅增加中文说明。

- [ ] **Step 1:** 为文件职责、主页面状态编排、生命周期清理和 generation 防旧回调机制添加注释。
- [ ] **Step 2:** 为参数快照编码、地址持久化、端点校验、TCP 串行操作、绝对响应截止时间和异常清理添加注释。
- [ ] **Step 3:** 为主要 Compose 组件和主题入口添加职责注释，跳过普通颜色、尺寸和赋值语句。
- [ ] **Step 4:** 检查这些文件的差异，确认除注释和空行外没有代码 token 变化。

### Task 2: 语音 Kotlin 代码注释

**Files:**
- Modify: `app/src/main/java/com/example/myapp/voice/DeterministicVoiceParser.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/LocalInferenceCoordinator.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/LocalWhisperController.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/MicrophoneAvailability.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/ParameterVoiceUpdate.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/PcmRecorder.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/QwenInferenceEngine.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/QwenModelStore.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/SpeechRecognitionResult.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/VoiceCommand.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/VoiceGenerationTracker.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/VoiceIntentParser.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/VoiceUiState.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/WhisperInferenceEngine.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/WhisperModelStore.kt`

**Interfaces:**
- Consumes: PCM 录音、Whisper 转写、确定性解析、Qwen 回退、严格命令校验和 UI 状态归约。
- Produces: 不改变任何接口；仅解释各层契约及并发边界。

- [ ] **Step 1:** 注释录音格式、时长边界、start/stop/cancel 所有权和 AudioRecord 清理规则。
- [ ] **Step 2:** 注释 Whisper 会话 Channel、手动/超时停止竞争、generation 失效和安全回调投递。
- [ ] **Step 3:** 注释模型复制校验、`.partial` 原子发布、验证缓存和同路径互斥。
- [ ] **Step 4:** 注释规则优先/Qwen 回退、安全 prompt、严格 JSON 白名单和成功后单字段更新。
- [ ] **Step 5:** 注释 Qwen/Whisper 协程取消、推理串行化、handle 生命周期和全局模型互斥。
- [ ] **Step 6:** 检查语音文件差异，确认没有改变行为代码。

### Task 3: JNI、CMake 与模块构建注释

**Files:**
- Modify: `app/src/main/cpp/qwen_jni.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `whisper-native/src/main/cpp/whisper_jni.cpp`
- Modify: `whisper-native/src/main/cpp/CMakeLists.txt`
- Modify: `app/build.gradle.kts`
- Modify: `whisper-native/build.gradle.kts`

**Interfaces:**
- Consumes: Kotlin external 方法、llama.cpp、whisper.cpp、Android NDK 和 arm64 ABI 配置。
- Produces: 不改变 JNI 符号、native 参数、目标库或构建选项。

- [ ] **Step 1:** 注释 JNI RAII 包装、UTF 转换、异常映射、取消标志和 native handle 注册表。
- [ ] **Step 2:** 注释 Qwen chat template、分批解码、greedy 采样、每 token 取消检查和资源释放。
- [ ] **Step 3:** 注释 Whisper PCM 归一化、CPU 上下文、自动语言、abort callback 和文本拼接。
- [ ] **Step 4:** 注释 CMake 的第三方裁剪、CPU-only 配置、模块隔离和链接目标。
- [ ] **Step 5:** 注释 Gradle 中 ABI、NDK/CMake、模型不压缩和模块依赖等关键配置。
- [ ] **Step 6:** 检查 native/构建文件差异，确认选项和值未变化。

### Task 4: 完整验证

**Files:**
- Verify only: all modified production files

**Interfaces:**
- Consumes: Tasks 1-3 的注释改动。
- Produces: 差异审计、单元测试和构建结果。

- [ ] **Step 1:** 用 `git diff --check`（若仓库元数据可用）或等价文本检查发现空白错误。
- [ ] **Step 2:** 检查 `src/test`、`src/androidTest` 和 `third_party` 没有发生修改。
- [ ] **Step 3:** 运行 `./gradlew.bat testDebugUnitTest`，要求退出码为 0。
- [ ] **Step 4:** 运行 `./gradlew.bat assembleDebug`，要求退出码为 0。
- [ ] **Step 5:** 汇总已注释的文件范围、验证结果及任何环境限制。
