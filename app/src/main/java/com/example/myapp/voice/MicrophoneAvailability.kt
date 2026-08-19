package com.example.myapp.voice

/** 系统全局静音时在创建 AudioRecord 前给出明确提示。 */
fun systemMicrophoneBlockMessage(isSystemMicrophoneMuted: Boolean): String? =
    if (isSystemMicrophoneMuted) {
        "\u7cfb\u7edf\u9ea6\u514b\u98ce\u5df2\u5173\u95ed\uff0c\u8bf7\u5148\u6253\u5f00\u9ea6\u514b\u98ce\u603b\u5f00\u5173"
    } else {
        null
    }
