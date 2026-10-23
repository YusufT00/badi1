package com.example.asistan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

// Telefonun "varsayılan asistan" olarak bizi tanıması için gereken sistem servisleri.

class AsistanInteractionService : VoiceInteractionService()

class AsistanSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AsistanSession(this)
}

class AsistanSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    // Kullanıcı sistemin asistan tetikleyicisini kullanınca (güç tuşu, köşeden kaydırma vb.) burası çalışır
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        ctx.startForegroundService(Intent(ctx, AsistanService::class.java).putExtra("cagir", true))
        hide()
    }
}

// Asistan olarak seçilebilmek için sistemin istediği boş bir ses tanıma servisi
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
