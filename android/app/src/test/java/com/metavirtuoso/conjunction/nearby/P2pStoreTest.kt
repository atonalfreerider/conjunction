package com.metavirtuoso.conjunction.nearby

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pStoreTest {
    /**
     * Regression (OTA round 2): the debug service's `-a reset` in a fresh process called clear()
     * on a store nobody had loaded yet, update() threw "before load()" on a Dispatchers.Default
     * worker, and that uncaught exception killed the app process. clear() must load first.
     */
    @Test
    fun clearBeforeLoadDoesNotThrowAndErasesTheFile() = runBlocking {
        val dir = Files.createTempDirectory("p2pstore").toFile()
        val file = File(dir, "datastore/p2p_debug.json")
        file.parentFile!!.mkdirs()
        val old = P2pState(messages = listOf(ChatLine("m1", "f1", true, "hi", 1L, ChatStatus.QUEUED)))
        file.writeText(P2pStore.StateSerializer.encode(old))
        val photos = File(dir, "p2p_debug/photos").apply { mkdirs() }
        File(photos, "x.jpg").writeBytes(byteArrayOf(1, 2, 3))

        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = DataStoreFactory.create(
            serializer = P2pStore.StateSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { P2pState() },
            scope = io,
            produceFile = { file },
        )
        // A cancelled scope: the store's own background load() never runs, so clear() is
        // guaranteed to be the first thing that touches it (deterministic, unlike a race).
        val store = P2pStore(ds, photos, CoroutineScope(Job().apply { cancel() }))
        assertFalse(store.isLoaded)

        store.clear()

        assertTrue(store.isLoaded)
        assertEquals(P2pState(), store.state.value)
        assertEquals(P2pState(), P2pStore.StateSerializer.decode(file.readText()))
        assertFalse(photos.exists())
        dir.deleteRecursively()
        Unit
    }
}
