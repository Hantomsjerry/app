package com.example.myapp.voice

import android.icu.text.Transliterator
import java.util.Locale

fun interface PinyinEncoder {
    fun encode(text: String): String
}

class AndroidIcuPinyinEncoder : PinyinEncoder {
    private val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower()")

    override fun encode(text: String): String = transliterator
        .transliterate(text)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
