/*
 * GoodbyeDPI Android - byedpi JNI koprusu.
 *
 * Kotlin: io.github.unsalable.goodbyedpi.engine.NativeBridge
 *   @JvmStatic external fun byedpiStart(args: Array<String>): Int   (bloklar)
 *   @JvmStatic external fun byedpiStop(): Int
 *
 * Isimler Java_... sembolleriyle degil RegisterNatives ile baglanir: R8 sinifi
 * yeniden adlandirirsa JNI_OnLoad hata verir ve System.loadLibrary acikca
 * patlar (sessizce yanlis metoda baglanmak yerine). Keep kurali proguard-rules.pro'da.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>

#include "byedpi_lib.h"

#define TAG "ciadpi"
#define CLASS_NAME "io/github/unsalable/goodbyedpi/engine/NativeBridge"

/* Upstream argv'den gelen isaretcileri calisma boyunca sakliyor; arguman
 * sayisini makul tutmak Kotlin tarafinin isi, burada yalnizca akil siniri. */
#define MAX_ARGS 1024


static jint native_start(JNIEnv *env, jclass clazz, jobjectArray args)
{
    (void)clazz;
    if (!args) {
        return BYEDPI_ERR_ARGS;
    }
    jsize n = (*env)->GetArrayLength(env, args);
    if (n < 0 || n > MAX_ARGS) {
        return BYEDPI_ERR_ARGS;
    }
    /* argv[0] program adi (getopt atlar), argv[n + 1] NULL */
    char **argv = calloc((size_t)n + 2, sizeof(char *));
    if (!argv) {
        return BYEDPI_ERR_INTERNAL;
    }
    jint ret = BYEDPI_ERR_INTERNAL;
    int argc = 0;

    if (!(argv[argc++] = strdup("ciadpi"))) {
        goto out;
    }
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        if ((*env)->ExceptionCheck(env)) {
            goto out;
        }
        if (!s) {
            ret = BYEDPI_ERR_ARGS;
            goto out;
        }
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        if (!c) {
            /* OutOfMemoryError bekliyor; Java'ya donunce firlar */
            (*env)->DeleteLocalRef(env, s);
            goto out;
        }
        argv[argc] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
        if (!argv[argc]) {
            goto out;
        }
        argc++;
    }
    argv[argc] = NULL;

    /* Bloklar; bu thread JVM'e bagli kalir ama dongu JNI cagrisi yapmaz. */
    ret = byedpi_lib_start(argc, argv);
    if (ret != BYEDPI_OK) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "byedpiStart: %d", ret);
    }
out:
    /* getopt_long argv dizisini yeniden siralayabilir; hepsi ayni kumeden */
    for (jsize i = 0; i < n + 1; i++) {
        free(argv[i]);
    }
    free(argv);
    return ret;
}


static jint native_stop(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return byedpi_lib_stop();
}


static const JNINativeMethod methods[] = {
    { "byedpiStart", "([Ljava/lang/String;)I", (void *)native_start },
    { "byedpiStop",  "()I",                    (void *)native_stop },
};


JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = (*env)->FindClass(env, CLASS_NAME);
    if (!cls) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "class not found: %s", CLASS_NAME);
        return JNI_ERR;
    }
    jint r = (*env)->RegisterNatives(env, cls, methods,
        (jint)(sizeof(methods) / sizeof(methods[0])));
    (*env)->DeleteLocalRef(env, cls);
    if (r != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "RegisterNatives failed: %d", (int)r);
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
