package com.uong.phormi

import android.app.Application
import android.os.LocaleList
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PhormiKeyboardTextEngineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun editor(locale: Locale = Locale.US) = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        hintLocales = LocaleList(locale)
    }
    @Before fun clear() { PhormiKeyboardTextEngine.clearPersonalDictionary(context) }

    @Test fun `three intentional rejections stop correction of a chosen spelling`() {
        val info = editor()
        assertEquals("the", PhormiKeyboardTextEngine.correctionFor(context, "teh", Locale.US))
        repeat(2) { PhormiKeyboardTextEngine.rememberLiteralChoice(context, "teh", info) }
        assertEquals("the", PhormiKeyboardTextEngine.correctionFor(context, "teh", Locale.US))
        PhormiKeyboardTextEngine.rememberLiteralChoice(context, "teh", info)
        assertNull(PhormiKeyboardTextEngine.correctionFor(context, "teh", Locale.US))
    }

    @Test fun `new personal words are suggested only after three completed uses`() {
        val info = editor()
        repeat(2) { PhormiKeyboardTextEngine.learn(context, "Phormiverse", info) }
        assertFalse(PhormiKeyboardTextEngine.suggestions(context, "phormi", Locale.US).contains("Phormiverse"))
        PhormiKeyboardTextEngine.learn(context, "Phormiverse", info)
        assertTrue(PhormiKeyboardTextEngine.suggestions(context, "phormi", Locale.US).any { it.equals("Phormiverse", true) })
    }

    @Test fun `literal spelling is the first suggestion not an unsolicited replacement`() {
        assertEquals("teh", PhormiKeyboardTextEngine.suggestions(context, "teh", Locale.US).first())
    }

    @Test fun `passwords numeric PINs URI and private fields do not learn or autocorrect`() {
        val variants = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        variants.forEach { type ->
            val info = editor().apply { inputType = type }
            assertFalse(PhormiKeyboardTextEngine.shouldUsePredictions(info))
            repeat(4) { PhormiKeyboardTextEngine.learn(context, "Privatesecret", info) }
        }
        val private = editor().apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }
        assertFalse(PhormiKeyboardTextEngine.allowsAiEmoji(private))
        assertFalse(PhormiKeyboardTextEngine.allowsPersonalizedLearning(private))
        assertFalse(PhormiKeyboardTextEngine.suggestions(context, "Privates", Locale.US).contains("Privatesecret"))
    }

    @Test fun `personal spellings are scoped to the selected language`() {
        repeat(3) { PhormiKeyboardTextEngine.rememberLiteralChoice(context, "teh", editor(Locale.US)) }
        assertNull(PhormiKeyboardTextEngine.correctionFor(context, "teh", Locale.US))
        assertEquals("the", PhormiKeyboardTextEngine.correctionFor(context, "teh", Locale.UK))
    }

    @Test fun `forgetting a learned spelling restores conservative correction`() {
        repeat(3) { PhormiKeyboardTextEngine.rememberLiteralChoice(context, "teh", editor()) }
        assertTrue(PhormiKeyboardTextEngine.forgetPersonalizedSuggestion(context, "teh", locale = Locale.US))
        assertEquals("the", PhormiKeyboardTextEngine.correctionFor(context, "teh", Locale.US))
    }
}
