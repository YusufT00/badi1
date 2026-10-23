package com.example.asistan

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class AsistanService : Service() {

    companion object {
        // Sırayla denenen Gemini modelleri. Biri bulunamazsa (404) ya da sınıra takılırsa (429) sıradakine geçer.
        val MODELLER = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-flash-lite-latest",
            "gemini-2.5-flash-lite"
        )

        // Uyandırma kelimesi gibi anlaşılabilecek yazılışlar (küçük harf, Türkçe)
        val UYANMA_KELIMELERI = listOf("badi", "badı", "baddi", "baddy", "buddy", "bady")
    }

    private enum class Mod { UYKU, AKTIF }

    private data class Kisi(val ad: String, val numara: String)
    private class Karar(val islem: String, val cevap: String, val kisi: String, val mesaj: String)

    private lateinit var wm: WindowManager
    private var panel: TextView? = null
    private var bubble: TextView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var bekleyenKonusma: Pair<String, Boolean>? = null
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tr = Locale("tr", "TR")

    private var running = false
    private var mod = Mod.UYKU
    private var emptyCount = 0
    private var uykuyaGec = false
    private var dinlemeNesli = 0
    private val gecmis = ArrayList<Pair<String, String>>() // "user" / "model"
    @Volatile private var calisanModel: String? = null

    // WhatsApp mesaj akışı
    private var acikSohbet: Kisi? = null
    private var taslak: String? = null
    private var adaylar: List<Kisi>? = null
    private var bekleyenMesaj: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "DURDUR") {
            stopAll()
            return START_NOT_STICKY
        }

        startAsForeground()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "badi:dinleme").apply { acquire() }
        }
        baslatTts()

        if (intent?.getBooleanExtra("cagir", false) == true) uyan(null) else uyku()
        return START_STICKY
    }

    // ---------- Modlar ----------

    /** Arka planda sadece "Badi" kelimesini bekler. */
    private fun uyku() {
        mod = Mod.UYKU
        uykuyaGec = false
        taslak = null
        acikSohbet = null
        adaylar = null
        bekleyenMesaj = null
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        panelKaldir()
        baloncukKaldir()
        startListening()
    }

    /** Uyandırma kelimesi duyuldu (ya da asistan düğmesiyle çağrıldı). */
    private fun uyan(duyulan: String?) {
        mod = Mod.AKTIF
        emptyCount = 0
        uykuyaGec = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        baloncukKaldir()

        val kalan = if (duyulan == null) "" else temizle(duyulan)
        if (kalan.length >= 3) {
            panelGoster(kalan)
            handle(kalan)
        } else {
            val ad = getSharedPreferences("ayar", MODE_PRIVATE).getString("ad", "") ?: ""
            say(if (ad.isBlank()) "Efendim" else "Efendim $ad")
        }
    }

    private fun uyanmaVarMi(t: String): Boolean {
        val n = t.lowercase(tr).filter { it.isLetter() }
        return UYANMA_KELIMELERI.any { n.contains(it) }
    }

    private fun temizle(t: String): String {
        var s = t.lowercase(tr)
        for (w in UYANMA_KELIMELERI) s = s.replace(w, " ")
        s = s.replace(Regex("bakar\\s*m[ıi]s[ıi]n([ıi]z)?"), " ")
        s = s.replace(Regex("[,.!?]"), " ")
        return s.trim().replace(Regex("\\s+"), " ")
    }

    // ---------- Konuşma (TTS) ----------

    private fun baslatTts() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = tr
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { handler.post { konusmaBitti() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { handler.post { konusmaBitti() } }
                })
                ttsReady = true
                bekleyenKonusma?.let { say(it.first, it.second) }
                bekleyenKonusma = null
            }
        }
    }

    private fun say(text: String, panelde: Boolean = true) {
        recognizer?.cancel()
        dinlemeNesli++
        if (panelde) panelGoster(text)
        if (!ttsReady) {
            bekleyenKonusma = Pair(text, panelde)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    private fun konusmaBitti() {
        if (!running) return
        if (uykuyaGec) {
            uyku()
        } else if (mod == Mod.AKTIF) {
            startListening()
        }
    }

    // ---------- Dinleme ----------

    private fun startListening() {
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (mod == Mod.AKTIF) panelGoster("Bu telefonda ses tanıma yok")
            return
        }
        recognizer?.destroy()
        val nesil = ++dinlemeNesli
        val buMod = mod

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (nesil == dinlemeNesli && buMod == Mod.AKTIF) setText("Dinliyorum...")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partial: Bundle?) {
                    if (nesil != dinlemeNesli || buMod != Mod.AKTIF) return
                    val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) setText(t)
                }

                override fun onResults(results: Bundle?) {
                    if (nesil != dinlemeNesli) return
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (buMod == Mod.UYKU) {
                        if (!t.isNullOrBlank() && uyanmaVarMi(t)) uyan(t) else uykuTekrar(300)
                    } else {
                        if (t.isNullOrBlank()) relisten() else handle(t)
                    }
                }

                override fun onError(error: Int) {
                    if (nesil != dinlemeNesli) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stopAll()
                        return
                    }
                    if (buMod == Mod.UYKU) {
                        val bekle = when (error) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                            10 -> 3000L // ERROR_TOO_MANY_REQUESTS
                            else -> 300L
                        }
                        uykuTekrar(bekle)
                    } else {
                        relisten()
                    }
                }
            })
        }

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, buMod == Mod.UYKU)
        }
        recognizer?.startListening(i)
    }

    private fun uykuTekrar(gecikme: Long) {
        handler.postDelayed({
            if (running && mod == Mod.UYKU) startListening()
        }, gecikme)
    }

    private fun relisten() {
        emptyCount++
        if (emptyCount >= 6) {
            uyku()
            return
        }
        handler.postDelayed({ if (running && mod == Mod.AKTIF) startListening() }, 600)
    }

    // ---------- Komut yönlendirme ----------

    private fun normTr(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase(tr)) {
            sb.append(
                when (c) {
                    'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'
                    'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'
                    else -> c
                }
            )
        }
        return sb.toString().filter { it.isLetterOrDigit() || it == ' ' }.trim()
    }

    private fun norm(s: String): String = s.lowercase(tr).filter { it.isLetterOrDigit() }

    private fun handle(spoken: String) {
        emptyCount = 0
        val t = spoken.lowercase(tr)
        val words = t.split(" ", "'", "’").filter { it.isNotBlank() }

        // 0) "Hangi Ahmet?" sorusuna verilen cevap
        val aday = adaylar
        if (aday != null) {
            adaylar = null
            val m = bekleyenMesaj
            bekleyenMesaj = null
            val sec = adayCoz(aday, t)
            when {
                sec == null -> say("Anlayamadım, vazgeçtim")
                m != null -> mesajiHazirla(sec, m)
                else -> sohbetiAc(sec)
            }
            return
        }

        // 1) Uyku
        val uykuKelime = listOf("görüşürüz", "hoşça kal", "hoşçakal", "uyu", "sus", "çıkış")
        if ((words.size <= 4 && uykuKelime.any { t.contains(it) }) || (uyanmaVarMi(t) && t.contains("kapat"))) {
            uykuyaGec = true
            say("Görüşürüz")
            return
        }

        // 2) Hazır mesaj taslağı varken: gönder / iptal
        if (taslak != null) {
            if (t.contains("iptal") || t.contains("vazgeç") || t.contains("gönderme")) {
                BadiErisilebilirlik.instance?.metinYaz("")
                taslak = null
                say("Tamam, vazgeçtim")
                return
            }
            if (gonderMi(t, words)) {
                gonder()
                return
            }
        } else if (gonderMi(t, words)) {
            say("Gönderilecek bir mesaj yok")
            return
        }

        // 3) Uygulama kapat
        if (words.size <= 5 && words.any { it.startsWith("kapat") }) {
            kapatKomutu(t)
            return
        }

        // 4) Uygulama aç
        if (words.contains("aç") || t.contains("açar mısın") || t.contains("açar misin")) {
            if (openApp(t)) return
        }

        // 5) Ekranda işlem: geri, ana ekran, kaydır, tıkla
        if (ekranKomutu(t, words)) return

        // 6) Geri kalan her şey: sohbet ya da WhatsApp mesajı (Gemini karar verir)
        beyin(spoken)
    }

    private fun gonderMi(t: String, words: List<String>): Boolean =
        words.size <= 3 && words.any { it == "gönder" || it == "yolla" } && !t.contains("tıkla")

    // ---------- Uygulama bulma / açma / kapatma ----------

    private fun appBul(q: String): Pair<String, String>? {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val best = pm.queryIntentActivities(launcher, 0).mapNotNull { ri ->
            val label = ri.loadLabel(pm).toString()
            val n = norm(label)
            val key = if (n.length > 4) n.dropLast(1) else n
            if (key.length >= 3 && q.contains(key)) Triple(ri.activityInfo.packageName, label, key.length) else null
        }.maxByOrNull { it.third } ?: return null
        return Pair(best.first, best.second)
    }

    private fun openApp(spoken: String): Boolean {
        val best = appBul(norm(spoken)) ?: return false
        val launch = packageManager.getLaunchIntentForPackage(best.first) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        baloncukGoster()
        say("${best.second} açılıyor", panelde = false)
        return true
    }

    private fun anaEkranPaketi(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    private fun etiket(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("Uygulama")

    private fun kapatKomutu(t: String) {
        val erisim = BadiErisilebilirlik.instance
        val ust = erisim?.ustPaket()
        val bulunan = appBul(norm(t))

        val pkg: String
        val ad: String
        if (bulunan != null) {
            pkg = bulunan.first
            ad = bulunan.second
        } else if (ust != null && ust != packageName && ust != "com.android.systemui" && ust != anaEkranPaketi()) {
            pkg = ust
            ad = etiket(ust)
        } else {
            say(
                if (erisim == null) "Hangi uygulamayı kapatayım? Önde olanı kapatabilmem için erişilebilirlik izni gerekiyor"
                else "Hangi uygulamayı kapatayım?"
            )
            return
        }

        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (pkg == ust) {
            // Önce ana ekrana dön (arka plana at), sonra süreci sonlandır
            erisim?.anaEkran()
            handler.postDelayed({ am.killBackgroundProcesses(pkg) }, 900)
        } else {
            am.killBackgroundProcesses(pkg)
        }
        say("$ad kapatıldı", panelde = false)
    }

    // ---------- Ekranda işlem (erişilebilirlik) ----------

    private fun ekranKomutu(t: String, words: List<String>): Boolean {
        val tetik = setOf("tıkla", "dokun", "bas")
        val ekranIsi = (t.contains("ana ekran") || t.contains("ana sayfa") || t.contains("kaydır") ||
            (words.size <= 3 && words.contains("geri")) || words.any { it in tetik })
        if (!ekranIsi) return false

        val erisim = BadiErisilebilirlik.instance
        if (erisim == null) {
            say("Bunun için Ayarlar'dan erişilebilirlikte Badi'yi açman gerekiyor")
            return true
        }
        when {
            t.contains("ana ekran") || t.contains("ana sayfa") -> {
                erisim.anaEkran()
                say("Ana ekran", panelde = false)
            }
            t.contains("kaydır") -> {
                val ok = erisim.kaydir(!t.contains("yukarı"))
                say(if (ok) "Kaydırdım" else "Kaydırılacak bir yer bulamadım", panelde = false)
            }
            words.size <= 3 && words.contains("geri") -> {
                erisim.geri()
                say("Geri", panelde = false)
            }
            else -> {
                val gereksiz = tetik + setOf("üstüne", "butonuna", "düğmesine", "tuşuna", "şuna", "ona")
                val hedef = words.filterNot { it in gereksiz }
                var ok = hedef.isNotEmpty() && erisim.metneTikla(hedef.joinToString(" "))
                if (!ok) {
                    // "Ara'ya tıkla" gibi ekli kelimeler için tek tek dene
                    for (w in hedef.sortedByDescending { it.length }) {
                        if (w.length >= 3 && erisim.metneTikla(w)) { ok = true; break }
                    }
                }
                say(if (ok) "Tamamdır" else "Ekranda bunu bulamadım", panelde = false)
            }
        }
        return true
    }

    // ---------- WhatsApp ----------

    private fun wpPaket(): String? = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { p ->
        runCatching { packageManager.getPackageInfo(p, 0) }.isSuccess
    }

    private fun wpNumara(ham: String): String {
        var d = ham.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)
        else if (d.startsWith("0")) d = "90" + d.drop(1)
        else if (d.length == 10 && d.startsWith("5")) d = "90$d"
        return d
    }

    private fun kisiBul(istenen: String): List<Kisi> {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val hedef = normTr(istenen)
        if (hedef.isEmpty()) return emptyList()

        val hepsi = LinkedHashMap<String, Kisi>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val ad = c.getString(0) ?: continue
                val no = c.getString(1) ?: continue
                if (!hepsi.containsKey(ad)) hepsi[ad] = Kisi(ad, no)
            }
        }
        val liste = hepsi.values.toList()
        val tam = liste.filter { normTr(it.ad) == hedef }
        if (tam.isNotEmpty()) return tam
        val bas = liste.filter { k ->
            val n = normTr(k.ad)
            n.startsWith(hedef) || n.split(" ").any { it == hedef }
        }
        if (bas.isNotEmpty()) return bas
        return liste.filter { normTr(it.ad).contains(hedef) }
    }

    private fun adayCoz(adaylar: List<Kisi>, t: String): Kisi? {
        val parcalar = normTr(t).split(" ").filter { it.length >= 2 }
        val skorlu = adaylar.map { k ->
            val n = normTr(k.ad)
            k to parcalar.count { n.contains(it) }
        }
        val enIyi = skorlu.maxByOrNull { it.second } ?: return null
        if (enIyi.second == 0) return null
        return if (skorlu.count { it.second == enIyi.second } == 1) enIyi.first else null
    }

    /** Kişiyi seçer; bulamazsa ya da birden fazlaysa kullanıcıya sorup null döner. */
    private fun kisiSec(istenen: String, mesaj: String?): Kisi? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            say("Rehbere erişim iznim yok. Uygulamadan birinci adımdaki izinleri ver")
            return null
        }
        val liste = kisiBul(istenen)
        return when {
            liste.isEmpty() -> { say("$istenen adında bir kişi bulamadım"); null }
            liste.size == 1 -> liste[0]
            else -> {
                adaylar = liste.take(5)
                bekleyenMesaj = mesaj
                val adlar = liste.take(3).joinToString(", ") { it.ad }
                say("Birden fazla kişi var: $adlar. Hangisi?")
                null
            }
        }
    }

    private fun wpSohbetAc(k: Kisi, metin: String?): Boolean {
        val pkg = wpPaket() ?: run { say("Bu telefonda WhatsApp kurulu değil"); return false }
        var url = "whatsapp://send?phone=${wpNumara(k.numara)}"
        if (!metin.isNullOrBlank()) url += "&text=" + Uri.encode(metin)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return true
    }

    private fun sohbetiAc(k: Kisi) {
        if (!wpSohbetAc(k, null)) return
        acikSohbet = k
        taslak = null
        baloncukGoster()
        say("${k.ad} ile sohbeti açtım", panelde = false)
    }

    private fun mesajiHazirla(k: Kisi, mesaj: String) {
        if (!wpSohbetAc(k, mesaj)) return
        acikSohbet = k
        taslak = mesaj
        gecmis.add(Pair("model", "${k.ad} için mesaj hazırladım: $mesaj"))
        baloncukGoster()
        say("${k.ad} için şunu yazdım: $mesaj. Göndereyim mi?", panelde = false)
    }

    private fun taslagiGuncelle(mesaj: String) {
        val k = acikSohbet ?: return
        taslak = mesaj
        val erisim = BadiErisilebilirlik.instance
        val ust = erisim?.ustPaket()
        val wpOnde = ust == "com.whatsapp" || ust == "com.whatsapp.w4b"
        if (!(wpOnde && erisim?.metinYaz(mesaj) == true)) {
            wpSohbetAc(k, mesaj)
        }
        gecmis.add(Pair("model", "Taslağı güncelledim: $mesaj"))
        say("Şöyle yazdım: $mesaj. Göndereyim mi?", panelde = false)
    }

    private fun gonder() {
        val erisim = BadiErisilebilirlik.instance
        if (erisim == null) {
            say("Göndermek için Ayarlar'dan erişilebilirlikte Badi'yi açman gerekiyor")
            return
        }
        val ust = erisim.ustPaket()
        if (ust != "com.whatsapp" && ust != "com.whatsapp.w4b") {
            val k = acikSohbet
            val m = taslak
            if (k == null || m == null) {
                say("Gönderilecek bir mesaj yok")
                return
            }
            // Kullanıcı başka yere geçmiş: sohbeti taslakla tekrar aç, biraz bekleyip gönder
            wpSohbetAc(k, m)
            handler.postDelayed({ gonderTikla() }, 2500)
            return
        }
        gonderTikla()
    }

    private fun gonderTikla() {
        val ok = BadiErisilebilirlik.instance?.gonderTikla() == true
        if (ok) {
            taslak = null
            say("Gönderdim", panelde = false)
        } else {
            say("Gönder düğmesini bulamadım", panelde = false)
        }
    }

    // ---------- Yapay zeka (Gemini) ----------

    private fun baglamMetni(): String {
        val sb = StringBuilder()
        val ust = BadiErisilebilirlik.instance?.ustPaket()
        if (ust == "com.whatsapp" || ust == "com.whatsapp.w4b") sb.append("WhatsApp şu an ön planda. ")
        acikSohbet?.let { sb.append("Açık sohbet: ${it.ad}. ") }
        taslak?.let {
            sb.append(
                "Gönderilmemiş taslak: \"$it\". Kullanıcı taslağı düzeltmek veya değiştirmek istiyorsa " +
                    "islem=whatsapp_mesaj, kisi boş, mesaj alanına tam yeni metni yaz. "
            )
        }
        return sb.toString()
    }

    private fun beyin(soru: String) {
        recognizer?.cancel()
        dinlemeNesli++
        panelGoster("Düşünüyorum...")
        gecmis.add(Pair("user", soru))
        val kopya = gecmis.toList()
        val baglam = baglamMetni()

        thread {
            var karar: Karar? = null
            var hata: String? = null
            try {
                karar = geminiSor(kopya, baglam)
            } catch (e: Exception) {
                hata = e.message ?: "hata"
            }
            handler.post {
                if (!running) return@post
                if (karar == null) hataSoyle(hata) else kararUygula(karar)
            }
        }
    }

    private fun hataSoyle(hata: String?) {
        gecmis.removeLastOrNull()
        val kod = hata?.let { Regex("HTTP (\\d+)").find(it)?.groupValues?.get(1) }
        val kisa = when {
            hata == null -> "Bir sorun oldu"
            hata.contains("ANAHTAR_YOK") -> "Yapay zeka anahtarı uygulamaya eklenmemiş"
            kod == "400" || kod == "403" -> "Yapay zeka anahtarı geçersiz ya da izinli değil"
            kod == "404" -> "Yapay zeka modeli bulunamadı"
            kod == "429" -> "Kullanım sınırına takıldım, biraz bekle"
            hata.contains("BOS") -> "Yapay zeka cevap vermedi, tekrar dener misin"
            else -> "İnternete bağlanamadım"
        }
        say(kisa)
        panelGoster("$kisa (${hata ?: ""})")
    }

    private fun kararUygula(k: Karar) {
        when (k.islem) {
            "whatsapp_mesaj" -> {
                val mesaj = k.mesaj.trim()
                if (mesaj.isBlank()) {
                    gecmis.removeLastOrNull()
                    say("Mesajı anlayamadım, tekrar söyler misin?")
                } else if (k.kisi.isBlank() && taslak != null) {
                    taslagiGuncelle(mesaj)
                } else {
                    val ad = k.kisi.ifBlank { acikSohbet?.ad ?: "" }
                    if (ad.isBlank()) {
                        gecmis.removeLastOrNull()
                        say("Mesajı kime yazayım?")
                    } else {
                        val kisi = kisiSec(ad, mesaj)
                        if (kisi != null) mesajiHazirla(kisi, mesaj)
                    }
                }
            }
            "whatsapp_ac" -> {
                if (k.kisi.isBlank()) {
                    gecmis.removeLastOrNull()
                    say("Kimin sohbetini açayım?")
                } else {
                    val kisi = kisiSec(k.kisi, null)
                    if (kisi != null) {
                        gecmis.add(Pair("model", "${kisi.ad} sohbetini açtım"))
                        sohbetiAc(kisi)
                    }
                }
            }
            else -> {
                val c = k.cevap.ifBlank { "Anlayamadım, tekrar söyler misin?" }
                gecmis.add(Pair("model", c))
                while (gecmis.size > 20) { gecmis.removeAt(0); gecmis.removeAt(0) }
                say(c)
            }
        }
    }

    private fun geminiSor(mesajlar: List<Pair<String, String>>, baglam: String): Karar {
        val key = BuildConfig.GEMINI_API_KEY
        if (key.isBlank()) throw IllegalStateException("ANAHTAR_YOK")

        val ad = getSharedPreferences("ayar", MODE_PRIVATE).getString("ad", "") ?: ""
        val sistem = "Sen Badi'sin, kullanıcının telefonundaki sesli yardımcısın. " +
            (if (ad.isNotBlank()) "Kullanıcının adı $ad. " else "") +
            "Kullanıcının son cümlesini değerlendir ve SADECE şu JSON'u döndür: " +
            "{\"islem\":\"sohbet\",\"cevap\":\"\",\"kisi\":\"\",\"mesaj\":\"\"}. Kurallar: " +
            "1) Gündelik sohbet, soru, bilgi isteği: islem=\"sohbet\". cevap alanına Türkçe, samimi ve doğal, " +
            "en fazla 2-3 cümlelik cevabı yaz. Cevap sesli okunacak: markdown, liste, emoji kullanma. " +
            "2) Kullanıcı birine WhatsApp mesajı yazılmasını istiyorsa: islem=\"whatsapp_mesaj\". " +
            "kisi = kişinin adı, ek almadan söylendiği gibi (\"Ahmet'e\" -> \"Ahmet\", \"anneme\" -> \"anne\"). " +
            "mesaj = kullanıcının ağzından, alıcıya hitap eden kısa ve doğal mesaj metni. Kullanıcının söylediğini " +
            "düzgün cümleye çevir, uydurma bilgi ekleme. Kullanıcı \"şunu yaz\" diyerek tam metin verdiyse neredeyse aynen yaz. " +
            "cevap boş kalsın. " +
            "3) Kullanıcı sadece bir kişinin WhatsApp sohbetini açmak istiyorsa (\"Ahmet'in mesajlarına gir\", " +
            "WhatsApp açıkken sadece bir isim söylemesi): islem=\"whatsapp_ac\", kisi doldur. " +
            "4) Telefonda şunları yapabilirsin: uygulama açma ve kapatma, geri, ana ekran, kaydırma, ekrandaki yazıya " +
            "dokunma, WhatsApp mesajı hazırlama ve gönderme. Başka telefon işlemleri istenirse (arama, alarm vb.) " +
            "bunu henüz yapamadığını dürüstçe söyle. " + baglam

        val contents = JSONArray()
        for ((rol, metin) in mesajlar) {
            contents.put(
                JSONObject().put("role", rol)
                    .put("parts", JSONArray().put(JSONObject().put("text", metin)))
            )
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sistem))))
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("maxOutputTokens", 2048).put("temperature", 0.7)
                    .put("responseMimeType", "application/json")
            )

        val denenecek = calisanModel?.let { m -> listOf(m) + MODELLER.filter { it != m } } ?: MODELLER
        var sonHata = "HTTP 0"
        for (model in denenecek) {
            val (kod, metin) = gemini(model, key, body)
            if (kod in 200..299) {
                calisanModel = model
                return kararCoz(metin)
            }
            val ayrinti = runCatching {
                JSONObject(metin).getJSONObject("error").optString("message")
            }.getOrDefault("")
            sonHata = "HTTP $kod ($model) ${ayrinti.take(100)}"
            if (kod != 404 && kod != 429) break
        }
        throw IllegalStateException(sonHata)
    }

    private fun gemini(model: String, key: String, body: JSONObject): Pair<Int, String> {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val kod = conn.responseCode
            val akim = if (kod in 200..299) conn.inputStream else conn.errorStream
            val metin = akim?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(kod, metin)
        } finally {
            conn.disconnect()
        }
    }

    private fun kararCoz(metin: String): Karar {
        val adaylarJson = JSONObject(metin).optJSONArray("candidates")
        if (adaylarJson == null || adaylarJson.length() == 0) throw IllegalStateException("BOS")
        val parcalar = adaylarJson.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("BOS")
        val sb = StringBuilder()
        for (i in 0 until parcalar.length()) sb.append(parcalar.getJSONObject(i).optString("text"))
        val ham = sb.toString().trim()
        if (ham.isEmpty()) throw IllegalStateException("BOS")

        val temiz = ham.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val o = JSONObject(temiz)
            Karar(o.optString("islem", "sohbet"), o.optString("cevap"), o.optString("kisi"), o.optString("mesaj"))
        } catch (e: Exception) {
            Karar("sohbet", ham, "", "")
        }
    }

    // ---------- Ekrandaki arayüz ----------

    private fun canOverlay() = Settings.canDrawOverlays(this)

    private fun overlayParams(gravity: Int, x: Int, y: Int, w: Int, h: Int) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun panelGoster(text: String) {
        if (!canOverlay()) return
        baloncukKaldir()
        if (panel == null) {
            panel = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                maxLines = 8
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = resources.displayMetrics.widthPixels - dp(32)
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6202030"))
                    cornerRadius = dp(24).toFloat()
                }
            }
            wm.addView(
                panel,
                overlayParams(
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(48),
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
                )
            )
        }
        panel?.text = text
    }

    private fun setText(text: String) { panel?.text = text }

    private fun panelKaldir() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun baloncukGoster() {
        panelKaldir()
        if (!canOverlay() || bubble != null) return
        bubble = TextView(this).apply {
            text = "AI"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC5B5BD6"))
            }
            setOnClickListener {
                emptyCount = 0
                baloncukKaldir()
                panelGoster("Dinliyorum...")
                startListening()
            }
        }
        wm.addView(bubble, overlayParams(Gravity.END or Gravity.CENTER_VERTICAL, 0, 0, dp(56), dp(56)))
    }

    private fun baloncukKaldir() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    // ---------- Servis yaşam döngüsü ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("asistan", "Badi", NotificationManager.IMPORTANCE_LOW)
        )
        val durdur = PendingIntent.getService(
            this, 0,
            Intent(this, AsistanService::class.java).setAction("DURDUR"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "asistan")
            .setContentTitle("Badi dinlemede")
            .setContentText("\"Badi, bakar mısın\" diyerek çağırabilirsin")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Durdur", durdur
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun stopAll() {
        running = false
        dinlemeNesli++
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
        panelKaldir()
        baloncukKaldir()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopAll()
        super.onDestroy()
    }
}
