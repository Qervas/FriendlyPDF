package tech.ohao.friendlypdf

import android.app.Notification
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
import android.speech.tts.Voice
import android.util.Log
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat

class TTSService : Service(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private val binder = TTSBinder()
    private var currentSentence = ""
    private var isSpeaking = false
    private var currentLanguage: Locale = Locale.getDefault()
    private var utteranceProgressListener: UtteranceProgressListener? = null
    private var isInitialized = false
    private var currentVoice: Voice? = null
    private var remainingTimeInMinutes: Int = 0
    private var countdownTimer: Timer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var sentences = listOf<String>()
    private var currentSentenceIndex = 0
    private var onSentenceChangeListener: ((Int) -> Unit)? = null
    private var pausedSentence: String? = null  

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

    // Add getter/setter for voices
    val voices: Set<Voice>?
        get() = tts.voices

    var voice: Voice?
        get() = currentVoice
        set(value) {
            currentVoice = value
            value?.let { tts.voice = it }
        }

    inner class TTSBinder : Binder() {
        fun getService(): TTSService = this@TTSService
    }

    companion object {
        private const val CHANNEL_ID = "pdf_reader_service_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("TTS_SERVICE", "onCreate called")
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        setupMediaSession()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "TTSService").apply {
            // Set callback to handle media button events
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d("TTS_SERVICE", "MediaSession onPlay")
                    resumeReading()
                    updatePlaybackState()
                    updateNotification()
                }

                override fun onPause() {
                    Log.d("TTS_SERVICE", "MediaSession onPause")
                    stop()
                    updatePlaybackState()
                    updateNotification()
                }

                override fun onSkipToNext() {
                    Log.d("TTS_SERVICE", "MediaSession onSkipToNext")
                    nextSentence()
                    updatePlaybackState()
                    updateNotification()
                }

                override fun onSkipToPrevious() {
                    Log.d("TTS_SERVICE", "MediaSession onSkipToPrevious")
                    previousSentence()
                    updatePlaybackState()
                    updateNotification()
                }
            })

            // Update initial playback state
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(
                        if (isSpeaking) PlaybackStateCompat.STATE_PLAYING
                        else PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        1f
                    )
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .build()
            )
            isActive = true
        }
    }

    private fun updatePlaybackState() {
        val state = if (isSpeaking) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .build()
        )
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // After language initialization, try to set the best available voice
            initializeBestVoice()
            isInitialized = true
            startForegroundService()

            // Set UtteranceProgressListener
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d("TTS_SERVICE", "Utterance started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d("TTS_SERVICE", "Utterance done: $utteranceId")
                    isSpeaking = false
                    updatePlaybackState()
                    updateNotification()
                    nextSentence()
                }

                override fun onError(utteranceId: String?) {
                    Log.e("TTS_SERVICE", "Utterance error: $utteranceId")
                    isSpeaking = false
                    updatePlaybackState()
                    updateNotification()
                }
            })
        } else {
            Log.e("TTS_SERVICE", "TTS initialization failed with status: $status")
            stopSelf()
        }
    }

    private fun initializeBestVoice() {
        // Try to find and set the best available voice
        voices?.firstOrNull { voice ->
            voice.name.contains("neural", ignoreCase = true) ||
            voice.name.contains("premium", ignoreCase = true) ||
            voice.name.contains("enhanced", ignoreCase = true)
        }?.let { bestVoice ->
            voice = bestVoice
            Log.d("TTS_SERVICE", "Set best voice: ${bestVoice.name}")
        } ?: run {
            // If no high-quality voice found, set any available voice
            voices?.firstOrNull()?.let { fallbackVoice ->
                voice = fallbackVoice
                Log.d("TTS_SERVICE", "Set fallback voice: ${fallbackVoice.name}")
            }
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDF Reader Service")
            .setContentText("Ready to read PDF")
            .setSmallIcon(R.drawable.baseline_play_arrow_24)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        params: Bundle? = null,
        utteranceId: String? = null
    ) {
        Log.d("TTS_SERVICE", "Speaking: $text")
        currentSentence = text
        tts.speak(text, queueMode, params, utteranceId)
        isSpeaking = true
        updatePlaybackState()
        updateNotification()
    }

    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        utteranceProgressListener = listener
        tts.setOnUtteranceProgressListener(listener)
    }

    fun stop() {
        Log.d("TTS_SERVICE", "Stopping TTS")
        if (isSpeaking) {
            pausedSentence = currentSentence  // Store the current sentence
        }
        tts.stop()
        isSpeaking = false
        updatePlaybackState()
        updateNotification()
    }

    fun shutdown() {
        tts.shutdown()
    }

    private fun updateNotification() {
        // Always show timer if it's greater than 0
        val timerText = if (remainingTimeInMinutes > 0) {
            "⏰ ${remainingTimeInMinutes} min remaining"
        } else {
            ""
        }

        // Update media metadata with timer
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Reading PDF")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Reading PDF")
            .putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                timerText.ifEmpty { currentSentence }
            )
            .build()
        mediaSession.setMetadata(metadata)

        // Create pending intents for media controls
        val playPauseIntent = createActionIntent("play_pause")
        val prevIntent = createActionIntent("previous")
        val nextIntent = createActionIntent("next")

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reading PDF")
            .setContentText(currentSentence)
            .setSubText(timerText)  // Show timer as subtext
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setSmallIcon(if (isSpeaking) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.baseline_skip_previous_24, "Previous", prevIntent)
            .addAction(
                if (isSpeaking) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24,
                if (isSpeaking) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(R.drawable.baseline_skip_next_24, "Next", nextIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        // Update the notification without calling startForeground again
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(this, TTSService::class.java).apply {
            this.action = action
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(
            this,
            action.hashCode(), // Unique request code based on action
            intent,
            flags
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PDF Reader Service",
                NotificationManager.IMPORTANCE_LOW  // Change to LOW to prevent sound/vibration
            ).apply {
                setShowBadge(true)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TTS_SERVICE", "onStartCommand action: ${intent?.action}")
        when (intent?.action) {
            "play_pause" -> {
                Log.d("TTS_SERVICE", "Play/Pause - Speaking: $isSpeaking")
                if (isSpeaking) {
                    stop()
                } else {
                    resumeReading()
                }
            }
            "next" -> {
                Log.d("TTS_SERVICE", "Skipping to next sentence")
                nextSentence()
            }
            "previous" -> {
                Log.d("TTS_SERVICE", "Skipping to previous sentence")
                previousSentence()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("TTS_SERVICE", "onDestroy called")
        mediaSession.release()
        countdownTimer?.cancel()
        tts.shutdown()
    }

    fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    fun setPitch(pitch: Float) {
        tts.setPitch(pitch)
    }

    fun updateCountdown(minutes: Int) {
        remainingTimeInMinutes = minutes
        countdownTimer?.cancel()

        if (minutes > 0) {
            countdownTimer = Timer("CountdownTimer").apply {
                scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        remainingTimeInMinutes = (remainingTimeInMinutes - 1).coerceAtLeast(0)
                        if (remainingTimeInMinutes > 0) {
                            updateNotification()  // Only update if timer is still running
                        } else {
                            // Timer finished, update one last time to clear the timer display
                            updateNotification()
                            countdownTimer?.cancel()
                        }
                    }
                }, 0, TimeUnit.MINUTES.toMillis(1))
            }
        }
        updateNotification()
    }

    fun setContent(newSentences: List<String>, startIndex: Int = 0) {
        sentences = newSentences
        currentSentenceIndex = startIndex
    }

    fun setOnSentenceChangeListener(listener: (Int) -> Unit) {
        onSentenceChangeListener = listener
    }

    fun resumeReading() {
        if (!isSpeaking) {
            if (pausedSentence != null) {
                // Resume from paused sentence
                speak(pausedSentence!!, TextToSpeech.QUEUE_FLUSH)
                pausedSentence = null  // Clear the stored sentence
            } else if (currentSentenceIndex < sentences.size) {
                // Start new sentence if no paused sentence
                speak(sentences[currentSentenceIndex], TextToSpeech.QUEUE_FLUSH)
            }
            isSpeaking = true
            onSentenceChangeListener?.invoke(currentSentenceIndex)
            updatePlaybackState()
            updateNotification()
        }
    }

    private fun nextSentence() {
        if (currentSentenceIndex < sentences.size - 1) {
            currentSentenceIndex++
            resumeReading()
        }
    }

    private fun previousSentence() {
        if (currentSentenceIndex > 0) {
            currentSentenceIndex--
            resumeReading()
        }
    }
}
