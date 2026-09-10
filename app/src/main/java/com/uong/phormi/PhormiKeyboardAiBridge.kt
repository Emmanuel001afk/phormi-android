package com.uong.phormi

import android.content.Context

/**
 * Compatibility facade for the keyboard's optional enhancement layer.
 * Core typing is handled directly by the IME so taps cannot be swallowed by
 * a background gesture layer. This keeps gesture typing available for a
 * future dedicated recognizer without compromising ordinary key presses.
 */
object PhormiKeyboardAiBridge {
    @Volatile private var started = false

    fun start() { started = true }
    fun start(@Suppress("UNUSED_PARAMETER") context: Context) { start() }
    fun stop() { started = false }
    fun isStarted(): Boolean = started
}

object PhormiEmojiSuggester {
    private val rules = listOf(
        listOf("love","heart","crush","miss you") to listOf("❤️","🥰","😍","😘"),
        listOf("happy","great","good","awesome","glad") to listOf("😊","😄","🥳","✨"),
        listOf("sad","sorry","hurt","cry","miss") to listOf("😢","😭","🥺","💔"),
        listOf("angry","mad","hate","furious","annoyed") to listOf("😡","🤬","💢","🔥"),
        listOf("laugh","funny","joke","lol","haha") to listOf("😂","🤣","😆","💀"),
        listOf("wow","amazing","shock","surprise","incredible") to listOf("😮","🤯","😱","✨"),
        listOf("cool","style","nice","perfect") to listOf("😎","🔥","💯","✨"),
        listOf("tired","sleep","sleepy","exhausted") to listOf("😴","🥱","😪","🫠"),
        listOf("confused","what","why") to listOf("🤔","😕","🧐","❓"),
        listOf("party","birthday","celebrate","congrats","congratulations") to listOf("🎉","🥳","🎂","🎊")
    )
    fun suggest(text:String):List<String>{
        val lower=text.lowercase()
        return rules.firstOrNull{(words,_)->words.any{lower.contains(it)}}?.second
            ?: when { lower.trimEnd().endsWith("!")->listOf("😊","😄","🔥","✨"); lower.trimEnd().endsWith("?")->listOf("🤔","❓","😅","👀"); else->emptyList() }
    }
}

object PhormiLocalPredictionEngine {
    private val words = listOf("the","and","you","your","that","this","with","have","for","are","what","when","where","why","how","can","will","would","could","should","please","thanks","hello","hey","good","great","today","tomorrow","now","later","because","about","from","just","really","very","love","like","want","need","know","think","make","going","come","home","work","friend","family","message","send","open","close","search","download","share","favorite","bookmark","history","keyboard","browser","testing","test","project")
    fun suggest(text:String):List<String>{val token=text.trimEnd().split(Regex("\\s+")).lastOrNull().orEmpty().lowercase().filter{it.isLetter()};if(token.length<2)return emptyList();return words.filter{it.startsWith(token)&&it!=token}.take(4)}
}

/** Conservative offline resolver retained as a compatibility fallback for future glide typing. */
object PhormiGlideEngine {
    private val dictionary=("hello help hey hi how what why where when thanks thankyou please sorry love lovely happy happiness sad friend friends family home work good great awesome amazing cool nice okay yes no maybe today tomorrow yesterday morning night now later soon welcome congratulations congrats birthday party celebrate food hungry coffee water music movie phone keyboard browser internet website google youtube github nigeria lagos phormi create emoji sticker download upload share search find open close save favorite bookmark history tab tabs group private ghost settings security password account message messages typing type write writing example testing test android iphone apple computer school student business project time day week month year money free local ai image photo video camera voice call chat whatsapp tiktok instagram facebook twitter").split(" ").toSet()
    fun resolve(path:String):String{val clean=path.lowercase().filter{it in 'a'..'z'};if(clean.isBlank())return clean;dictionary.minByOrNull{distance(clean,it)}?.let{best->if(distance(clean,best)<=maxOf(1,clean.length/3))return best};return clean}
    private fun distance(a:String,b:String):Int{val dp=Array(a.length+1){IntArray(b.length+1)};for(i in 0..a.length)dp[i][0]=i;for(j in 0..b.length)dp[0][j]=j;for(i in 1..a.length)for(j in 1..b.length)dp[i][j]=minOf(dp[i-1][j]+1,dp[i][j-1]+1,dp[i-1][j-1]+if(a[i-1]==b[j-1])0 else 1);return dp[a.length][b.length]}
}
