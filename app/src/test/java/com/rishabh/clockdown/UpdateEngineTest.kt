package com.rishabh.clockdown

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** A stand-in for the app's stored update state. */
private class MemoryStore : UpdateStore {
    val map = mutableMapOf<String, Any?>()
    override fun getString(key: String) = map[key] as String?
    override fun getLong(key: String) = (map[key] as Long?) ?: 0L
    override fun edit(block: UpdateStore.Editor.() -> Unit) {
        object : UpdateStore.Editor {
            override fun putString(key: String, value: String?) { map[key] = value }
            override fun putLong(key: String, value: Long) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
        }.block()
    }
}

class UpdateEngineTest {
    private lateinit var server: MockWebServer
    private val store = MemoryStore()
    private var installed = 1
    private var clock = 1_800_000_000_000L // a realistic "now"; 0 would look like "checked a moment ago"

    private fun release(tag: String) =
        """{"tag_name":"$tag","name":"Clockdown $tag","body":"Notes","assets":[{"name":"clockdown.apk","size":5,"browser_download_url":"${server.url("/dl/clockdown.apk")}"}]}"""

    // The release web page. Unless a test queues an answer, it answers 500, so the engine falls back to the API.
    private lateinit var web: MockWebServer

    private fun engine() = UpdateEngine(
        OkHttpClient(), server.url("/").toString().trimEnd('/'), store, { installed }, { clock }, web.url("/").toString().trimEnd('/'),
    )

    private fun latestPageSays(tag: String) =
        web.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://github.com/x/y/releases/tag/$tag").build())

    @Before fun start() {
        server = MockWebServer().also { it.start() }
        web = MockWebServer().also { it.start(); (it.dispatcher as mockwebserver3.QueueDispatcher).setFailFast(MockResponse.Builder().code(500).build()) }
    }
    @After fun stop() { server.close(); web.close() }

    @Test
    fun whenTheLatestTagIsNotNewerTheApiIsNeverAsked() {
        installed = 9
        repeat(3) { latestPageSays("9"); assertEquals(CheckResult.CURRENT, engine().check(CheckMode.REVALIDATE)) }
        assertEquals(0, server.requestCount)
        assertEquals(3, web.requestCount)
    }

    @Test
    fun aNewTagCostsOneApiRequestAndRepeatChecksCostNone() {
        installed = 9
        latestPageSays("10")
        server.enqueue(MockResponse.Builder().code(200).body(release("10")).build())
        assertEquals(CheckResult.UPDATE, engine().check(CheckMode.REVALIDATE))
        repeat(3) { latestPageSays("10"); assertEquals(CheckResult.UPDATE, engine().check(CheckMode.REVALIDATE)) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aWithdrawnReleaseIsForgottenFromTheLatestTagAlone() {
        installed = 9
        latestPageSays("10")
        server.enqueue(MockResponse.Builder().code(200).body(release("10")).build())
        engine().check(CheckMode.MANUAL)
        latestPageSays("9") // release 10 was deleted; the newest is the one we have
        assertEquals(CheckResult.CURRENT, engine().check(CheckMode.REVALIDATE))
        assertNull(store.getString("updRelease"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anUnexpectedLatestPageFallsBackToTheApi() {
        installed = 9
        web.enqueue(MockResponse.Builder().code(200).body("<html>changed</html>").build())
        server.enqueue(MockResponse.Builder().code(200).body(release("10")).build())
        assertEquals(CheckResult.UPDATE, engine().check(CheckMode.REVALIDATE))
        web.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://github.com/login").build())
        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(CheckResult.UPDATE, engine().check(CheckMode.REVALIDATE))
        // (the page failing outright, the default answer here, is every other test in this file)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aNewerReleaseIsOfferedAndCached() {
        server.enqueue(MockResponse.Builder().code(200).body(release("2")).addHeader("ETag", "\"a\"").build())
        assertEquals(CheckResult.UPDATE, engine().check(CheckMode.MANUAL))
        assertEquals(2, engine().available()?.versionCode)
    }

    @Test
    fun a404ClearsAnyCachedUpdate() {
        server.enqueue(MockResponse.Builder().code(200).body(release("2")).build())
        engine().check(CheckMode.MANUAL)
        assertNotNull(engine().available())

        server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())
        assertEquals(CheckResult.CURRENT, engine().check(CheckMode.REVALIDATE))
        assertNull(engine().available())
        assertNull(store.getString("updRelease")) // really removed, not just hidden
    }

    @Test
    fun aReleaseDeletedAfterBeingCachedIsNotOfferedAfterRevalidating() {
        // The exact bug: the phone cached "version 2", then the release was deleted on GitHub.
        store.map["updRelease"] = release("2")
        store.map["updEtag"] = "\"a\""
        store.map["updCheckedAt"] = clock // recent, so a normal check would trust the cache and keep offering it
        assertNotNull(engine().available())

        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(CheckResult.CURRENT, engine().check(CheckMode.REVALIDATE))
        assertNull(engine().available())
        val req = server.takeRequest()
        assertEquals("\"a\"", req.headers["If-None-Match"]) // asked cheaply: doesn't count against GitHub's limit
    }

    @Test
    fun aReleaseThatIsNotNewerThanTheInstalledVersionIsForgotten() {
        installed = 2
        server.enqueue(MockResponse.Builder().code(200).body(release("2")).build())
        assertEquals(CheckResult.CURRENT, engine().check(CheckMode.MANUAL))
        assertNull(store.getString("updRelease"))

        // And a cached one becomes stale the moment the app is updated to it, without any network.
        installed = 1
        store.map["updRelease"] = release("2")
        installed = 2
        assertEquals(CheckResult.CURRENT, engine().check(CheckMode.MANUAL))
        assertNull(store.getString("updRelease"))
    }

    @Test
    fun anUnchangedAnswerKeepsTheCache() {
        server.enqueue(MockResponse.Builder().code(200).body(release("2")).addHeader("ETag", "\"a\"").build())
        engine().check(CheckMode.MANUAL)
        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(CheckResult.UPDATE, engine().check(CheckMode.REVALIDATE))
        server.takeRequest()
        assertEquals("\"a\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun checksWithinAMinuteReuseTheCacheButRevalidateAlwaysAsks() {
        server.enqueue(MockResponse.Builder().code(200).body(release("2")).build())
        engine().check(CheckMode.MANUAL)
        engine().check(CheckMode.MANUAL) // within a minute: no request
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse.Builder().code(304).build())
        engine().check(CheckMode.REVALIDATE)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimitingKeepsWhatWeKnowAndReportsIt() {
        server.enqueue(MockResponse.Builder().code(200).body(release("2")).build())
        engine().check(CheckMode.MANUAL)
        server.enqueue(MockResponse.Builder().code(403).build())
        assertEquals(CheckResult.LIMITED, engine().check(CheckMode.REVALIDATE))
        assertNotNull(engine().available())
    }

    // ---------------------------------------------------------------- downloading

    private fun tmp(): File = File.createTempFile("clockdown", ".apk").also { it.delete(); it.deleteOnExit() }

    @Test
    fun anAssetThatIsGoneMidDownloadIsReportedAsGoneNotAsAFailure() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        val dest = tmp()
        val r = fetchApk(OkHttpClient(), server.url("/dl/clockdown.apk").toString(), 5, null, dest)
        assertEquals(Download.Gone, r)
        assertFalse(dest.exists())
    }

    @Test
    fun aServerErrorIsAnOrdinaryInterruptedDownloadNotGone() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        assertEquals(Download.Interrupted, fetchApk(OkHttpClient(), server.url("/x").toString(), 5, null, tmp()))
    }

    @Test
    fun aGoodDownloadIsWrittenAndACorruptOneIsRejected() = runBlocking {
        val bytes = "hello".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        server.enqueue(MockResponse.Builder().code(200).body("hello").build())
        val ok = tmp()
        assertTrue(fetchApk(OkHttpClient(), server.url("/x").toString(), 5, sha, ok) is Download.Done)
        assertEquals("hello", ok.readText())

        server.enqueue(MockResponse.Builder().code(200).body("hello").build())
        val bad = tmp()
        assertEquals(Download.Corrupt, fetchApk(OkHttpClient(), server.url("/x").toString(), 5, "0".repeat(64), bad))
        assertFalse(bad.exists())
    }
}
