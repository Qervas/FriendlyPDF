package tech.ohao.friendlypdf

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale
import android.os.Bundle
import android.util.Log

class TTSService : Service(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private val binder = TTSBinder()
    private var currentSentence = ""
    private var isSpeaking = false
    private var currentLanguage: Locale = Locale.getDefault()
    private var utteranceProgressListener: UtteranceProgressListener? = null
    private var isInitialized = false
    
    // Language-related function
    fun setLanguage(locale: Locale): Int {
        currentLanguage = locale
        return tts.setLanguage(locale)
    }

    // Language property with getter/setter
    var language: Locale
        get() = currentLanguage
        set(value) {
            currentLanguage = value
            tts.language = value
        }

    inner class TTSBinder : Binder() {
        fun getService(): TTSService = this@TTSService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("TTS_SERVICE", "onCreate called")
        tts = TextToSpeech(this, this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isInitialized) {
            // Wait for TTS to initialize before starting foreground
            return START_STICKY
        }
        startForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d("TTS_SERVICE", "TTS initialized successfully")
            val result = tts.setLanguage(currentLanguage)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TTS_SERVICE", "Language not supported, falling back to US English")
                tts.language = Locale.US
            }
            isInitialized = true
            startForeground()
        } else {
            Log.e("TTS_SERVICE", "TTS initialization failed with status: $status")
            stopSelf()
        }
    }

    private fun startForeground() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDF Reader Service")
            .setContentText("Ready to read PDF")
            .setSmallIcon(R.drawable.baseline_play_arrow_24)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH, params: Bundle? = null, utteranceId: String? = null) {
        Log.d("TTS_SERVICE", "Speaking: $text")
        currentSentence = text
        tts.speak(text, queueMode, params, utteranceId)
        isSpeaking = true
        startForeground()
        updateNotification()
    }

    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        utteranceProgressListener = listener
        tts.setOnUtteranceProgressListener(listener)
    }

    fun stop() {
        tts.stop()
        isSpeaking = false
        stopForeground(true)
    }

    fun shutdown() {
        tts.shutdown()
    }

    private fun updateNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reading PDF")
            .setContentText(currentSentence)
            .setSmallIcon(R.drawable.baseline_play_arrow_24)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PDF Reader Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "PDF_Reader_Service"
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }
}
