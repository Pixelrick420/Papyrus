package io.github.pixelrick420.papyrus.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.pixelrick420.papyrus.MainActivity
import io.github.pixelrick420.papyrus.MainApplication
import io.github.pixelrick420.papyrus.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

// The property setter discards the platform's support code; the method returns it.
internal fun resolveTtsLanguage(locale: Locale, setLanguage: (Locale) -> Int): Int {
    val direct = runCatching { setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
    if (direct != TextToSpeech.LANG_MISSING_DATA && direct != TextToSpeech.LANG_NOT_SUPPORTED) return direct
    return runCatching { setLanguage(Locale.ENGLISH) }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
}

// Foreground service owning the TextToSpeech engine, so reading aloud outlives the viewer and the screen going off.
class TtsPlaybackService : Service(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var mediaSession: MediaSession
    private lateinit var session: TtsSession
    private lateinit var audio: ReadAloudAudio
    private var controller: TtsController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var engineReady = false

    /** A failed [onInit] is permanent; only then does the controller report an error instead of deferring. */
    private var engineFailed = false
    private var started = false

    // The engine binds asynchronously, so the first play arrives before it is listening; the rate is held for the same reason.
    private var pendingSpeak: Pair<String, String>? = null
    private var pendingRate = 1.0f

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Headphones unplugged or a Bluetooth link dropped: the speech would move to the speaker, so it pauses. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            audio.onBecomingNoisy()
        }
    }

    private val engine = object : SpeechEngine {
        override fun speak(text: String, utteranceId: String): Boolean {
            if (engineFailed) return false
            if (!engineReady) {
                pendingSpeak = text to utteranceId
                return true
            }
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            if (result == TextToSpeech.ERROR) {
                scope.launch { controller?.onUtteranceError(utteranceId, getString(R.string.tts_error_speech)) }
            }
            return result != TextToSpeech.ERROR
        }

        override fun stop() {
            pendingSpeak = null
            if (engineReady) tts.stop()
        }

        override fun setSpeechRate(rate: Float) {
            pendingRate = rate
            if (engineReady) tts.setSpeechRate(rate)
        }

        override fun isAvailable(): Boolean = !engineFailed
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        session = (application as MainApplication).ttsSession
        audio = ReadAloudAudio(
            focus = PlatformAudioFocus(this, speechAttributes) { change -> audio.onFocusChange(change) },
            pause = { session.pause() },
            resume = { session.play() },
        )
        // System broadcast only, so no export flag is asked for.
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        val control = TtsController(engine, getString(R.string.tts_error_engine))
        controller = control
        session.attachController(control)
        mediaSession = MediaSession(this, "PapyrusReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = session.play()
                override fun onPause() = session.pause()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = session.skipForward()
                override fun onSkipToPrevious() = session.skipBack()
            })
            isActive = true
        }
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                scope.launch { controller?.onUtteranceComplete(utteranceId) }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                scope.launch { controller?.onRangeStart(utteranceId, start, end) }
            }

            @Deprecated("Kept for engines that report the legacy callback.")
            override fun onError(utteranceId: String) {
                scope.launch { controller?.onUtteranceError(utteranceId, getString(R.string.tts_error_speech)) }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                scope.launch { controller?.onUtteranceError(utteranceId, getString(R.string.tts_error_speech)) }
            }
        })
        scope.launch {
            var published: TtsPlayback? = null
            control.playback.collectLatest { playback ->
                session.syncPlayback(playback)
                audio.onPlaybackChanged(playback.isPlaying)
                // The word moves several times a second; the notification and the media session only
                // change with the transport, and the system throttles an app that re-posts faster.
                if (published?.sameTransportAs(playback) != true) publish(playback)
                published = playback
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // begin() starts playback with the new request, so a preceding play() would speak from the old position.
        val pending = if (action == ACTION_PAUSE || action == ACTION_STOP) null else session.consumePending()
        when (action) {
            ACTION_PLAY -> if (pending == null) session.play()
            ACTION_PAUSE -> session.pause()
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
        }
        if (!started) {
            started = true
            startInForeground()
        }
        pending?.let(::begin)
        publish(controller?.playback?.value ?: TtsPlayback())
        return START_NOT_STICKY
    }

    private fun begin(request: TtsSession.PendingRequest) {
        val control = controller ?: return
        control.setUnits(request.units)
        control.setSpeed(request.speed)
        control.playFrom(request.startIndex)
    }

    override fun onInit(status: Int) {
        engineReady = status == TextToSpeech.SUCCESS
        engineFailed = !engineReady
        if (!engineReady) {
            controller?.onError(getString(R.string.tts_error_engine))
            return
        }
        tts.setAudioAttributes(speechAttributes)
        val result = resolveTtsLanguage(Locale.getDefault(), tts::setLanguage)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            controller?.onError(getString(R.string.tts_error_locale))
        }
        tts.setSpeechRate(pendingRate)
        pendingSpeak?.let { (text, id) ->
            pendingSpeak = null
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result == TextToSpeech.ERROR) {
                scope.launch { controller?.onUtteranceError(id, getString(R.string.tts_error_speech)) }
            }
        }
    }

    private fun startInForeground() {
        val notification = buildNotification(session.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun publish(playback: TtsPlayback) {
        if (started) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(session.state.value))
        }
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, session.state.value.documentTitle)
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (playback.isPlaying) PlaybackState.STATE_PLAYING
                    else if (playback.isPaused) PlaybackState.STATE_PAUSED
                    else PlaybackState.STATE_STOPPED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    playback.speed,
                )
                .build(),
        )
        // A failure keeps the session so the in-flow bar can still say why it stopped.
        if (!playback.isPlaying && !playback.isPaused && started) {
            stopPlayback(clearSession = !playback.hasError)
        }
    }

    // [clearSession] false is the failure path: the error and document stay in the session so the bar reports it and keeps the reading position.
    private fun stopPlayback(clearSession: Boolean = true) {
        if (started) {
            started = false
            if (clearSession) {
                controller?.stop()
                session.stop()
            }
            audio.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        // Also when nothing had started: a second tap on Stop, or one that lands after the first has already
        // stopped playback, is a start command like any other and would keep an idle service alive.
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tts_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.viewer_read_aloud) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: TtsState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = if (state.isPlaying) ACTION_PAUSE else ACTION_PLAY
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TtsPlaybackService::class.java).setAction(toggle),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleTitle = getString(if (state.isPlaying) R.string.tts_pause else R.string.tts_play)
        val toggleIcon = if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            state.hasError -> state.errorMessage
            state.documentTitle.isNotEmpty() -> state.documentTitle
            else -> getString(R.string.viewer_read_aloud)
        }
        val toggleAction = Notification.Action.Builder(Icon.createWithResource(this, toggleIcon), toggleTitle, toggleIntent).build()
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.viewer_read_aloud_stop),
            stopIntent,
        ).build()
        // A locked screen shows this instead, so the document's title never appears there; an error text is generic.
        val lockScreen = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.viewer_read_aloud))
            .setContentText(if (state.hasError) state.errorMessage else getString(R.string.tts_notification_public))
            .setSmallIcon(R.drawable.ic_tts)
            .setContentIntent(open)
            .addAction(toggleAction)
            .addAction(stopAction)
            .build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.viewer_read_aloud))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tts)
            .setContentIntent(open)
            .setOngoing(state.isPlaying || state.isPaused)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreen)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken))
            .addAction(toggleAction)
            .addAction(stopAction)
            .build()
    }

    override fun onDestroy() {
        if (started) stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        unregisterReceiver(noisyReceiver)
        audio.release()
        controller?.let { session.detachController(it) }
        mediaSession.release()
        // Shut down even if onInit never arrived: the bind is live either way, and an engine left
        // warming past the service would leak the connection.
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 0x7A57
        const val ACTION_PLAY = "io.github.pixelrick420.papyrus.tts.PLAY"
        /** Notification-only: starting the service with this action would skip startForeground. */
        const val ACTION_PAUSE = "io.github.pixelrick420.papyrus.tts.PAUSE"
        /** Notification-only: starting the service with this action would skip startForeground. */
        const val ACTION_STOP = "io.github.pixelrick420.papyrus.tts.STOP"

        fun start(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java).setAction(ACTION_PLAY)
            context.startForegroundService(intent)
        }
    }
}
