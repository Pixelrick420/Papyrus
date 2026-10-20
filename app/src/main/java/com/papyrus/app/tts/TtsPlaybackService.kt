package com.papyrus.app.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.papyrus.app.MainActivity
import com.papyrus.app.MainApplication
import com.papyrus.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground playback service that owns the [TextToSpeech] engine, so reading aloud outlives the
 * viewer and survives the screen going off. It consumes the queue the viewer prepared and drives a
 * [TtsController] through a small platform adapter, publishing playback back into the app-scoped
 * [TtsSession].
 *
 * A media-style notification offers play/pause, and a platform [MediaSession] forwards headset and
 * Bluetooth transport keys and the lock-screen surface. Audio is attributed to media use so it ducks
 * with music.
 */
class TtsPlaybackService : Service(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var mediaSession: MediaSession
    private lateinit var session: TtsSession
    private var controller: TtsController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var engineReady = false

    /** A failed [onInit] is permanent; only then does the controller report an error instead of deferring. */
    private var engineFailed = false
    private var started = false

    /**
     * A speak that beats [onInit] is held here and spoken once the engine is ready. The engine binds
     * asynchronously, so the very first play always arrives before it is listening. The rate is held
     * for the same reason: it has to be applied before that first utterance, not while the engine is
     * still deaf to it.
     */
    private var pendingSpeak: Pair<String, String>? = null
    private var pendingRate = 1.0f

    private val engine = object : SpeechEngine {
        override fun speak(text: String, utteranceId: String) {
            if (engineFailed) return
            if (!engineReady) {
                pendingSpeak = text to utteranceId
                return
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
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

            /**
             * The word the engine is about to speak. The viewer marks it, and a speed change uses it
             * to re-queue only the rest of the unit instead of waiting for the whole paragraph.
             */
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
                // The word moves several times a second; the notification and the media session only
                // change with the transport, and the system throttles an app that re-posts faster.
                if (published?.sameTransportAs(playback) != true) publish(playback)
                published = playback
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
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
        // A fresh document (first play, or a new one) is loaded and started here.
        if (intent?.action != ACTION_PAUSE) {
            session.consumePending()?.let(::begin)
        }
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
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        runCatching { tts.language = Locale.getDefault() }
        tts.setSpeechRate(pendingRate)
        // The first play was queued before the engine was listening; speak it now.
        pendingSpeak?.let { (text, id) ->
            pendingSpeak = null
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
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

    /** Push the notification and the media session's transport state. */
    private fun publish(playback: TtsPlayback) {
        if (started) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(session.state.value))
        }
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
                    0L,
                    playback.speed,
                )
                .build(),
        )
        // Nothing left to play, or an error: drop the foreground job and the notification. A failure
        // keeps the session so the in-flow bar can still say why it stopped.
        if (!playback.isPlaying && !playback.isPaused && started) {
            stopPlayback(clearSession = !playback.hasError)
        }
    }

    /**
     * Drop the foreground job, the notification and the service. [clearSession] false is the failure
     * path: it leaves the error — and the document with it — in the session, so the bar reports it and
     * keeps the reading position. Everything that reaches an error has already stopped the engine, so
     * there is nothing left to silence.
     */
    private fun stopPlayback(clearSession: Boolean = true) {
        if (!started) return
        started = false
        if (clearSession) {
            controller?.stop()
            session.stop()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.viewer_read_aloud))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tts)
            .setContentIntent(open)
            .setOngoing(state.isPlaying || state.isPaused)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken))
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, toggleIcon), toggleTitle, toggleIntent).build())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.viewer_read_aloud_stop),
                    stopIntent,
                ).build(),
            )
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
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
        const val ACTION_PLAY = "com.papyrus.app.tts.PLAY"
        const val ACTION_PAUSE = "com.papyrus.app.tts.PAUSE"
        const val ACTION_STOP = "com.papyrus.app.tts.STOP"

        /** Bring the service up and let it consume whatever the viewer prepared. */
        fun start(context: Context, action: String = ACTION_PLAY) {
            val intent = Intent(context, TtsPlaybackService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
