package pro.sketchware.ai.agent

import com.google.gson.GsonBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionStoreTest {

    private lateinit var tempDir: File
    private val scId = "609"

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("sw_agent_session_test").toFile()
        AgentMemoryStore.testRootDir = tempDir
    }

    @After
    fun tearDown() {
        AgentMemoryStore.testRootDir = null
        tempDir.deleteRecursively()
    }

    @Test
    fun legacySingleSessionMigrationMovesChatPlanAndMemoryToFirstSession() {
        val legacyDir = File(tempDir, "agent/$scId").apply { mkdirs() }
        val gson = GsonBuilder().setPrettyPrinting().create()
        val legacyEntries = listOf(
            AgentChatEntry("e1", ChatEntryType.USER, "Anda", "Buat aplikasi kamera TikTok", 1000L),
            AgentChatEntry("e2", ChatEntryType.ASSISTANT, "Agent", "Siap, saya buatkan kamera TikTok.", 2000L)
        )
        File(legacyDir, "chat.json").writeText(gson.toJson(legacyEntries))
        File(legacyDir, "plan.json").writeText(gson.toJson(AgentPlan(goal = "TikTok Camera")))
        File(legacyDir, "memory.md").writeText("ID kamera: preview_view")

        val sessions = AgentMemoryStore.listSessions(scId)
        assertEquals(1, sessions.size)
        val first = sessions.first()
        assertEquals("Buat aplikasi kamera TikTok", first.title)
        assertEquals(2, first.messageCount)
        assertEquals(first.id, AgentMemoryStore.getActiveSessionId(scId))

        // Legacy flat files should have been moved into the session folder
        assertFalse(File(legacyDir, "chat.json").exists())
        assertFalse(File(legacyDir, "plan.json").exists())
        assertEquals(2, AgentMemoryStore.loadChatEntries(scId).size)
        assertEquals("TikTok Camera", AgentMemoryStore.loadPlan(scId).goal)
        assertEquals("ID kamera: preview_view", AgentMemoryStore.loadMemoryNotes(scId))
    }

    @Test
    fun sessionCrudPinAndAutoTitleWorks() {
        val s1 = AgentMemoryStore.listSessions(scId).first()
        assertTrue(AgentMemoryStore.isDefaultSessionTitle(s1.title))

        // First USER prompt should auto-title the session
        AgentMemoryStore.appendChatEntry(
            scId,
            AgentChatEntry("u1", ChatEntryType.USER, "Anda", "Desain layar profil modern dengan tab video"),
            sessionId = s1.id
        )
        val updatedS1 = AgentMemoryStore.getSessionMeta(scId, s1.id)!!
        assertEquals("Desain layar profil modern dengan tab video", updatedS1.title)

        // Create second session
        val s2 = AgentMemoryStore.createSession(scId, title = "Percakapan Baru", setActive = true)
        assertEquals(s2.id, AgentMemoryStore.getActiveSessionId(scId))

        // Rename s2
        AgentMemoryStore.renameSession(scId, s2.id, "Debugging Kamera")
        assertEquals("Debugging Kamera", AgentMemoryStore.getSessionMeta(scId, s2.id)?.title)

        // Pin s1 so it sorts before s2 even if s2 was updated more recently
        AgentMemoryStore.setSessionPinned(scId, s1.id, true)
        val sorted = AgentMemoryStore.listSessions(scId)
        assertEquals(s1.id, sorted.first().id)

        // Delete s2 -> active session falls back to s1
        val nextActive = AgentMemoryStore.deleteSession(scId, s2.id)
        assertEquals(s1.id, nextActive)
        assertEquals(1, AgentMemoryStore.listSessions(scId).size)
    }

    @Test
    fun unlimitedHistoryAndPaginationRemoves150TruncationLimit() {
        val sessionId = AgentMemoryStore.getActiveSessionId(scId)
        for (i in 1..210) {
            AgentMemoryStore.appendChatEntry(
                scId,
                AgentChatEntry("id_$i", ChatEntryType.USER, "Anda", "Pesan ke-$i", timestamp = i * 100L),
                sessionId = sessionId
            )
        }
        val all = AgentMemoryStore.loadChatEntries(scId, sessionId)
        assertEquals(210, all.size)

        val page1 = AgentMemoryStore.loadChatEntriesPage(scId, sessionId, loadedCount = 0, pageSize = 50)
        assertEquals(50, page1.entries.size)
        assertEquals("id_161", page1.entries.first().id)
        assertEquals("id_210", page1.entries.last().id)
        assertTrue(page1.hasOlder)

        val page2 = AgentMemoryStore.loadChatEntriesPage(scId, sessionId, loadedCount = 50, pageSize = 50)
        assertEquals(50, page2.entries.size)
        assertEquals("id_111", page2.entries.first().id)
        assertEquals("id_160", page2.entries.last().id)
        assertTrue(page2.hasOlder)
    }

    @Test
    fun forkTruncateSearchAndExportMarkdownWorkCorrectly() {
        val s1 = AgentMemoryStore.getActiveSessionId(scId)
        AgentMemoryStore.appendChatEntry(
            scId,
            AgentChatEntry("m1", ChatEntryType.USER, "Anda", "Tambahkan fitur ExoPlayer untuk video feed", checkpointPath = "/tmp/cp1"),
            sessionId = s1
        )
        AgentMemoryStore.appendChatEntry(
            scId,
            AgentChatEntry("m2", ChatEntryType.ASSISTANT, "Agent", "ExoPlayer berhasil ditambahkan ke MainActivity."),
            sessionId = s1
        )
        AgentMemoryStore.appendChatEntry(
            scId,
            AgentChatEntry("m3", ChatEntryType.USER, "Anda", "Sekarang ubah warna tombol like menjadi biru"),
            sessionId = s1
        )

        // Fork at m2
        val forked = AgentMemoryStore.forkSession(scId, s1, upToEntryId = "m2", setActive = false)
        assertEquals(s1, forked.forkedFromSessionId)
        val forkedEntries = AgentMemoryStore.loadChatEntries(scId, forked.id)
        assertEquals(2, forkedEntries.size)
        assertEquals("m2", forkedEntries.last().id)

        // Search across sessions
        val hits = AgentMemoryStore.searchSessions(scId, "ExoPlayer")
        assertTrue(hits.size >= 2)

        // Truncate s1 from m3 (inclusive) -> leaves m1 and m2
        val removed = AgentMemoryStore.truncateSessionFromEntry(scId, s1, fromEntryId = "m3", inclusive = true)
        assertEquals(1, removed.size)
        assertEquals("m3", removed.first().id)
        assertEquals(2, AgentMemoryStore.loadChatEntries(scId, s1).size)

        // Export Markdown
        val md = AgentMemoryStore.exportSessionMarkdown(scId, s1)
        assertTrue(md.contains("ExoPlayer"))
        assertTrue(md.contains("/tmp/cp1"))
    }
}
