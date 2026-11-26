package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceSdcardMode
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for the workspace sdcard guard (the thin path-aware layer from the
 * opencode-v2-security migration study). READ_ONLY denies every write/delete
 * primitive reaching /sdcard; READ_WRITE denies only public-directory-level
 * annihilation; NONE is a no-op (no mount).
 */
class WorkspaceSdcardGuardTest {

    private val ro = WorkspaceSdcardMode.READ_ONLY
    private val rw = WorkspaceSdcardMode.READ_WRITE
    private val none = WorkspaceSdcardMode.NONE

    // ------------------------------------------------------------------
    // NONE: no mount, nothing to guard
    // ------------------------------------------------------------------

    @Test fun `NONE mode guards nothing`() {
        assertNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/DCIM", none))
        assertNull(WorkspaceSdcardGuard.check("echo x > /sdcard/out.txt", none))
    }

    // ------------------------------------------------------------------
    // READ_ONLY: every write/delete/metadata primitive is denied
    // ------------------------------------------------------------------

    @Test fun `RO denies rm`() {
        assertNotNull(WorkspaceSdcardGuard.check("rm /sdcard/a.txt", ro))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/DCIM/2024", ro))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf '/sdcard/My Documents'", ro))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard", ro))
    }

    @Test fun `RO denies redirect writes`() {
        assertNotNull(WorkspaceSdcardGuard.check("echo hello > /sdcard/out.txt", ro))
        assertNotNull(WorkspaceSdcardGuard.check("echo hello >> /sdcard/out.txt", ro))
        assertNotNull(WorkspaceSdcardGuard.check("ls &> /sdcard/out.txt", ro))
        assertNotNull(WorkspaceSdcardGuard.check("ls 2> /sdcard/err.txt", ro))
    }

    @Test fun `RO denies redirect even on read commands`() {
        assertNotNull(WorkspaceSdcardGuard.check("cat /workspace/a.md > /sdcard/copy.md", ro))
    }

    @Test fun `RO denies move, copy, touch, mkdir, tee, truncate`() {
        assertNotNull(WorkspaceSdcardGuard.check("mv /sdcard/a.txt /workspace/", ro))
        assertNotNull(WorkspaceSdcardGuard.check("mv /workspace/a /sdcard/b", ro))
        assertNotNull(WorkspaceSdcardGuard.check("cp /workspace/a /sdcard/b", ro))
        assertNotNull(WorkspaceSdcardGuard.check("touch /sdcard/a.txt", ro))
        assertNotNull(WorkspaceSdcardGuard.check("mkdir /sdcard/newdir", ro))
        assertNotNull(WorkspaceSdcardGuard.check("tee /sdcard/x", ro))
        assertNotNull(WorkspaceSdcardGuard.check("truncate -s 0 /sdcard/x", ro))
    }

    @Test fun `RO denies metadata primitives and in-place editors`() {
        assertNotNull(WorkspaceSdcardGuard.check("chmod +x /sdcard/script.sh", ro))
        assertNotNull(WorkspaceSdcardGuard.check("chown root /sdcard/a", ro))
        assertNotNull(WorkspaceSdcardGuard.check("sed -i 's/a/b/' /sdcard/a.txt", ro))
    }

    @Test fun `RO denies dd of-target and archive extraction`() {
        assertNotNull(WorkspaceSdcardGuard.check("dd if=/workspace/a of=/sdcard/b", ro))
        assertNotNull(WorkspaceSdcardGuard.check("unzip -d /sdcard/out /workspace/a.zip", ro))
        assertNotNull(WorkspaceSdcardGuard.check("tar -xzf /workspace/a.tgz -C /sdcard/out", ro))
        assertNotNull(WorkspaceSdcardGuard.check("gzip /sdcard/photo.jpg", ro))
    }

    @Test fun `RO denies find actions and xargs chains`() {
        assertNotNull(WorkspaceSdcardGuard.check("find /sdcard -name '*.mp4' -delete", ro))
        assertNotNull(WorkspaceSdcardGuard.check("find /sdcard -type d -exec rm -rf {} +", ro))
        assertNotNull(WorkspaceSdcardGuard.check("find /sdcard -name '*.mp4' | xargs rm", ro))
    }

    @Test fun `RO denies wrapped payloads`() {
        assertNotNull(WorkspaceSdcardGuard.check("bash -c 'rm -rf /sdcard/DCIM'", ro))
        assertNotNull(WorkspaceSdcardGuard.check("sh -c \"cp /workspace/a /sdcard/b\"", ro))
        assertNotNull(WorkspaceSdcardGuard.check("eval 'rm /sdcard/x'", ro))
        assertNotNull(WorkspaceSdcardGuard.check("python3 -c 'open(\"/sdcard/x\", \"w\")'", ro))
    }

    @Test fun `RO denies via sudo and env wrappers`() {
        assertNotNull(WorkspaceSdcardGuard.check("sudo rm -rf /sdcard/DCIM", ro))
        assertNotNull(WorkspaceSdcardGuard.check("env TMPDIR=x rm -rf /sdcard/DCIM", ro))
        assertNotNull(WorkspaceSdcardGuard.check("timeout 10 rm -rf /sdcard/DCIM", ro))
    }

    @Test fun `RO allows reads and non-sdcard writes`() {
        assertNull(WorkspaceSdcardGuard.check("ls /sdcard/DCIM", ro))
        assertNull(WorkspaceSdcardGuard.check("cat /sdcard/notes.txt", ro))
        assertNull(WorkspaceSdcardGuard.check("grep -r keyword /sdcard/Download", ro))
        assertNull(WorkspaceSdcardGuard.check("rm -rf /workspace/build", ro))
        assertNull(WorkspaceSdcardGuard.check("echo hi > /workspace/out.txt", ro))
        assertNull(WorkspaceSdcardGuard.check("cp /sdcard/a.txt /workspace/", ro))
        assertNull(WorkspaceSdcardGuard.check("mv /workspace/a /workspace/b", ro))
    }

    @Test fun `RO redirect to dev-null and fd merges are inert`() {
        assertNull(WorkspaceSdcardGuard.check("ls /sdcard > /dev/null 2>&1", ro))
        assertNull(WorkspaceSdcardGuard.check("cat /sdcard/a 2>&1 | head", ro))
    }

    // ------------------------------------------------------------------
    // Path reduction: .., cd tracking, quoting
    // ------------------------------------------------------------------

    @Test fun `relative paths resolving into the mount are caught`() {
        // default cwd is /workspace; ../../sdcard IS /sdcard inside the rootfs
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf ../../sdcard/DCIM", ro))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf ../../sdcard", rw))
    }

    @Test fun `literal cd tracking changes the resolution base`() {
        assertNotNull(WorkspaceSdcardGuard.check("cd /sdcard && rm -rf .", ro))
        assertNotNull(WorkspaceSdcardGuard.check("cd /sdcard; rm -rf ./DCIM", ro))
        assertNotNull(WorkspaceSdcardGuard.check("cd /tmp && rm -rf ../sdcard", ro))
        // cd into the workspace files area: relative targets stay out of the mount
        assertNull(WorkspaceSdcardGuard.check("cd /workspace/sub && rm -rf .", ro))
    }

    @Test fun `dot segments are normalized`() {
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/./DCIM/../Pictures", ro))
        assertNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/../workspace/build", ro))
    }

    @Test fun `quoted targets are unwrapped`() {
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf \"/sdcard/DCIM\"", ro))
        assertNotNull(WorkspaceSdcardGuard.check("echo x > '/sdcard/out.txt'", ro))
    }

    @Test fun `separators inside quotes do not split segments`() {
        // `rm -rf '/sdcard/a;b'` is ONE target, not two segments
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf '/sdcard/a;b'", ro))
        assertNull(WorkspaceSdcardGuard.check("echo 'not a > /sdcard/redirect'", ro))
    }

    @Test fun `glob in zone target is caught in both modes`() {
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/*", ro))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/*", rw))
        assertNotNull(WorkspaceSdcardGuard.check("cp /workspace/a /sdcard/*", rw))
        assertNotNull(WorkspaceSdcardGuard.check("echo x > /sdcard/*.txt", rw))
    }

    // ------------------------------------------------------------------
    // READ_WRITE: only public-directory-level annihilation is denied
    // ------------------------------------------------------------------

    @Test fun `RW denies wiping the mount root`() {
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard", rw))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/", rw))
    }

    @Test fun `RW denies recursive delete of first-level public dirs`() {
        for (dir in listOf("DCIM", "Pictures", "Download", "Documents", "Music")) {
            assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/$dir", rw))
        }
    }

    @Test fun `RW denies find-delete and xargs chains at public level`() {
        assertNotNull(WorkspaceSdcardGuard.check("find /sdcard -delete", rw))
        assertNotNull(WorkspaceSdcardGuard.check("find /sdcard/DCIM -name '*.mp4' -delete", rw))
        assertNotNull(WorkspaceSdcardGuard.check("find /sdcard -name '*.jpg' | xargs rm", rw))
    }

    @Test fun `RW denies shred at any depth`() {
        assertNotNull(WorkspaceSdcardGuard.check("shred /sdcard/secret.bin", rw))
        assertNotNull(WorkspaceSdcardGuard.check("shred -u /sdcard/a/b/c.bin", rw))
    }

    @Test fun `RW allows deeper subdirectory operations and single files`() {
        assertNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/DCIM/2024/edit-session", rw))
        assertNull(WorkspaceSdcardGuard.check("rm /sdcard/tmp-notes.txt", rw))
        assertNull(WorkspaceSdcardGuard.check("mv /sdcard/a.txt /sdcard/b.txt", rw))
        assertNull(WorkspaceSdcardGuard.check("cp /workspace/report.md /sdcard/Documents/report.md", rw))
        assertNull(WorkspaceSdcardGuard.check("echo done > /sdcard/Download/out.log", rw))
        assertNull(WorkspaceSdcardGuard.check("sed -i 's/a/b/' /sdcard/Download/a.txt", rw))
        assertNull(WorkspaceSdcardGuard.check("mkdir /sdcard/Download/agent-out", rw))
        assertNull(WorkspaceSdcardGuard.check("find /sdcard/DCIM/2024 -name '*.tmp' -delete", rw))
    }

    @Test fun `RW allows non-recursive rm of a public dir file`() {
        // `rm /sdcard/Download/x.zip` deletes one file — approval chain, not a hard deny
        assertNull(WorkspaceSdcardGuard.check("rm /sdcard/Download/x.zip", rw))
    }

    // ------------------------------------------------------------------
    // Documented residual surface (kept stable deliberately)
    // ------------------------------------------------------------------

    @Test fun `dynamic targets fall through unproven`() {
        // $-expansion cannot be resolved lexically → passes to the approval chain
        assertNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/\$DIR", ro))
        assertNull(WorkspaceSdcardGuard.check("echo x > \$TARGET", ro))
    }

    @Test fun `tilde targets never resolve into the mount`() {
        // HOME inside the rootfs is /root, not the sdcard bind
        assertNull(WorkspaceSdcardGuard.check("rm -rf ~/anything", ro))
    }

    // ------------------------------------------------------------------
    // Hardened bypass closures (6 new guards)
    // ------------------------------------------------------------------

    @Test fun `RW denies long recursive flag and depth-2 critical user dirs`() {
        assertNotNull(WorkspaceSdcardGuard.check("rm --recursive -f /sdcard/DCIM", rw))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/DCIM/Camera", rw))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/Pictures/Screenshots", rw))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /sdcard/Android/media", rw))
    }

    @Test fun `RW denies moving protected sdcard directories`() {
        assertNotNull(WorkspaceSdcardGuard.check("mv /sdcard/DCIM /tmp/trash", rw))
        assertNotNull(WorkspaceSdcardGuard.check("mv /sdcard/DCIM/Camera /tmp/trash", rw))
        assertNotNull(WorkspaceSdcardGuard.check("mv /sdcard /tmp/trash", rw))
    }

    @Test fun `sequential relative cd tracking catches nested relative wipe`() {
        assertNotNull(WorkspaceSdcardGuard.check("cd /sdcard && cd DCIM && rm -rf .", rw))
        assertNotNull(WorkspaceSdcardGuard.check("cd /sdcard && cd DCIM && rm -rf *", rw))
    }

    @Test fun `host storage aliases normalize to sdcard`() {
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /storage/emulated/0/DCIM", rw))
        assertNotNull(WorkspaceSdcardGuard.check("rm -rf /storage/self/primary/Download", rw))
    }

    @Test fun `RW denies rsync delete on protected sdcard dirs`() {
        assertNotNull(WorkspaceSdcardGuard.check("rsync -a --delete /tmp/empty/ /sdcard/Download/", rw))
        assertNotNull(WorkspaceSdcardGuard.check("rsync -a --remove-source-files /sdcard/DCIM/ /tmp/out/", rw))
        assertNull(WorkspaceSdcardGuard.check("rsync -a /workspace/out/ /sdcard/Download/export/", rw))
    }

    @Test fun `RW denies variable indirection, symlink delete, and destructive scripts or heredocs`() {
        assertNotNull(WorkspaceSdcardGuard.check("D=/sdcard/DCIM; rm -rf \$D", rw))
        assertNotNull(WorkspaceSdcardGuard.check("ln -s /sdcard /tmp/s && rm -rf /tmp/s/", rw))
        assertNotNull(WorkspaceSdcardGuard.check("python3 -c 'import shutil; shutil.rmtree(\"/sdcard/DCIM\")'", rw))
        assertNotNull(WorkspaceSdcardGuard.check("node -e 'require(\"fs\").rm(\"/sdcard/DCIM\", {recursive:true})'", rw))
        assertNotNull(
            WorkspaceSdcardGuard.check(
                "python3 <<EOF\nimport shutil\nshutil.rmtree('/sdcard/Pictures')\nEOF",
                rw,
            )
        )
    }
}