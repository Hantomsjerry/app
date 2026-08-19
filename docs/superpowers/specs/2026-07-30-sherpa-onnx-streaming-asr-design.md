# sherpa-onnx 流式语音识别设计

## 目标

使用 sherpa-onnx 的中英双语 Streaming Zipformer INT8 模型完全替换本地 Whisper，在录音过程中实时显示临时转写，并在用户停止录音或达到 20 秒上限后把最终文字交给现有 Qwen/Kotlin/TCP 安全链路。

本次迁移优先降低本地语音转写等待时间，同时保持以下既有约束：

- Android 10（API 29）及以上。
- 目标设备为 8 GB 内存、中端骁龙、`arm64-v8a`。
- ASR 模型随 App 内置，识别过程不依赖网络。
- 支持中文、英文和中英混合表达。
- 最终命令仍必须经过 Qwen 输出、Kotlin 白名单校验和用户点击“应用”。
- 语音卡片和现有 20 秒录音上限保持不变。
- 不新增热词设置界面。

## 技术选型

### 运行库

使用官方 `sherpa-onnx-1.13.2.aar`，固定保存到项目中，不依赖构建时动态选择最新版。

- 发布版本：`v1.13.2`
- AAR 大小：约 54 MB
- AAR SHA-256：`aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245`
- Android ABI 继续由项目现有 `arm64-v8a` 过滤规则限制。

### 模型

使用官方 `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16` 的 INT8 文件：

- `encoder-epoch-99-avg-1.int8.onnx`，约 41 MB
- `decoder-epoch-99-avg-1.int8.onnx`，约 3.4 MB
- `joiner-epoch-99-avg-1.int8.onnx`，约 3.1 MB
- `tokens.txt`
- 从模型的 `bpe.model` 通过官方脚本导出的 `bpe.vocab`

只打包 INT8 推理所需文件，不打包 FP32 模型、测试音频、脚本或原始 `bpe.model`。集成时为每个资源记录精确字节数和 SHA-256，并在首次复制到 App 私有目录时校验。

### 解码配置

- `sampleRate = 16000`
- `featureDim = 80`
- `provider = cpu`
- `numThreads = 2`
- `decodingMethod = modified_beam_search`
- `maxActivePaths = 4`
- `enableEndpoint = false`，结束由现有按钮和 20 秒上限控制
- `modelingUnit = cjkchar+bpe`
- `hotwordsScore = 2.0`
- `bpeVocab` 指向已校验的 `bpe.vocab`

官方文档说明，热词只支持 Transducer 模型的 `modified_beam_search`，中英混合模型需要 `cjkchar+bpe` 与 `bpe.vocab`。

## 组件边界

### `SherpaOnnxModelStore`

负责将 AAR 所需的模型、词元表、BPE 词表和热词文件从 assets 复制到 App 私有目录，并验证字节数与 SHA-256。复制采用临时文件加原子发布；发现缺失、截断或校验失败时删除无效文件并重新准备。

Store 只负责文件，不创建 recognizer，也不感知录音或界面状态。

### `StreamingSpeechEngine`

定义与 sherpa-onnx 无关的流式识别接口：

- `prepare()`：准备模型并创建可复用 recognizer。
- `startSession(onPartialText)`：为本次录音创建独立 stream。
- `acceptSamples(samples)`：按顺序接收 16 kHz 单声道 PCM 块。
- `finishSession()`：通知输入结束、排空可解码帧并返回最终文字。
- `cancelSession()`：销毁当前 stream，不产生最终结果。
- `close()`：释放 stream 与 recognizer。

生产实现 `SherpaOnnxStreamingEngine` 在专用的单线程协程上下文中调用 native API，保证同一 stream 的输入和解码顺序稳定。`ShortArray` PCM 在送入 recognizer 前转换为 `FloatArray`，使用 `sample / 32768.0f` 归一化到 `[-1, 1)`。

Recognizer 在页面出现后后台预热并复用；每次录音只创建和销毁 stream。首次点击时若预热尚未完成，界面显示模型加载状态，准备完成后自动开始录音。

### `StreamingPcmRecorder`

复用现有 `AudioRecord` 配置：16 kHz、单声道、16-bit PCM、`VOICE_RECOGNITION` 音源和 20 秒样本上限。

录音线程只读取与复制约 1024 个样本的块，不直接执行 ONNX 推理。样本块进入当前识别会话的顺序队列，由引擎消费；即使推理短时落后也不阻塞 `AudioRecord.read()`。20 秒原始 PCM 总量有限，队列最坏内存仍小于 1 MB。

### `LocalSpeechController`

由现有 `LocalWhisperController` 泛化而来，继续作为录音、超时、取消和识别会话的唯一生命周期所有者。它增加临时转写回调，并保留 generation 隔离：旧会话可以释放资源，但不能投递临时文字、最终文字或错误。

临时文字仅在内容发生变化时投递，并限制为最多每 100 ms 一次。停止录音后关闭样本队列，等待已入队样本全部解码，再调用 `finishSession()`。

### `SpeechTranscriptNormalizer`

在最终文字进入 `DeterministicVoiceParser` 和 Qwen 之前执行受控规范化。它不做自由改写，只处理明确的领域形式：

- `L V 1`、`l v 1`、`L V ONE` 转为 `Lv1`
- 对应的 `L V 2/3` 转为 `Lv2/3`
- 允许 ASR 常见空格和大小写差异

规范化规则使用单词边界，避免替换普通英文单词中的字母组合。临时文字可显示规范化后的结果，使界面最终显示与解析输入一致。

## 固定热词

热词存放在只读的 App assets 中，不提供用户编辑入口。使用全局 2.0 分数，并为容易混淆的设备、级别和参数短语设置 2.5 至 3.5 的单独分数。

热词至少覆盖：

- `一号机`、`二号机`、`三号机`
- `MACHINE ONE`、`MACHINE TWO`、`MACHINE THREE`
- `LV1`、`LV2`、`LV3`
- `L V 1`、`L V 2`、`L V 3`
- `LEVEL ONE`、`LEVEL TWO`、`LEVEL THREE`
- `灵敏度`、`敏感度`、`强度`、`浓淡`、`密度`
- `最小面积`、`增强推理`、`增强推断`
- `面积屏蔽`、`区域屏蔽`、`区域遮罩`、`区域掩码`
- `动作持续时间`、`动作时长`、`剔除延时`、`拒绝延迟`
- `模板`、`TEMPLATE`、`400 M M BASE ENGINE`
- 现有确定性解析器中的英文参数短语，例如 `SENSITIVITY`、`STRENGTH`、`MINIMUM AREA` 和 `REJECT DELAY`

热词表与参数白名单使用测试保持同步。新增语音参数别名时，如果它适合作为领域热词，测试会要求同步更新热词资源。

## 数据流

1. 页面启动后后台执行 `StreamingSpeechEngine.prepare()`。
2. 用户点击麦克风，现有权限检查通过后创建识别 session 并开始录音。
3. `AudioRecord` 持续产生 PCM 块，识别引擎边接收边解码。
4. 改变后的临时文字经过受控规范化，并更新语音卡片。
5. 用户再次点击或达到 20 秒时停止录音，等待队列排空，完成 stream。
6. 空白最终文字进入“未识别到有效语音”错误状态；非空文字成为最终转写。
7. 最终转写经过 `SpeechTranscriptNormalizer`，再进入现有确定性解析器；信息不足时才调用 Qwen。
8. Qwen 输出继续经过严格 JSON 白名单校验。
9. 语音卡片展示候选命令；用户点击“应用”后才通过 TCP 发送。

临时文字绝不进入 Qwen、Kotlin 命令构造或 TCP 发送。

## 界面状态

现有语音卡片布局保持不变，只扩展状态内容：

- `PreparingModel`：显示“正在加载语音模型”。
- `Recording(partialText)`：没有文字时显示“正在聆听”，有文字时显示实时转写。
- `Transcribing`：停止后短暂显示“正在整理识别结果”。
- 后续 `Parsing`、`Ready`、发送和错误状态沿用现有流程。

如果模型已经预热，点击麦克风后直接进入录音。页面切到后台、Composition 销毁或用户开始新会话时，立即使旧 generation 失效并释放旧 stream。

## 错误处理

- AAR 或 native 库加载失败：显示“本地语音识别组件加载失败”，不开始录音。
- 模型资源缺失或校验失败：尝试重新复制；仍失败时显示“语音模型准备失败”。
- recognizer 配置无效或热词无法解析：显示“语音识别配置无效”。
- `AudioRecord` 失败：沿用现有麦克风错误分类和权限处理。
- 流式解码失败：停止并释放录音与 stream，显示“本地语音识别失败”，允许重新点击。
- 最终文字为空：显示“未识别到有效语音”。
- 取消与 `CancellationException`：只清理资源，不显示旧会话错误。

任何识别错误都不得触发 Qwen 或 TCP。

## 移除内容

- 删除 App 对 `:whisper-native` 的 Gradle 依赖和 settings 注册。
- 删除 Whisper 模型 `ggml-small-q5_1.bin` 的打包与模型准备逻辑。
- 删除未再使用的 Whisper Kotlin/JNI/CMake 接入代码。
- `third_party/whisper.cpp` 不再参与构建；是否从工作区物理删除不影响 App 行为，但最终构建不得引用它。
- Qwen 模型、Qwen JNI、参数解析和 TCP 模块保持不变。

## 测试范围

### JVM 自动测试

- 模型清单、原子复制、长度/SHA-256 校验和损坏恢复。
- PCM `ShortArray` 到归一化 `FloatArray` 的边界值转换。
- 流式会话的开始、样本顺序、临时文字去重与 100 ms 节流。
- 手动停止、20 秒停止、取消、关闭和重复会话。
- 旧 generation 的临时文字、最终文字和错误无法回写。
- 临时文字不会触发 Qwen，最终文字只触发一次。
- `L V 1/2/3` 受控规范化及误替换防护。
- 热词资源覆盖设备和现有参数别名。
- 现有 Qwen、Kotlin 校验、TCP 和界面状态测试全部回归。

### 构建验证

- `:app:testDebugUnitTest`
- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- 检查 APK 中包含 arm64 sherpa-onnx native 库和所需 INT8 模型，不包含 Whisper 模型和 Whisper native 库。

### 明确不包含

按当前要求，不执行真机或模拟器上的 native 加载、识别准确率和速度测试。因此本次只能证明代码、资源和 APK 可构建，不能证明目标手机上的实际延迟、热词增益或识别准确率。首次在手机运行后的真实表现需要另行观察。

## 参考资料

- sherpa-onnx Android：https://k2-fsa.github.io/sherpa/onnx/android/index.html
- Android 构建与模型集成：https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html
- Streaming Zipformer 模型：https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/pretrained_models/online-transducer/zipformer-transducer-models.rst
- 热词与 `cjkchar+bpe`：https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html
- sherpa-onnx v1.13.2：https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.2
