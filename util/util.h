//
// Created by x150 on 24.08.23.
//

#pragma once

#include <cassert>

#include "export.h"

#define assertm(exp, msg) assert(((void)msg, exp))

//#define J2CC_DBG

#ifdef J2CC_DBG
#define DBG(msgf, ...) printf("[j2cc] %s at %d: " msgf "\n", __FILE_NAME__, __LINE__ __VA_OPT__(,) __VA_ARGS__); fflush(stdout);
#else
#define DBG(msg_expr, ...)
#endif

// these are defined by compiler in actual build - dummy definition
#ifndef DYN_CACHE_SIZE
#define DYN_CACHE_SIZE 0
#endif

#ifndef CLAZZ_CACHE_SIZE
#define CLAZZ_CACHE_SIZE 0
#endif
#ifndef FIELD_CACHE_SIZE
#define FIELD_CACHE_SIZE 0
#endif
#ifndef METHOD_CACHE_SIZE
#define METHOD_CACHE_SIZE 0
#endif

// /compiler defined dummy definitions

#include <jni.h>
#include <cstring>
#include <sstream>
#include <mutex>

#include "chacha20.h"

struct StringCpInfo {
    char* ptr;
    size_t n;
};

extern StringCpInfo* stringConstantPool;

#define STRING_CP(i) (stringConstantPool->ptr+(i))

inline size_t roundUp(size_t n, size_t x) {
    return ((n + x - 1) / x) * x;
}

static void initChachaTable(JNIEnv* env, jbyteArray jba) {
    if (stringConstantPool == nullptr) return;
    unsigned char keyData[48];
    env->GetByteArrayRegion(jba, 0, 48, reinterpret_cast<jbyte*>(keyData));
    size_t ctLen = roundUp(stringConstantPool->n, 64);
    unsigned char chachaTable[ctLen];
    // one block is 64 bytes. we have n bytes in total
    size_t nBlocks = ctLen / 64;
    fuckMyShitUp(keyData, chachaTable, nBlocks);
    for (size_t i = 0; i < stringConstantPool->n; i++) {
        stringConstantPool->ptr[i] = static_cast<char>(stringConstantPool->ptr[i] ^ chachaTable[i]);
    }
}

static bool stringsEqual(JNIEnv* env, jstring a, jstring b) {
    jsize lenA = env->GetStringLength(a);
    if (jsize lenB = env->GetStringLength(b); lenA != lenB) return false;
    const jchar* cA = env->GetStringCritical(a, nullptr);
    const jchar* cB = env->GetStringCritical(b, nullptr);
    bool res = true;
    for(jsize p = 0; p < lenA; ++p) {
        if (cA[p] != cB[p]) { res = false; break; }
    }
    env->ReleaseStringCritical(a, cA);
    env->ReleaseStringCritical(b, cB);
    return res;
}

namespace cache {
    static jclass clazzCache[CLAZZ_CACHE_SIZE] = {};

    static jmethodID methodCache[METHOD_CACHE_SIZE] = {};
    static jfieldID fieldCache[FIELD_CACHE_SIZE] = {};

    static jobject dynamicCache[DYN_CACHE_SIZE] = {};

    static std::recursive_mutex clazzMutex;
    static std::mutex methodMutex;
    static std::mutex fieldMutex;

    static jclass java_lang_Class = nullptr;
    static jmethodID java_lang_Class_getName = nullptr;

    void ensureClazz(JNIEnv* env);

    std::string clazzName(JNIEnv* env, jclass clazz, bool* ok);

    bool getCachedValue(int key, jobject* target);
    void putCachedValue(int key, jobject target);

    jclass findClass(JNIEnv *env, const char* name, int slot);
    jfieldID getNonstaticField(JNIEnv* env, const jclass& clazz, const char* name, const char* desc, int index);
    jfieldID getStaticField(JNIEnv* env, const jclass& clazz, const char* name, const char* desc, int index);
    jmethodID getNonstaticMethod(JNIEnv* env, const jclass& clazz, const char* name, const char* desc, int index);
    jmethodID getStaticMethod(JNIEnv* env, const jclass& clazz, const char* name, const char* desc, int index);
}

static struct {
    bool initialized = false;
    jclass java_lang_NullpointerException = nullptr;
    jclass java_lang_Object = nullptr;
    jclass IntegerCache = nullptr;
    jclass java_lang_Integer = nullptr;
    jclass java_lang_Character = nullptr;
    jclass java_lang_CharacterCache = nullptr;
} commonClasses;

void ensureCommonClassesInit(JNIEnv* env);

#define J2_INTRINSIC(cl, name, ...) j2cc_intrinsic_ ## cl ## _ ## name(JNIEnv* env, jobject instance __VA_OPT__(,) __VA_ARGS__)
#define J2_INTRINSIC_STATIC(cl, name, ...) j2cc_intrinsic_ ## cl ## _ ## name(JNIEnv* env __VA_OPT__(,) __VA_ARGS__)
#define J2_INTRINSIC_HEADER(methodRep, ...) if (!instance) {\
        ensureCommonClassesInit(env); \
        env->ThrowNew(commonClasses.java_lang_NullpointerException, "Cannot invoke " methodRep);\
    }

jclass J2_INTRINSIC(java_lang_Object, getClass);

jint J2_INTRINSIC(java_lang_String, length);
jboolean J2_INTRINSIC(java_lang_String, isEmpty);
jboolean J2_INTRINSIC(java_lang_String, equals, jobject thatStr);

jobject J2_INTRINSIC_STATIC(java_lang_Integer, valueOf, jint value);

jobject J2_INTRINSIC_STATIC(java_lang_Character, valueOf, jchar value);