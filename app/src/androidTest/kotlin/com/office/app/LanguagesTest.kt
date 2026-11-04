package com.office.app

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LanguagesTest {

    private fun titleIn(tag: String): String {
        val config = Configuration(targetContext.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return targetContext.createConfigurationContext(config).getString(R.string.home_title)
    }

    @Test
    fun everyLanguageResolvesItsOwnStrings() {
        val expected = mapOf(
            "tr" to "Dosyalar", "en" to "Files", "de" to "Dateien", "es" to "Archivos", "fr" to "Fichiers",
            "pt" to "Arquivos", "ru" to "Файлы", "zh" to "文件", "ja" to "ファイル", "ko" to "파일",
            "ar" to "الملفات", "hi" to "फ़ाइलें", "bn" to "ফাইল", "ur" to "فائلیں", "id" to "File",
            "vi" to "Tệp", "mr" to "फाइल", "te" to "ఫైల్‌లు", "ta" to "கோப்புகள்", "fil" to "Mga file",
            "fa" to "فایل‌ها", "sw" to "Faili",
        )
        expected.forEach { (tag, title) -> assertEquals(tag, title, titleIn(tag)) }
    }
}
