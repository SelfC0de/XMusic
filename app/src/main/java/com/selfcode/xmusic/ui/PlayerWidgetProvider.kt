package com.selfcode.xmusic.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.selfcode.xmusic.R
import com.selfcode.xmusic.service.MusicService

class PlayerWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateWidget(context: Context, title: String, artist: String, isPlaying: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(R.id.widgetTitle, title)
            views.setTextViewText(R.id.widgetArtist, artist)
            views.setImageViewResource(R.id.widgetPlayPause,
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

            views.setOnClickPendingIntent(R.id.widgetPlayPause, getServiceIntent(context, MusicService.ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widgetPrev, getServiceIntent(context, MusicService.ACTION_PREV))
            views.setOnClickPendingIntent(R.id.widgetNext, getServiceIntent(context, MusicService.ACTION_NEXT))

            val openApp = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(R.id.widgetCover,
                PendingIntent.getActivity(context, 0, openApp, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }

        private fun getServiceIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, MusicService::class.java).apply { this.action = action }
            return PendingIntent.getService(context, action.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setOnClickPendingIntent(R.id.widgetPlayPause, getServiceIntent(context, MusicService.ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widgetPrev, getServiceIntent(context, MusicService.ACTION_PREV))
            views.setOnClickPendingIntent(R.id.widgetNext, getServiceIntent(context, MusicService.ACTION_NEXT))

            val openApp = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(R.id.widgetCover,
                PendingIntent.getActivity(context, 0, openApp, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

            manager.updateAppWidget(id, views)
        }
    }
}
