package com.metavirtuoso.conjunction.nearby

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import com.metavirtuoso.conjunction.core.p2p.P256Agreement
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One Nearby "node": the engine thread, the protocol [engine] and the Bluetooth [transport] on
 * it, over one [store] and one Keystore identity. Production has exactly one (owned by
 * [NearbyRepository], driven by [NearbyBluetoothService]); the debug test harness makes its own
 * with a separate store and key alias so it never touches the owner's data.
 */
@RequiresApi(Build.VERSION_CODES.S)
class P2pNode(
    context: Context,
    val store: P2pStore,
    private val identityAlias: String,
    host: NearbyHost,
    screener: ContentScreener,
) {
    private val thread = HandlerThread("conjunction-p2p").apply { start() }
    /** The engine thread. A failure in one callback is logged, never fatal to the app. */
    val handler: Handler = object : Handler(thread.looper) {
        override fun dispatchMessage(msg: android.os.Message) {
            try {
                super.dispatchMessage(msg)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Nearby engine callback failed", e)
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var identity: P256Agreement? = null

    val engine = NearbyEngine(
        store = store,
        identity = { identity ?: KeystoreIdentity.loadOrCreate(identityAlias).also { identity = it } },
        host = host,
        screener = screener,
        scope = scope,
    )
    val transport = BleTransport(context.applicationContext, engine, handler) { Log.i(TAG, it) }

    val engineStatus: StateFlow<EngineStatus> get() = engine.status
    val radioStatus: StateFlow<RadioStatus> get() = transport.status

    /** Runs [block] on the engine thread. */
    fun post(block: () -> Unit) {
        handler.post(block)
    }

    /** Loads the store, then starts advertising, the GATT server and scanning. */
    suspend fun startRadio(): RadioStartResult {
        store.load()
        return onEngine { transport.start() }
    }

    suspend fun stopRadio(graceful: Boolean) {
        onEngine { transport.stop(graceful) }
    }

    private suspend fun <T> onEngine(block: () -> T): T = suspendCancellableCoroutine { cont ->
        handler.post { runCatching(block).fold({ cont.resume(it) }, { cont.resumeWithException(it) }) }
    }

    companion object {
        const val TAG = "ConjunctionP2P"
    }
}
