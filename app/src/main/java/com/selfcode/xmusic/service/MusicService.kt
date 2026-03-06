package com.selfcode.xmusic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.selfcode.xmusic.R
import com.selfcode.xmusic.ui.MainActivity

class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "xmusic_playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.selfcode.xmusic.service.PLAY_PAUSE"
        const val ACTION_PREV = "com.selfcode.xmusic.service.PREV"
        const val ACTION_NEXT = "com.selfcode.xmusic.service.NEXT"
        const val ACTION_STOP = "com.selfcode.xmusic.service.STOP"
    }

    private var mediaSession: MediaSessionCompat? = null
    private val binder = MusicBinder()
    var callback: ServiceCallback? = null

    interface ServiceCallback {
        fun onServicePlayPause()
        fun onServicePrev()
        fun onServiceNext()
        fun onServiceStop()
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "XMusicSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { callback?.onServicePlayPause() }
                override fun onPause() { callback?.onServicePlayPause() }
                override fun onSkipToNext() { callback?.onServiceNext() }
                override fun onSkipToPrevious() { callback?.onServicePrev() }
                override fun onStop() { callback?.onServiceStop() }
            })
            isActive = true
        }
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val initNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Self Music")
            .setContentText("by SelfCode")
            .setSmallIcon(R.drawable.ic_music_placeholder)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
        startForeground(NOTIFICATION_ID, initNotification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> callback?.onServicePlayPause()
            ACTION_PREV -> callback?.onServicePrev()
            ACTION_NEXT -> callback?.onServiceNext()
            ACTION_STOP -> {
                callback?.onServiceStop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun updateNotification(title: String, artist: String, isPlaying: Boolean, cover: Bitmap?) {
        val session = mediaSession ?: return

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .apply { if (cover != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover) }
                .build()
        )

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1f
            )
        session.setPlaybackState(stateBuilder.build())

        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_music_placeholder)
            .setLargeIcon(cover)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setStyle(MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(R.drawable.ic_prev, "Prev", getActionIntent(ACTION_PREV))
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play",
                getActionIntent(ACTION_PLAY_PAUSE)
            )
            .addAction(R.drawable.ic_next, "Next", getActionIntent(ACTION_NEXT))
            .addAction(R.drawable.ic_delete, "Stop", getActionIntent(ACTION_STOP))
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun getActionIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Воспроизведение музыки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление плеером"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun getMediaSession(): MediaSessionCompat? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
