#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <android/log.h>

#ifndef TIOCGPTN
#define TIOCGPTN _IOR('T', 0x30, unsigned int)
#endif
#ifndef TIOCSPTLCK
#define TIOCSPTLCK _IOW('T', 0x31, int)
#endif
#ifndef TIOCSCTTY
#define TIOCSCTTY 0x540E
#endif
#ifndef TIOCSWINSZ
#define TIOCSWINSZ _IOW('T', 0x67, struct winsize)
#endif

#define TAG "NativePty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void set_winsize(int fd, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    if (rows > 0) ws.ws_row = (unsigned short) rows;
    if (cols > 0) ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

/*
 * Allocates a pseudo-terminal pair, forks, and runs `command` via `/system/bin/sh -c`
 * with the slave end wired to the child's stdin/stdout/stderr and controlling terminal.
 * Returns the master fd, or -1 on failure. The child pid is stored into pidOut[0].
 */
JNIEXPORT jint JNICALL
Java_pro_sketchware_ai_shell_Pty_createSubprocess(
        JNIEnv *env, jobject thiz,
        jstring command, jstring cwd, jintArray pidOut,
        jint rows, jint cols) {

    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (master < 0) {
        LOGE("open /dev/ptmx failed: %s", strerror(errno));
        return -1;
    }

    int unlock = 0;
    if (ioctl(master, TIOCSPTLCK, &unlock) != 0) {
        LOGE("TIOCSPTLCK failed: %s", strerror(errno));
        close(master);
        return -1;
    }

    unsigned int ptn = 0;
    if (ioctl(master, TIOCGPTN, &ptn) != 0) {
        LOGE("TIOCGPTN failed: %s", strerror(errno));
        close(master);
        return -1;
    }

    char slave_name[64];
    snprintf(slave_name, sizeof(slave_name), "/dev/pts/%u", ptn);

    set_winsize(master, rows, cols);

    const char *cmd = (*env)->GetStringUTFChars(env, command, NULL);
    const char *dir = cwd ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        if (dir) (*env)->ReleaseStringUTFChars(env, cwd, dir);
        (*env)->ReleaseStringUTFChars(env, command, cmd);
        close(master);
        return -1;
    }

    if (pid == 0) {
        /* Child: detach, adopt the slave as controlling terminal, exec the command. */
        close(master);
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        if (dir) chdir(dir);
        setenv("TERM", "xterm-256color", 1);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *) NULL);
        _exit(127);
    }

    if (dir) (*env)->ReleaseStringUTFChars(env, cwd, dir);
    (*env)->ReleaseStringUTFChars(env, command, cmd);

    if (pidOut != NULL) {
        jint p = (jint) pid;
        (*env)->SetIntArrayRegion(env, pidOut, 0, 1, &p);
    }
    return master;
}

JNIEXPORT void JNICALL
Java_pro_sketchware_ai_shell_Pty_setWindowSize(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    if (fd >= 0) set_winsize(fd, rows, cols);
}

JNIEXPORT jint JNICALL
Java_pro_sketchware_ai_shell_Pty_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

JNIEXPORT void JNICALL
Java_pro_sketchware_ai_shell_Pty_closeFd(JNIEnv *env, jobject thiz, jint fd) {
    if (fd >= 0) close(fd);
}

/*
 * Sends [sig] to the whole process group led by [pid]. The PTY child calls setsid(), so its pid
 * is also its process-group id; this reaches grandchildren (proot tracees, apt, npm, python...)
 * that a plain kill(pid) would leave orphaned. Falls back to kill(pid) when killpg fails.
 */
JNIEXPORT jint JNICALL
Java_pro_sketchware_ai_shell_Pty_killProcessGroup(JNIEnv *env, jobject thiz, jint pid, jint sig) {
    if (pid <= 1) return -1;
    int r = killpg((pid_t) pid, sig);
    if (r != 0) r = kill((pid_t) pid, sig);
    return r;
}
