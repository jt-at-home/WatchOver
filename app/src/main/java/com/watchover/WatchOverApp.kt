package com.watchover

import android.app.Application
import android.content.Context
import com.watchover.alert.NotificationChannels
import com.watchover.di.AppGraph

class WatchOverApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
        // Channels must exist before the first notification, otherwise the owner
        // cannot see, silence, or hide them in system settings.
        NotificationChannels.ensureCreated(this, bypassDnd = false)
    }

    companion object {
        fun from(context: Context): AppGraph =
            (context.applicationContext as WatchOverApp).graph
    }
}
