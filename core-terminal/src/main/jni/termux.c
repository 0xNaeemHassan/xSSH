// SPDX-License-Identifier: Apache-2.0
// Derived from termux/termux-app terminal-emulator v0.118.3.

#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define XSSH_UNUSED(x) x __attribute__((__unused__))

static int throw_runtime_exception(JNIEnv *env, const char *message) {
    jclass exception_class = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message);
        (*env)->DeleteLocalRef(env, exception_class);
    }
    return -1;
}

static void free_string_array(char **values) {
    if (values == NULL) return;
    for (char **value = values; *value != NULL; ++value) free(*value);
    free(values);
}

static char **copy_java_string_array(JNIEnv *env, jobjectArray source, const char *label) {
    if (source == NULL) return NULL;
    jsize count = (*env)->GetArrayLength(env, source);
    if (count == 0) return NULL;

    char **result = calloc((size_t) count + 1U, sizeof(char *));
    if (result == NULL) {
        throw_runtime_exception(env, "Unable to allocate a native string array");
        return NULL;
    }

    for (jsize index = 0; index < count; ++index) {
        jstring java_value = (jstring) (*env)->GetObjectArrayElement(env, source, index);
        if (java_value == NULL) {
            free_string_array(result);
            throw_runtime_exception(env, label);
            return NULL;
        }
        const char *utf8 = (*env)->GetStringUTFChars(env, java_value, NULL);
        if (utf8 == NULL) {
            (*env)->DeleteLocalRef(env, java_value);
            free_string_array(result);
            return NULL;
        }
        result[index] = strdup(utf8);
        (*env)->ReleaseStringUTFChars(env, java_value, utf8);
        (*env)->DeleteLocalRef(env, java_value);
        if (result[index] == NULL) {
            free_string_array(result);
            throw_runtime_exception(env, "Unable to copy a native string");
            return NULL;
        }
    }
    return result;
}

static int create_subprocess(
        JNIEnv *env,
        const char *command,
        const char *working_directory,
        char *const arguments[],
        char **environment,
        int *process_id,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height) {
    int terminal_master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (terminal_master < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    char device_name[64];
    if (grantpt(terminal_master) || unlockpt(terminal_master) ||
            ptsname_r(terminal_master, device_name, sizeof(device_name))) {
        close(terminal_master);
        return throw_runtime_exception(env, "Cannot initialize the pseudo-terminal");
    }

    struct termios terminal_attributes;
    if (tcgetattr(terminal_master, &terminal_attributes) == 0) {
        terminal_attributes.c_iflag |= IUTF8;
        terminal_attributes.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(terminal_master, TCSANOW, &terminal_attributes);
    }

    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = (unsigned short) (columns * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height),
    };
    ioctl(terminal_master, TIOCSWINSZ, &size);

    pid_t child = fork();
    if (child < 0) {
        close(terminal_master);
        return throw_runtime_exception(env, "Fork failed");
    }
    if (child > 0) {
        *process_id = (int) child;
        return terminal_master;
    }

    sigset_t signals_to_unblock;
    sigfillset(&signals_to_unblock);
    sigprocmask(SIG_UNBLOCK, &signals_to_unblock, NULL);

    close(terminal_master);
    setsid();

    int terminal_slave = open(device_name, O_RDWR);
    if (terminal_slave < 0) _exit(1);
    dup2(terminal_slave, STDIN_FILENO);
    dup2(terminal_slave, STDOUT_FILENO);
    dup2(terminal_slave, STDERR_FILENO);

    DIR *descriptor_directory = opendir("/proc/self/fd");
    if (descriptor_directory != NULL) {
        int descriptor_directory_fd = dirfd(descriptor_directory);
        struct dirent *entry;
        while ((entry = readdir(descriptor_directory)) != NULL) {
            int descriptor = atoi(entry->d_name);
            if (descriptor > STDERR_FILENO && descriptor != descriptor_directory_fd) close(descriptor);
        }
        closedir(descriptor_directory);
    }

    clearenv();
    if (environment != NULL) {
        for (char **value = environment; *value != NULL; ++value) putenv(*value);
    }

    if (chdir(working_directory) != 0) {
        perror("chdir()");
        fflush(stderr);
    }
    execvp(command, arguments);
    perror("execvp()");
    _exit(1);
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv *env,
        jclass XSSH_UNUSED(clazz),
        jstring command,
        jstring working_directory,
        jobjectArray arguments,
        jobjectArray environment,
        jintArray process_id_array,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height) {
    char **native_arguments = copy_java_string_array(env, arguments, "A process argument was null");
    if ((*env)->ExceptionCheck(env)) return -1;
    char **native_environment = copy_java_string_array(env, environment, "An environment entry was null");
    if ((*env)->ExceptionCheck(env)) {
        free_string_array(native_arguments);
        return -1;
    }

    const char *native_command = (*env)->GetStringUTFChars(env, command, NULL);
    if (native_command == NULL) {
        free_string_array(native_arguments);
        free_string_array(native_environment);
        return -1;
    }
    const char *native_working_directory = (*env)->GetStringUTFChars(env, working_directory, NULL);
    if (native_working_directory == NULL) {
        (*env)->ReleaseStringUTFChars(env, command, native_command);
        free_string_array(native_arguments);
        free_string_array(native_environment);
        return -1;
    }

    int process_id = 0;
    int terminal_master = create_subprocess(
        env,
        native_command,
        native_working_directory,
        native_arguments,
        native_environment,
        &process_id,
        rows,
        columns,
        cell_width,
        cell_height);

    (*env)->ReleaseStringUTFChars(env, command, native_command);
    (*env)->ReleaseStringUTFChars(env, working_directory, native_working_directory);
    free_string_array(native_arguments);
    free_string_array(native_environment);
    if (terminal_master < 0 || (*env)->ExceptionCheck(env)) return -1;

    jint *process_id_target = (*env)->GetPrimitiveArrayCritical(env, process_id_array, NULL);
    if (process_id_target == NULL) {
        close(terminal_master);
        return throw_runtime_exception(env, "Cannot update the process ID");
    }
    *process_id_target = process_id;
    (*env)->ReleasePrimitiveArrayCritical(env, process_id_array, process_id_target, 0);
    return terminal_master;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(
        JNIEnv *XSSH_UNUSED(env),
        jclass XSSH_UNUSED(clazz),
        jint descriptor,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height) {
    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = (unsigned short) (columns * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height),
    };
    ioctl(descriptor, TIOCSWINSZ, &size);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(
        JNIEnv *XSSH_UNUSED(env),
        jclass XSSH_UNUSED(clazz),
        jint descriptor) {
    struct termios terminal_attributes;
    if (tcgetattr(descriptor, &terminal_attributes) == 0 &&
            (terminal_attributes.c_iflag & IUTF8) == 0) {
        terminal_attributes.c_iflag |= IUTF8;
        tcsetattr(descriptor, TCSANOW, &terminal_attributes);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(
        JNIEnv *XSSH_UNUSED(env),
        jclass XSSH_UNUSED(clazz),
        jint process_id) {
    int status;
    if (waitpid(process_id, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(
        JNIEnv *XSSH_UNUSED(env),
        jclass XSSH_UNUSED(clazz),
        jint descriptor) {
    close(descriptor);
}
