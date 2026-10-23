package com.example.asistan

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Diğer uygulamaların ekranında dokunma, kaydırma, yazma yapar.
 * Kullanıcı Ayarlar > Erişilebilirlik'ten açmalıdır.
 */
class BadiErisilebilirlik : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BadiErisilebilirlik? = null
    }

    private val tr = Locale("tr", "TR")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private fun norm(s: String?): String = (s ?: "").lowercase(tr).filter { it.isLetterOrDigit() }

    private fun gez(n: AccessibilityNodeInfo?, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null) return
        f(n)
        for (i in 0 until n.childCount) gez(n.getChild(i), f)
    }

    private fun tikla(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        while (x != null) {
            if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            x = x.parent
        }
        return false
    }

    /** Şu an ekranda önde olan uygulamanın paket adı */
    fun ustPaket(): String? = rootInActiveWindow?.packageName?.toString()

    fun anaEkran(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun geri(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Ekranda yazısı (veya açıklaması) eşleşen öğeye dokunur */
    fun metneTikla(metin: String): Boolean {
        val hedef = norm(metin)
        if (hedef.length < 2) return false
        val root = rootInActiveWindow ?: return false
        var en: AccessibilityNodeInfo? = null
        var enSkor = Int.MAX_VALUE
        gez(root) { n ->
            for (a in listOf(norm(n.text?.toString()), norm(n.contentDescription?.toString()))) {
                if (a.isEmpty()) continue
                val skor = when {
                    a == hedef -> 0
                    a.contains(hedef) -> 1 + (a.length - hedef.length)
                    else -> -1
                }
                if (skor >= 0 && skor < enSkor) {
                    en = n
                    enSkor = skor
                }
            }
        }
        val bulunan = en ?: return false
        return tikla(bulunan)
    }

    fun kaydir(ileri: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        var hedef: AccessibilityNodeInfo? = null
        gez(root) { n -> if (hedef == null && n.isScrollable) hedef = n }
        val h = hedef ?: return false
        return h.performAction(
            if (ileri) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    /** Odaktaki (yoksa ilk) yazı alanındaki metni değiştirir */
    fun metinYaz(metin: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val odak = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var alan: AccessibilityNodeInfo? = if (odak != null && odak.isEditable) odak else null
        if (alan == null) {
            gez(root) { n -> if (alan == null && n.isEditable) alan = n }
        }
        val a = alan ?: return false
        val b = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, metin)
        }
        return a.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    /** WhatsApp'ta gönder düğmesine basar */
    fun gonderTikla(): Boolean {
        val root = rootInActiveWindow ?: return false
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            val l = root.findAccessibilityNodeInfosByViewId(id)
            if (!l.isNullOrEmpty()) return tikla(l[0])
        }
        var bulunan: AccessibilityNodeInfo? = null
        gez(root) { n ->
            if (bulunan == null) {
                val d = (n.contentDescription?.toString() ?: "").lowercase(tr)
                if (d == "gönder" || d == "send") bulunan = n
            }
        }
        val b = bulunan ?: return false
        return tikla(b)
    }
}
