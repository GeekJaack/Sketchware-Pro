package pro.sketchware.ai.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTextTest {

    @Test
    fun `strip removes SGR colors`() {
        assertEquals("red", AnsiStripper.strip("\u001B[31mred\u001B[0m"))
        assertEquals("ok", AnsiStripper.strip("\u001B[1;32mok\u001B[m"))
    }

    @Test
    fun `strip removes cursor and private mode sequences`() {
        assertEquals("hi", AnsiStripper.strip("\u001B[?25lhi\u001B[?25h"))
        assertEquals("ab", AnsiStripper.strip("a\u001B[2K\u001B[1Gb"))
    }

    @Test
    fun `strip removes OSC title sequences and charset switches`() {
        assertEquals("hello", AnsiStripper.strip("\u001B]0;window title\u0007hello"))
        assertEquals("x", AnsiStripper.strip("\u001B(Bx"))
    }

    @Test
    fun `strip keeps only the last carriage-return redraw of a progress line`() {
        assertEquals("100%\ndone", AnsiStripper.strip("10%\r50%\r100%\ndone"))
    }

    @Test
    fun `strip normalizes CRLF and drops backspace and NUL`() {
        assertEquals("a\nb", AnsiStripper.strip("a\r\nb"))
        assertEquals("ab", AnsiStripper.strip("a\u0008b\u0000"))
    }

    @Test
    fun `strip leaves plain text and tabs untouched`() {
        assertEquals("col1\tcol2\nline", AnsiStripper.strip("col1\tcol2\nline"))
    }

    @Test
    fun `incompleteTailStart holds back an unfinished escape only`() {
        assertEquals(3, AnsiStripper.incompleteTailStart("abc\u001B[3"))
        assertEquals(3, AnsiStripper.incompleteTailStart("abc\u001B"))
        val complete = "abc\u001B[31m"
        assertEquals(complete.length, AnsiStripper.incompleteTailStart(complete))
        assertEquals(5, AnsiStripper.incompleteTailStart("plain"))
    }

    @Test
    fun `PromptDetector recognizes common interactive prompts`() {
        assertTrue(PromptDetector.detect("Do you want to continue? [Y/n] ")!!.contains("y/n"))
        assertTrue(PromptDetector.detect("[sudo] Password: ")!!.contains("password"))
        assertNotNull(PromptDetector.detect("Python 3.11\n>>> "))
        assertNotNull(PromptDetector.detect("root@localhost:/# "))
    }

    @Test
    fun `PromptDetector ignores ordinary output`() {
        assertNull(PromptDetector.detect("Building project...\nDone\n"))
        assertNull(PromptDetector.detect(""))
    }

    @Test
    fun `session readNew returns cleaned output and survives ring-buffer trimming`() {
        val session = TerminalSession("t1", "host", null, null)
        session.append("\u001B[32mhello\u001B[0m\n")
        assertEquals("hello\n", session.readNew())

        // Fill past MAX_BUFFER: the unread cursor must follow the trimmed data, not jump to 0.
        val filler = "x".repeat(TerminalSession.MAX_BUFFER)
        session.append(filler)
        session.readNew()
        session.append("NEWLINE-MARKER\n")
        assertEquals("NEWLINE-MARKER\n", session.readNew())
    }

    @Test
    fun `session holds back a split escape sequence until it completes`() {
        val session = TerminalSession("t2", "host", null, null)
        session.append("ab\u001B[3")
        assertEquals("ab", session.readNew())
        session.append("1mcd")
        assertEquals("cd", session.readNew())
    }

    @Test
    fun `session readUntil returns as soon as the pattern matches`() {
        val session = TerminalSession("t3", "host", null, null)
        Thread {
            Thread.sleep(150)
            session.append("working...\nReady> ")
        }.start()
        val started = System.currentTimeMillis()
        val out = session.readUntil(Regex("Ready>"), 5_000L, 0L)
        assertTrue(out.contains("Ready>"))
        assertTrue(System.currentTimeMillis() - started < 3_000L)
    }
}
