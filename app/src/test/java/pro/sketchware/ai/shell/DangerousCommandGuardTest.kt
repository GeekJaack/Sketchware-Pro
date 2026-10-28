package pro.sketchware.ai.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DangerousCommandGuardTest {

    private fun blocked(cmd: String) = DangerousCommandGuard.inspect(cmd).blocked

    @Test
    fun `blocks recursive delete of critical roots`() {
        assertTrue(blocked("rm -rf /"))
        assertTrue(blocked("rm -rf /*"))
        assertTrue(blocked("rm -fr /storage/emulated/0"))
        assertTrue(blocked("rm -rf /sdcard/*"))
        assertTrue(blocked("rm -rf ~"))
        assertTrue(blocked("rm -rf \$HOME/*"))
    }

    @Test
    fun `blocks recursive delete in user storage outside sketchware`() {
        assertTrue(blocked("rm -rf /storage/emulated/0/DCIM"))
        assertTrue(blocked("sudo rm -rf '/storage/emulated/0/Pictures'"))
        assertTrue(blocked("cd /tmp && rm -rf /sdcard/Download"))
        assertTrue(blocked("FOO=1 rm -rf /"))
    }

    @Test
    fun `blocks deleting the sketchware parent folders but allows project files`() {
        assertTrue(blocked("rm -rf /storage/emulated/0/.sketchware"))
        assertTrue(blocked("rm -rf /storage/emulated/0/.sketchware/data"))
        assertFalse(blocked("rm -rf /storage/emulated/0/.sketchware/data/605/files/java/Foo.java"))
        assertFalse(blocked("rm -rf \"/storage/emulated/0/.sketchware/mysc/605/app/build\""))
    }

    @Test
    fun `allows ordinary commands`() {
        assertFalse(blocked("rm -rf build"))
        assertFalse(blocked("rm /storage/emulated/0/DCIM/a.jpg"))
        assertFalse(blocked("ls -la /storage/emulated/0"))
        assertFalse(blocked("find . -name '*.class' -delete"))
        assertFalse(blocked("echo rm -rf /"))
        assertFalse(blocked("apt-get install -y git && git clone https://example.com/x.git"))
    }

    @Test
    fun `blocks find delete on user storage`() {
        assertTrue(blocked("find /sdcard/Download -name '*.tmp' -delete"))
    }

    @Test
    fun `blocks disk and fork bomb patterns`() {
        assertTrue(blocked("mkfs.ext4 /dev/block/sda1"))
        assertTrue(blocked("dd if=/dev/zero of=/dev/sda bs=1M"))
        assertTrue(blocked(":(){ :|:& };:"))
        assertFalse(blocked("dd if=/dev/zero of=./test.img bs=1M count=1"))
    }

    @Test
    fun `verdict carries a reason`() {
        val v = DangerousCommandGuard.inspect("rm -rf /storage/emulated/0/DCIM")
        assertTrue(v.blocked)
        assertTrue(v.reason.isNotBlank())
    }
}
