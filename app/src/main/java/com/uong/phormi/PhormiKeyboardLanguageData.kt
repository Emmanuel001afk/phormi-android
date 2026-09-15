package com.uong.phormi

import java.util.Locale

/** Compact offline starter lexicons for major keyboard languages. Android/system spell-check data can supplement these where available. */
object PhormiKeyboardLanguageData {
    private val packs: Map<String, List<String>> = mapOf(
        "en" to "the and you your that this with have for are what when where why how can will would could should please thanks hello good great today tomorrow now later because about from just really very love like want need know think make going come home work friend family message send open close search download share save".split(" "),
        "fr" to "le la les un une des et de du dans pour avec vous nous je tu il elle ils elles que qui quoi comment pourquoi quand où bon bonne bonjour merci beaucoup très bien aujourd hui demain amour ami famille maison travail faire aller venir vouloir pouvoir savoir penser besoin".split(" "),
        "es" to "el la los las un una unos unas y de del en para con por que qué quien cómo cuándo dónde porqué hola gracias mucho muy bien buenos días mañana hoy amor amigo familia casa trabajo hacer ir venir querer poder saber pensar necesito".split(" "),
        "pt" to "o a os as um uma uns umas e de do da em para com por que quem como quando onde olá obrigado obrigada muito muito bem hoje amanhã amor amigo família casa trabalho fazer ir vir querer poder saber pensar preciso".split(" "),
        "de" to "der die das ein eine und oder aber von zu in für mit auf an bei ich du er sie wir ihr sie was wer wie wann wo warum hallo danke bitte sehr gut heute morgen liebe freund familie haus arbeit machen gehen kommen wollen können wissen denken brauchen".split(" "),
        "it" to "il lo la i gli le un una e di del della in per con da che chi come quando dove perché ciao grazie prego molto bene oggi domani amore amico famiglia casa lavoro fare andare venire volere potere sapere pensare bisogno".split(" "),
        "id" to "yang dan di ke dari untuk dengan ini itu saya kamu dia kita kami mereka apa siapa bagaimana kapan dimana mengapa halo terima kasih sangat baik hari ini besok cinta teman keluarga rumah kerja membuat pergi datang mau bisa tahu pikir perlu".split(" "),
        "tr" to "bir ve bu şu için ile de da ben sen o biz siz onlar ne kim nasıl neden ne zaman nerede merhaba teşekkür lütfen çok iyi bugün yarın aşk arkadaş aile ev iş yapmak gitmek gelmek istemek bilmek düşünmek lazım".split(" "),
        "yo" to "mo iwọ ìwọ ó a awa ẹ̀yin wọn ati àti fún pé kí ni báwo bẹẹni rárá dáadáa pẹ̀lẹ́ ẹ ṣe mo dúpẹ́ ìfẹ́ ayọ̀ ọ̀rẹ́ ẹ̀gbọ́n ẹbí ilé iṣẹ́ ọ̀la òní ọjọ́ ṣe lọ wá fẹ́ mọ̀ rò fẹ́ràn".split(" "),
        "ig" to "m na gị ya anyị unu ha na nke bụ maka site n'ime ka ihe onye onyeozi kedu biko daalụ ee mba ọma taa echi ịhụnanya enyi ezinụlọ ụlọ ọrụ aga bia chọrọ nwere mara chee".split(" "),
        "ha" to "da na ni ga don cikin tare wannan wancan kai ke shi ita mu ku su me wa yaya yaushe ina me yasa sannu na gode don Allah eh a'a kyau yau gobe soyayya aboki iyali gida aiki je zo so sani tunani".split(" "),
        "sw" to "na ya ni kwa kwa kwa hii hiyo mimi wewe yeye sisi ninyi wao nini nani jinsi lini wapi kwa nini habari asante tafadhali ndiyo hapana nzuri leo kesho upendo rafiki familia nyumba kazi kufanya kwenda kuja kutaka kujua kufikiri".split(" "),
        "ar" to "ال و في من إلى على عن مع هذا هذه ذلك تلك أنا أنت هو هي نحن أنتم هم ماذا من كيف متى أين لماذا مرحبا شكرا من فضلك نعم لا جيد اليوم غدا حب صديق عائلة بيت عمل يفعل يذهب يأتي يريد يستطيع يعرف يفكر".split(" "),
        "hi" to "का के की और में से को पर यह वह मैं तुम आप हम वे क्या कौन कैसे कब कहाँ क्यों नमस्ते धन्यवाद कृपया हाँ नहीं अच्छा आज कल प्यार दोस्त परिवार घर काम करना जाना आना चाहना सकता जानना सोचना जरूरत".split(" "),
        "bn" to "এবং এই সেই আমি তুমি আপনি আমরা তারা কি কে কিভাবে কখন কোথায় কেন হ্যালো ধন্যবাদ দয়া করে হ্যাঁ না ভালো আজ কাল আগামীকাল ভালোবাসা বন্ধু পরিবার বাড়ি কাজ করা যাওয়া আসা চাই পারি জানা ভাবা দরকার".split(" "),
        "ur" to "اور یہ وہ میں تم آپ ہم وہ کیا کون کیسے کب کہاں کیوں سلام شکریہ براہ کرم ہاں نہیں اچھا آج کل کل محبت دوست خاندان گھر کام کرنا جانا آنا چاہنا سکتا جاننا سوچنا ضرورت".split(" "),
        "pa" to "ਅਤੇ ਇਹ ਉਹ ਮੈਂ ਤੁਸੀਂ ਅਸੀਂ ਉਹ ਕੀ ਕੌਣ ਕਿਵੇਂ ਕਦੋਂ ਕਿੱਥੇ ਕਿਉਂ ਸਤ ਸ੍ਰੀ ਅਕਾਲ ਧੰਨਵਾਦ ਜੀ ਹਾਂ ਨਹੀਂ ਚੰਗਾ ਅੱਜ ਕੱਲ੍ਹ ਭਲਕੇ ਪਿਆਰ ਦੋਸਤ ਪਰਿਵਾਰ ਘਰ ਕੰਮ ਕਰਨਾ ਜਾਣਾ ਆਉਣਾ ਚਾਹੁੰਦਾ ਸਕਦਾ ਜਾਣਨਾ ਸੋਚਣਾ".split(" "),
        "gu" to "અને આ તે હું તમે અમે તેઓ શું કોણ કેવી રીતે ક્યારે ક્યાં કેમ નમસ્તે આભાર કૃપા કરીને હા ના સારું આજે કાલે આવતીકાલે પ્રેમ મિત્ર પરિવાર ઘર કામ કરવું જવું આવવું ઈચ્છવું શકવું જાણવું વિચારવું".split(" "),
        "ta" to "மற்றும் இது அது நான் நீ நீங்கள் நாம் அவர்கள் என்ன யார் எப்படி எப்போது எங்கே ஏன் வணக்கம் நன்றி தயவு செய்து ஆம் இல்லை நல்ல இன்று நாளை காதல் நண்பர் குடும்பம் வீடு வேலை செய்ய போக வர விரும்ப அறிய நினைக்க வேண்டும்".split(" "),
        "te" to "మరియు ఇది అది నేను నువ్వు మీరు మేము వారు ఏమి ఎవరు ఎలా ఎప్పుడు ఎక్కడ ఎందుకు నమస్కారం ధన్యవాదాలు దయచేసి అవును కాదు మంచి ఈరోజు రేపు ప్రేమ స్నేహితుడు కుటుంబం ఇల్లు పని చేయు వెళ్ళు రా కావాలి తెలుసు ఆలోచించు".split(" "),
        "ml" to "കൂടാതെ ഇത് അത് ഞാൻ നീ നിങ്ങൾ ഞങ്ങൾ അവർ എന്ത് ആര് എങ്ങനെ എപ്പോൾ എവിടെ എന്തുകൊണ്ട് നമസ്കാരം നന്ദി ദയവായി അതെ ഇല്ല നല്ല ഇന്ന് നാളെ സ്നേഹം സുഹൃത്ത് കുടുംബം വീട് ജോലി ചെയ്യുക പോകുക വരുക വേണം അറിയുക ചിന്തിക്കുക".split(" "),
        "th" to "และ นี้ นั้น ฉัน คุณ เรา พวกเขา อะไร ใคร อย่างไร เมื่อไร ที่ไหน ทำไม สวัสดี ขอบคุณ กรุณา ใช่ ไม่ ดี วันนี้ พรุ่งนี้ รัก เพื่อน ครอบครัว บ้าน งาน ทำ ไป มา ต้องการ สามารถ รู้ คิด".split(" "),
        "vi" to "và này đó tôi bạn anh chị chúng tôi họ gì ai như thế nào khi nào ở đâu tại sao xin chào cảm ơn làm ơn có không tốt hôm nay ngày mai tình yêu bạn bè gia đình nhà công việc làm đi đến muốn có thể biết nghĩ cần".split(" "),
        "zh" to "的 了 和 是 我 你 您 他 她 我们 他们 什么 谁 怎么 什么时候 哪里 为什么 你好 谢谢 请 是的 不 好 今天 明天 爱 朋友 家庭 家 工作 做 去 来 想 要 能 知道 觉得 需要".split(" "),
        "ja" to "これ それ あれ 私 あなた 私たち 彼 彼女 何 誰 どう いつ どこ なぜ こんにちは ありがとう お願い はい いいえ 良い 今日 明日 愛 友達 家族 家 仕事 する 行く 来る 欲しい できる 知る 思う 必要".split(" "),
        "ko" to "그리고 이것 그것 나 너 당신 우리 그들 무엇 누구 어떻게 언제 어디 왜 안녕하세요 감사합니다 부탁합니다 네 아니요 좋아요 오늘 내일 사랑 친구 가족 집 일 하다 가다 오다 원하다 할수있다 알다 생각하다 필요".split(" "),
        "ru" to "и это тот я ты вы мы они что кто как когда где почему привет спасибо пожалуйста да нет хорошо сегодня завтра любовь друг семья дом работа делать идти приходить хотеть мочь знать думать нужно".split(" "),
        "uk" to "і це той я ти ви ми вони що хто як коли де чому привіт дякую будь ласка так ні добре сьогодні завтра любов друг сім'я дім робота робити йти приходити хотіти могти знати думати потрібно".split(" "),
        "pl" to "i to ten ja ty wy my oni co kto jak kiedy gdzie dlaczego cześć dziękuję proszę tak nie dobrze dziś jutro miłość przyjaciel rodzina dom praca robić iść przyjść chcieć móc wiedzieć myśleć potrzebować".split(" "),
        "nl" to "en dit dat ik jij u wij zij wat wie hoe wanneer waar waarom hallo bedankt alsjeblieft ja nee goed vandaag morgen liefde vriend familie huis werk doen gaan komen willen kunnen weten denken nodig".split(" "),
        "sv" to "och det den jag du ni vi de vad vem hur när var varför hej tack snälla ja nej bra idag imorgon kärlek vän familj hem arbete göra gå komma vilja kunna veta tänka behöver".split(" "),
        "no" to "og det den jeg du dere vi de hva hvem hvordan når hvor hvorfor hei takk vær så snill ja nei bra i dag i morgen kjærlighet venn familie hjem arbeid gjøre gå komme ville kunne vite tenke trenger".split(" "),
        "da" to "og det den jeg du De vi de hvad hvem hvordan hvornår hvor hvorfor hej tak venligst ja nej god i dag i morgen kærlighed ven familie hjem arbejde gøre gå komme ville kunne vide tænke behøver".split(" "),
        "fi" to "ja tämä tuo minä sinä te me he mitä kuka miten milloin missä miksi hei kiitos ole hyvä kyllä ei hyvä tänään huomenna rakkaus ystävä perhe koti työ tehdä mennä tulla haluta voida tietää ajatella tarvita".split(" "),
        "cs" to "a to ten já ty vy my oni co kdo jak kdy kde proč ahoj děkuji prosím ano ne dobře dnes zítra láska přítel rodina domov práce dělat jít přijít chtít moci vědět myslet potřebovat".split(" "),
        "ro" to "și acest aceea eu tu voi noi ei ce cine cum când unde de ce salut mulțumesc te rog da nu bine azi mâine iubire prieten familie casă muncă face merge veni vrea poate ști gândi nevoie".split(" "),
        "hu" to "és ez az én te ön mi ők miért hogyan mikor hol miért szia köszönöm kérem igen nem jó ma holnap szeretet barát család otthon munka csinál megy jön akar tud gondol szükség".split(" "),
        "el" to "και αυτό εκεί εγώ εσύ εσείς εμείς αυτοί τι ποιος πώς πότε πού γιατί γεια ευχαριστώ παρακαλώ ναι όχι καλά σήμερα αύριο αγάπη φίλος οικογένεια σπίτι δουλειά κάνω πηγαίνω έρχομαι θέλω μπορώ ξέρω σκέφτομαι χρειάζομαι".split(" "),
        "he" to "ו זה זאת אני אתה את אתם אנחנו הם מה מי איך מתי איפה למה שלום תודה בבקשה כן לא טוב היום מחר אהבה חבר משפחה בית עבודה לעשות ללכת לבוא רוצה יכול יודע חושב צריך".split(" ")
    )

    fun words(locale: Locale): List<String> = packs[locale.language] ?: packs["en"].orEmpty()
    fun supportedLanguages(): List<String> = packs.keys.toList()
    fun label(language: String): String = Locale(language).getDisplayLanguage(Locale.getDefault()).ifBlank { language }
}
