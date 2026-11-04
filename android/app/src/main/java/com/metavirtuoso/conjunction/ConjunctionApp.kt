package com.metavirtuoso.conjunction

import android.app.Application
import android.content.Context
import com.metavirtuoso.conjunction.ai.GeminiNanoModel
import com.metavirtuoso.conjunction.ai.ReadingService
import com.metavirtuoso.conjunction.ai.TextModel
import com.metavirtuoso.conjunction.data.ProfileStore
import com.metavirtuoso.conjunction.data.ReadingCache
import com.metavirtuoso.conjunction.legal.ConsentLedger
import com.metavirtuoso.conjunction.nearby.NearbyApi
import com.metavirtuoso.conjunction.nearby.NearbyRepository
import com.metavirtuoso.conjunction.nearby.Notifications
import com.metavirtuoso.conjunction.palm.PalmScanner
import com.metavirtuoso.conjunction.tz.TimeZoneGrid

/** Hand-wired dependencies (small app; no DI framework). */
class AppContainer(context: Context, model: TextModel = GeminiNanoModel()) {
    private val app = context.applicationContext
    /** Application context (for strings and system services in non-UI code). */
    val context: Context get() = app
    val profileStore = ProfileStore(app)
    val consentLedger = ConsentLedger(app)
    val readingCache = ReadingCache(app)
    val textModel: TextModel = model
    val readings = ReadingService(model, readingCache)
    val nearbyApi = NearbyApi(app)
    val nearby = NearbyRepository(app, nearbyApi, profileStore, consentLedger)
    val palmScanner by lazy { PalmScanner(app) }

    /** Offline lat/lon -> IANA zone grid (about 1 MB; loaded on first use, off the main thread). */
    val timeZones: TimeZoneGrid by lazy { app.assets.open(TimeZoneGrid.ASSET_PATH).use(TimeZoneGrid::read) }
}

class ConjunctionApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }
}
