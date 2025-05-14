//
// Created by x150 on 07.12.23.
//

#include <cstring>
#include <iostream>
#include "util.h"

void cache::ensureClazz(JNIEnv *env) {
    if (!java_lang_Class) {
        java_lang_Class = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Class")));
    }
    if (!java_lang_Class_getName) java_lang_Class_getName = env->GetMethodID(java_lang_Class, "getName", "()Ljava/lang/String;");
}

std::string cache::clazzName(JNIEnv *env, jclass clazz, bool* ok) {
    ensureClazz(env);
    const auto classNameRet = static_cast<jstring>(env->CallObjectMethod(clazz, java_lang_Class_getName));
    if (env->ExceptionCheck()) {
        *ok = false;
        return "";
    }
    const char* utfc = env->GetStringUTFChars(classNameRet, nullptr);
    std::string cpy = utfc;
    env->ReleaseStringUTFChars(classNameRet, utfc);
    *ok = true;
    return cpy;
}

bool cache::getCachedValue(const int key, jobject *target) {
    if (jobject k = dynamicCache[key]; k == nullptr) {
        DBG("cache miss on key %d", key)
        return false;
    }

    *target = dynamicCache[key];
    DBG("cache hit on key %d: %p", key, *target)
    return true;
}

void cache::putCachedValue(const int key, jobject target) {
    DBG("put cache with key %d: %p", key, target)
    dynamicCache[key] = target;
}

jclass cache::findClass(JNIEnv *env, const char* name, const int slot) {
    std::scoped_lock lock(clazzMutex);
    DBG("finding class %s in slot %d", name, slot)
    if (clazzCache[slot] != nullptr) [[likely]] {
        jclass p = clazzCache[slot];
        DBG("... result (short): %p", p)
        return p;
    }
    // FIXME theoretical memory leak: class A <clinit> has A.something(). FindClass A calls clinit, calls FindClass A.
    // latter FindClass A runs, makes a new global ref, stores it, returns
    // former FindClass A finishes due to clinit run, overwrites the now stored global ref with a new one
    // -> one global ref is lost
    // proper fix: load class without calling ctor, Class.forName(..., false)?
    jclass jc = env->FindClass(name);
    if (!jc && env->ExceptionCheck()) return nullptr;
    assertm(jc != nullptr, "class ref is not empty");

    // round 2 to figure out if calling FindClass recursively called another FindClass and populated the cache in the meantime
    if (clazzCache[slot] != nullptr) [[unlikely]] {
        jclass p = clazzCache[slot];
        DBG("... result (short 2nd; recursive FindClass filled it): %p", p)
        return p;
    }

    const auto found = static_cast<jclass>(env->NewGlobalRef(jc));
    clazzCache[slot] = found;
    DBG("... result (long): %p (global ref of %p)", found, jc)
    return found;
}

jfieldID cache::getNonstaticField(JNIEnv* env, const jclass& clazz, const char* name, const char* desc, int index) {
#ifdef J2CC_DBG
    bool ok;
    std::string clName = clazzName(env, clazz, &ok);
    if (!ok) clName = "<<UNKNOWN!!>>";
    DBG("finding field %s.%s:%s in slot %d", clName.c_str(), name, desc, index)
#endif
    std::scoped_lock lk(fieldMutex);
    if (fieldCache[index] != nullptr) [[likely]] {
        jfieldID j = fieldCache[index];
        return j;
    }
    jfieldID jf = env->GetFieldID(clazz, name, desc);
    if (!jf && env->ExceptionCheck()) return nullptr;
    assertm(jf != nullptr, "field ref is not empty");
    fieldCache[index] = jf;
    return jf;
}

jfieldID cache::getStaticField(JNIEnv* env, const jclass& clazz, const char* name, const char* desc, int index) {
#ifdef J2CC_DBG
    bool ok;
    std::string clName = clazzName(env, clazz, &ok);
    if (!ok) clName = "<<UNKNOWN!!>>";
    DBG("finding static field %s.%s:%s in slot %d", clName.c_str(), name, desc, index)
#endif
    std::scoped_lock lk(fieldMutex);
    if (fieldCache[index] != nullptr) [[likely]] {
        jfieldID j = fieldCache[index];
        return j;
    }
    jfieldID jf = env->GetStaticFieldID(clazz, name, desc);
    if (!jf && env->ExceptionCheck()) return nullptr;

    assertm(jf != nullptr, "field ref is not empty");

    fieldCache[index] = jf;
    return jf;
}

jmethodID cache::getNonstaticMethod(JNIEnv* env, const jclass& clazz, const char* name, const char* desc, int index) {
#ifdef J2CC_DBG
    bool ok;
    std::string clName = clazzName(env, clazz, &ok);
    if (!ok) clName = "<<UNKNOWN!!>>";
    DBG("finding method %s.%s%s in slot %d", clName.c_str(), name, desc, index)
#endif
    std::scoped_lock lk(methodMutex);
    if (methodCache[index] != nullptr) [[likely]] {
        jmethodID jm = methodCache[index];
        return jm;
    }
    jmethodID jf = env->GetMethodID(clazz, name, desc);

    if (!jf && env->ExceptionCheck()) return nullptr;

    assertm(jf != nullptr, "method ref is not empty");

    methodCache[index] = jf;
    return jf;
}

jmethodID cache::getStaticMethod(JNIEnv* env, const jclass& clazz, const char* name, const char* desc, int index) {
#ifdef J2CC_DBG
    bool ok;
    std::string clName = clazzName(env, clazz, &ok);
    if (!ok) clName = "<<UNKNOWN!!>>";
    DBG("finding static method %s.%s%s in slot %d", clName.c_str(), name, desc, index)
#endif
    std::scoped_lock lk(methodMutex);
    if (methodCache[index] != nullptr) [[likely]] {
        jmethodID jm = methodCache[index];
        return jm;
    }
    jmethodID jf = env->GetStaticMethodID(clazz, name, desc);

    if (!jf && env->ExceptionCheck()) return nullptr;

    assertm(jf != nullptr, "method ref is not empty");

    methodCache[index] = jf;
    return jf;
}


#define GLOBAL_REF(t, v) static_cast<t>(env->NewGlobalRef(v))
#define FIND_CLASS_AND_GREF(t) static_cast<jclass>(env->NewGlobalRef(env->FindClass(t)))

void ensureCommonClassesInit(JNIEnv *env) {
    if (!commonClasses.initialized) {
        commonClasses.initialized = true;
        commonClasses.java_lang_NullpointerException = FIND_CLASS_AND_GREF("java/lang/NullPointerException");
        commonClasses.java_lang_Object = FIND_CLASS_AND_GREF("java/lang/Object");
        commonClasses.IntegerCache = FIND_CLASS_AND_GREF("java/lang/Integer$IntegerCache");
        commonClasses.java_lang_Integer = FIND_CLASS_AND_GREF("java/lang/Integer");
        commonClasses.java_lang_Character = FIND_CLASS_AND_GREF("java/lang/Character");
        commonClasses.java_lang_CharacterCache = FIND_CLASS_AND_GREF("java/lang/Character$CharacterCache");
    }
}

jclass J2_INTRINSIC(java_lang_Object, getClass) {
    J2_INTRINSIC_HEADER("Object.getClass()")
    return env->GetObjectClass(instance);
}

jint J2_INTRINSIC(java_lang_String, length) {
    J2_INTRINSIC_HEADER("java.lang.String.length()")
    return env->GetStringLength(static_cast<jstring>(instance));
}
jboolean J2_INTRINSIC(java_lang_String, isEmpty) {
    J2_INTRINSIC_HEADER("java.lang.String.isEmpty()")
    return env->GetStringLength(static_cast<jstring>(instance)) == 0;
}
jboolean J2_INTRINSIC(java_lang_String, equals, jobject thatStr) {
    J2_INTRINSIC_HEADER("java.lang.String.equals(Object)")
    ensureCommonClassesInit(env);
    if (!env->IsInstanceOf(instance, commonClasses.java_lang_Object)) {
        return false;
    }
    auto jstr = static_cast<jstring>(instance);
    auto thatJS = static_cast<jstring>(thatStr);
    return stringsEqual(env, jstr, thatJS);
}

jobject J2_INTRINSIC_STATIC(java_lang_Integer, valueOf, jint value) {
    ensureCommonClassesInit(env);
    static jint icLo = 0;
    static jint icHi = 0;
    static jobjectArray icCache = nullptr;
    if (icLo == 0) {
        jfieldID ficLo = env->GetStaticFieldID(commonClasses.IntegerCache, "low", "I");
        jfieldID ficHi = env->GetStaticFieldID(commonClasses.IntegerCache, "high", "I");
        jfieldID ficCache = env->GetStaticFieldID(commonClasses.IntegerCache, "cache", "[Ljava/lang/Integer;");
        icLo = env->GetStaticIntField(commonClasses.IntegerCache, ficLo);
        icHi = env->GetStaticIntField(commonClasses.IntegerCache, ficHi);
        icCache = static_cast<jobjectArray>(env->NewGlobalRef(env->GetStaticObjectField(commonClasses.IntegerCache, ficCache)));
    }
    if (value >= icLo && value <= icHi) {
        return env->GetObjectArrayElement(icCache, value - icLo);
    }
    return env->NewObject(commonClasses.java_lang_Integer, env->GetMethodID(commonClasses.java_lang_Integer, "<init>", "(I)V"), value);
}

jobject J2_INTRINSIC_STATIC(java_lang_Character, valueOf, jchar value) {
    ensureCommonClassesInit(env);
    if (value <= 127) {
        static jobjectArray ccCache = nullptr;
        if (ccCache == nullptr) {
            jfieldID ccCachef = env->GetStaticFieldID(commonClasses.java_lang_CharacterCache, "cache", "[Ljava/lang/Character;");
            ccCache = static_cast<jobjectArray>(env->NewGlobalRef(env->GetStaticObjectField(commonClasses.java_lang_CharacterCache, ccCachef)));
        }
        return env->GetObjectArrayElement(ccCache, value);
    }
    return env->NewObject(commonClasses.java_lang_Character, env->GetMethodID(commonClasses.java_lang_Character, "<init>", "(C)V"), value);
}