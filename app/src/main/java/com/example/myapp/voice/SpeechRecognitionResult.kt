package com.example.myapp.voice

/** 某一录音 generation 对应的最终转写文本。 */
data class SpeechRecognitionText(
    val generation: Long,
    val text: String
)

/** One final offline recognition result, including the correction decision for its session. */
data class SpeechRecognitionResult(
    val generation: Long,
    val correction: CorrectionResult
)

/** 供 UI 展示的稳定错误分类和中文信息，不泄露底层异常细节。 */
data class SpeechRecognitionFailure(
    val generation: Long,
    val type: SpeechRecognitionFailureType,
    val message: String
)

enum class SpeechRecognitionFailureType {
    PermissionDenied,
    Unavailable,
    NoMatch,
    Network,
    Timeout,
    Busy,
    Client,
    Service,
    Audio
}
