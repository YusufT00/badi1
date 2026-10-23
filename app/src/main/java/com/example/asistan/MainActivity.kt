package com.example.asistan

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

// E-posta adresinden ismi çıkarır: "yusuf.tekin42@gmail.com" -> "Yusuf"
fun adDan(mail: String): String {
    val tr = Locale("tr", "TR")
    val yerel = mail.substringBefore("@")
    val ilk = yerel.split('.', '_', '-', '+').firstOrNull { p -> p.any { it.isLetter() } } ?: return ""
    val harfler = ilk.filter { it.isLetter() }
    if (harfler.isEmpty()) return ""
    return harfler.lowercase(tr).replaceFirstChar { it.titlecase(tr) }
}

class MainActivity : Activity() {

    private lateinit var durum: TextView

    private fun durumGuncelle() {
        val prefs = getSharedPreferences("ayar", MODE_PRIVATE)
        val mail = prefs.getString("mail", "") ?: ""
        val ad = prefs.getString("ad", "") ?: ""
        val erisim = if (BadiErisilebilirlik.instance != null) "açık" else "kapalı"
        durum.text = (if (mail.isBlank()) "E-posta: SEÇİLMEDİ (zorunlu)" else "E-posta: $mail\nAdın: $ad") +
            "\nErişilebilirlik: $erisim"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ayar", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

        root.addView(TextView(this).apply { text = "Badi kurulumu"; textSize = 24f })
        durum = TextView(this).apply { setPadding(0, 24, 0, 24) }
        root.addView(durum)

        root.addView(btn("1) Mikrofon, bildirim ve rehber izni ver") {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_CONTACTS
                ), 1
            )
        })

        root.addView(btn("2) Diğer uygulamaların üzerinde göster izni") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        root.addView(btn("3) E-posta hesabını seç (zorunlu)") {
            val i = AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"), null, null, null, null
            )
            startActivityForResult(i, 2)
        })

        root.addView(btn("4a) Uygulama bilgisi (⋮ > Kısıtlı ayarlara izin ver)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })

        root.addView(btn("4b) Erişilebilirlik ayarlarını aç, Badi'yi etkinleştir") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(btn("5) Pil kısıtlamasını kaldır (arka planda çalışsın)") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Zaten kaldırılmış", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        })

        root.addView(btn("6) Varsayılan asistan yap (isteğe bağlı)") {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        })

        root.addView(btn("BAŞLAT: Badi'yi dinlemeye al") {
            val mail = prefs.getString("mail", "") ?: ""
            when {
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                    Toast.makeText(this, "Önce 1. adımdaki izinleri ver", Toast.LENGTH_LONG).show()
                mail.isBlank() ->
                    Toast.makeText(this, "Önce 3. adımda e-posta hesabını seç", Toast.LENGTH_LONG).show()
                else -> {
                    prefs.edit().putString("ad", adDan(mail)).apply()
                    startForegroundService(Intent(this, AsistanService::class.java))
                    Toast.makeText(this, "Badi dinlemede. \"Badi, bakar mısın\" de.", Toast.LENGTH_LONG).show()
                }
            }
        })

        root.addView(btn("DURDUR: Badi'yi tamamen kapat") {
            stopService(Intent(this, AsistanService::class.java))
        })

        setContentView(ScrollView(this).apply { addView(root) })
        durumGuncelle()
    }

    override fun onResume() {
        super.onResume()
        durumGuncelle()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK) {
            val mail = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return
            getSharedPreferences("ayar", MODE_PRIVATE).edit()
                .putString("mail", mail)
                .putString("ad", adDan(mail))
                .apply()
            durumGuncelle()
        }
    }
}
