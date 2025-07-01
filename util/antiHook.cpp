//
// Created by x150 on 26 Sep 2024.
//

#include "antiHook.h"

#include <algorithm>
#include <exception>

#ifndef _WIN32
#include <dlfcn.h>
#else
#include <windows.h>
#include <memoryapi.h>
#endif

#include <cstring>
#include <string>

// testing
#ifndef HOOK_ACTION
#define HOOK_ACTION 1
#endif

#ifndef CALL_TARGET
#define CALL_TARGET "bruh"
#endif
#ifndef CALL_TARGET_M
#define CALL_TARGET_M "bruh"
#endif

#define STR_VALUE(arg)      #arg
#define FUNCTION_NAME(name) STR_VALUE(name)

#define CALL_TARGET_S FUNCTION_NAME(CALL_TARGET)
#define CALL_TARGET_M_S FUNCTION_NAME(CALL_TARGET_M)

static const JNINativeInterface_ *safeJNIItf = nullptr;

void checkForHookedFunctions(JNIEnv *env) {
	constexpr size_t noPtrs = sizeof(JNINativeInterface_) / sizeof(void *);
	void *const*start = &env->functions->reserved0; // start of function table (pointer to first function pointer)
	void *const*end = start + noPtrs; // exclusive end of function table (first pointer + nPointers)
	if (safeJNIItf != nullptr) {
		void *const*startSecond = &safeJNIItf->reserved0;
		for(size_t offset = 4; offset < noPtrs; offset++) {
			void *const*a = start + offset;
			void *const*b = startSecond + offset;
			if (*a != *b) {
				// function pointer differs
#if HOOK_ACTION == 0
				exit(0);
				std::terminate();
				*static_cast<char *>(nullptr) = 0; // intentional segfault
				return;
#elif HOOK_ACTION == 1
#ifndef _WIN32
				Dl_info dli;
				if (dladdr(*a, &dli) != 0) {
					jclass clazzToCall = safeJNIItf->FindClass(env, CALL_TARGET_S);
					jmethodID idToCall = safeJNIItf->GetStaticMethodID(env, clazzToCall, CALL_TARGET_M_S,
																	   "(Ljava/lang/String;JLjava/lang/String;J)V");
					safeJNIItf->CallStaticVoidMethod(env, clazzToCall, idToCall,
													 env->NewStringUTF(dli.dli_fname),
													 reinterpret_cast<jlong>(dli.dli_fbase),
													 env->NewStringUTF(dli.dli_sname),
													 reinterpret_cast<jlong>(dli.dli_saddr)
					);
				}
#else
				MEMORY_BASIC_INFORMATION mbi{};
				if (VirtualQuery(*a, &mbi, sizeof(mbi))) {
					const auto hmod = static_cast<HMODULE>(mbi.AllocationBase);
					char real[256];
					if (!GetModuleFileNameA(hmod, real, 256)) return; // no filename
					jclass clazzToCall = safeJNIItf->FindClass(env, CALL_TARGET_S);
					jmethodID idToCall = safeJNIItf->GetStaticMethodID(env, clazzToCall, CALL_TARGET_M_S, "(Ljava/lang/String;JLjava/lang/String;J)V");
					safeJNIItf->CallStaticVoidMethod(env, clazzToCall, idToCall,
						env->NewStringUTF(real),
						reinterpret_cast<jlong>(mbi.AllocationBase),
						nullptr,
						0
						);
				}
#endif
#endif
			}
		}
		return;
	}
	bool isSafe = true;
	// first 4 pointers are void* reserved
	std::for_each(start + 4, end, [env, &isSafe](void *const&ptr) {
		// ptr is a &reference and as such is automatically deref'd
#ifndef _WIN32
		Dl_info dli;
		if (dladdr(ptr, &dli) != 0) {
			// check if its from our libjvm
			const std::string checkAgainst =
#ifdef __APPLE__
					"/libjvm.dylib";
#elif defined(__linux__) || defined(__unix__)
					"/libjvm.so";
#else
#error "platform isnt windows and isn't mac or linux. please file a bug report"
#endif
			if (!std::string(dli.dli_fname).ends_with(checkAgainst)) {
				isSafe = false;
#if HOOK_ACTION == 0
				exit(0);
				std::terminate();
				*static_cast<char *>(nullptr) = 0; // intentional segfault
				return;
#elif HOOK_ACTION == 1
				jclass clazzToCall = env->FindClass(CALL_TARGET_S);
				jmethodID idToCall = env->GetStaticMethodID(clazzToCall, CALL_TARGET_M_S,
				                                            "(Ljava/lang/String;JLjava/lang/String;J)V");
				env->CallStaticVoidMethod(clazzToCall, idToCall,
				                          env->NewStringUTF(dli.dli_fname),
				                          reinterpret_cast<jlong>(dli.dli_fbase),
				                          env->NewStringUTF(dli.dli_sname),
				                          reinterpret_cast<jlong>(dli.dli_saddr)
				);
#endif
			}
		}
#else
		MEMORY_BASIC_INFORMATION mbi{};
		if (VirtualQuery(ptr, &mbi, sizeof(mbi))) {
			const auto hmod = static_cast<HMODULE>(mbi.AllocationBase);
			char real[256];
			if (!GetModuleFileNameA(hmod, real, 256)) return; // no filename
			if (!std::string(real).ends_with("\\jvm.dll")) {
				isSafe = false;
#if HOOK_ACTION == 0
				exit(0);
				std::terminate();
				*static_cast<char *>(nullptr) = 0; // intentional segfault
				return;
#elif HOOK_ACTION == 1
				jclass clazzToCall = env->FindClass(CALL_TARGET_S);
				jmethodID idToCall = env->GetStaticMethodID(clazzToCall, CALL_TARGET_M_S, "(Ljava/lang/String;JLjava/lang/String;J)V");
				env->CallStaticVoidMethod(clazzToCall, idToCall,
					env->NewStringUTF(real),
					reinterpret_cast<jlong>(mbi.AllocationBase),
					nullptr,
					0
					);
#endif
			}
		}
#endif
	});

	if (isSafe) {
		// we know this function table is ok, so we copy it, to prevent tampering later
		// we now have a list of safe pointers we can check against
		auto *copied = new JNINativeInterface_(*env->functions);
		// memcpy(copied, env->functions, sizeof(JNINativeInterface_));
		safeJNIItf = copied;
	}
}
