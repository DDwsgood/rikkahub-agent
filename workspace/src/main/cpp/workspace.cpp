#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <string>
#include <vector>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1 << 0)
#endif

namespace {

struct ScopedFd {
    int fd = -1;

    ScopedFd() = default;
    explicit ScopedFd(int f) : fd(f) {}
    ~ScopedFd() {
        if (fd >= 0) {
            close(fd);
        }
    }
    ScopedFd(const ScopedFd &) = delete;
    ScopedFd &operator=(const ScopedFd &) = delete;
    ScopedFd(ScopedFd &&other) noexcept : fd(other.fd) { other.fd = -1; }
    ScopedFd &operator=(ScopedFd &&other) noexcept {
        if (this != &other) {
            if (fd >= 0) close(fd);
            fd = other.fd;
            other.fd = -1;
        }
        return *this;
    }
};

/**
 * Copies standard UTF-8 bytes from a Java `ByteArray` (never JNI Modified UTF-8, so
 * 4-byte supplementary-plane Unicode characters like emoji match Linux filesystem bytes
 * exactly). Rejects empty or NUL-containing buffers.
 */
bool copyUtf8Bytes(JNIEnv *env, jbyteArray arr, std::string &out) {
    if (arr == nullptr) return false;
    const jsize len = env->GetArrayLength(arr);
    if (len < 0) return false;
    out.resize(static_cast<size_t>(len));
    if (len > 0) {
        env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(out.data()));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
    }
    for (char c : out) {
        if (c == '\0') return false;
    }
    return true;
}

bool isSafeSegment(const std::string &seg) {
    if (seg.empty() || seg == "." || seg == "..") return false;
    for (char c : seg) {
        if (c == '/' || c == '\\' || c == '\0') return false;
    }
    return true;
}

bool copySafeSegments(JNIEnv *env, jobjectArray arr, std::vector<std::string> &out) {
    if (arr == nullptr) return true;
    const jsize len = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(arr, i));
        std::string seg;
        const bool copied = copyUtf8Bytes(env, item, seg);
        if (item != nullptr) env->DeleteLocalRef(item);
        if (!copied || !isSafeSegment(seg)) return false;
        out.push_back(std::move(seg));
    }
    return true;
}

int openSubdirNoFollow(int parentFd, const std::string &seg, bool createMissing) {
    int fd = openat(parentFd, seg.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0 && errno == ENOENT && createMissing) {
        if (mkdirat(parentFd, seg.c_str(), 0700) != 0 && errno != EEXIST) {
            return -errno;
        }
        fd = openat(parentFd, seg.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    }
    if (fd < 0) {
        int err = errno;
        if (err == ENOTDIR || err == ELOOP) {
            struct stat probe {};
            if (fstatat(parentFd, seg.c_str(), &probe, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(probe.st_mode)) {
                return -ELOOP;
            }
        }
        return -err;
    }
    return fd;
}

}  // namespace

/**
 * Atomically moves `srcDirSegs/srcLeaf` to `dstDirSegs/dstLeaf` relative to pinned `rootPath`
 * strictly via Linux `SYS_renameat2(..., RENAME_NOREPLACE)` (no overwrite, no symlink traversal).
 *
 * Refuses to fall back to non-atomic `linkat + unlinkat` or copy+delete if the kernel or
 * filesystem rejects `RENAME_NOREPLACE`: returns `-ENOSYS` so the caller fails closed.
 *
 * Returns 0 on success or `-errno` on failure:
 *   -EEXIST  destination already exists (never overwritten)
 *   -ENOENT  source or non-creatable ancestor does not exist
 *   -ELOOP   root, ancestor, or source is a symbolic link
 *   -EINVAL  invalid segment or attempt to move a directory into itself
 *   -EXDEV   cross-filesystem move (rejected instead of unsafe copy+delete)
 *   -ENOSYS  kernel/filesystem refuses atomic RENAME_NOREPLACE
 */
extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_NativeSafeFiles_nativeMoveNoReplace(
        JNIEnv *env,
        jclass,
        jbyteArray jRootPath,
        jobjectArray jSrcDirSegs,
        jbyteArray jSrcLeaf,
        jobjectArray jDstDirSegs,
        jbyteArray jDstLeaf,
        jboolean createMissingDstDirs) {
    std::string rootPath;
    std::string srcLeaf;
    std::string dstLeaf;
    std::vector<std::string> srcDirSegs;
    std::vector<std::string> dstDirSegs;

    if (!copyUtf8Bytes(env, jRootPath, rootPath) || rootPath.empty()) return -EINVAL;
    if (!copyUtf8Bytes(env, jSrcLeaf, srcLeaf) || !isSafeSegment(srcLeaf)) return -EINVAL;
    if (!copyUtf8Bytes(env, jDstLeaf, dstLeaf) || !isSafeSegment(dstLeaf)) return -EINVAL;
    if (!copySafeSegments(env, jSrcDirSegs, srcDirSegs)) return -EINVAL;
    if (!copySafeSegments(env, jDstDirSegs, dstDirSegs)) return -EINVAL;

    ScopedFd rootFd(open(rootPath.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (rootFd.fd < 0) {
        int err = errno;
        struct stat probe {};
        if (lstat(rootPath.c_str(), &probe) == 0 && S_ISLNK(probe.st_mode)) {
            return -ELOOP;
        }
        return -err;
    }
    struct stat rootSt {};
    if (fstat(rootFd.fd, &rootSt) != 0) return -errno;
    if (!S_ISDIR(rootSt.st_mode)) return -ENOTDIR;

    // Walk source ancestor directories relative to rootFd.
    ScopedFd ownedSrcDir;
    int srcDirFd = rootFd.fd;
    for (const auto &seg : srcDirSegs) {
        int next = openSubdirNoFollow(srcDirFd, seg, false);
        if (next < 0) return next;
        ownedSrcDir = ScopedFd(next);
        srcDirFd = ownedSrcDir.fd;
    }

    struct stat srcSt {};
    if (fstatat(srcDirFd, srcLeaf.c_str(), &srcSt, AT_SYMLINK_NOFOLLOW) != 0) {
        return -errno;
    }
    if (S_ISLNK(srcSt.st_mode)) {
        return -ELOOP;
    }
    if (!S_ISREG(srcSt.st_mode) && !S_ISDIR(srcSt.st_mode)) {
        return -EINVAL;
    }

    // Walk destination ancestor directories relative to rootFd and verify no dir-into-self cycle.
    if (S_ISDIR(srcSt.st_mode) &&
        rootSt.st_dev == srcSt.st_dev &&
        rootSt.st_ino == srcSt.st_ino) {
        return -EINVAL;
    }
    ScopedFd ownedDstDir;
    int dstDirFd = rootFd.fd;
    for (const auto &seg : dstDirSegs) {
        int next = openSubdirNoFollow(dstDirFd, seg, createMissingDstDirs == JNI_TRUE);
        if (next < 0) return next;
        ownedDstDir = ScopedFd(next);
        dstDirFd = ownedDstDir.fd;
        if (S_ISDIR(srcSt.st_mode)) {
            struct stat ancSt {};
            if (fstat(dstDirFd, &ancSt) != 0) return -errno;
            if (ancSt.st_dev == srcSt.st_dev && ancSt.st_ino == srcSt.st_ino) {
                return -EINVAL;
            }
        }
    }

#ifdef SYS_renameat2
    long rc = syscall(
            SYS_renameat2,
            srcDirFd,
            srcLeaf.c_str(),
            dstDirFd,
            dstLeaf.c_str(),
            static_cast<unsigned int>(RENAME_NOREPLACE));
#else
    errno = ENOSYS;
    long rc = -1;
#endif
    if (rc == 0) {
        return 0;
    }

    int err = errno;
    if (err == ENOSYS || err == EINVAL || err == EOPNOTSUPP) {
        // Strictly fail closed: never fall back to non-atomic linkat+unlinkat or copy+delete.
        return -ENOSYS;
    }
    return -err;
}
