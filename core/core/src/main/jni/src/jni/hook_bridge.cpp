/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2022 LSPosed Contributors
 */

#include "hook_bridge.h"
#include "native_util.h"
#include "lsplant.hpp"
#include <parallel_hashmap/phmap.h>
#include <atomic>
#include <memory>
#include <shared_mutex>
#include <mutex>
#include <set>
#include <vector>

using namespace lsplant;

namespace {
struct ModuleCallback {
    jobject hooker_callback; // HookerCallback instance (GlobalRef)
};

struct HookItem {
    std::multimap<jint, jobject, std::greater<>> legacy_callbacks;
    std::multimap<jint, ModuleCallback, std::greater<>> modern_callbacks;
private:
    std::atomic<jobject> backup {nullptr};
    static_assert(decltype(backup)::is_always_lock_free);
    inline static jobject FAILED = reinterpret_cast<jobject>(std::numeric_limits<uintptr_t>::max());
public:
    jobject GetBackup() {
        backup.wait(nullptr, std::memory_order::acquire);
        if (auto bk = backup.load(std::memory_order_relaxed); bk != FAILED) {
            return bk;
        } else {
            return nullptr;
        }
    }
    void SetBackup(jobject newBackup) {
        jobject null = nullptr;
        backup.compare_exchange_strong(null, newBackup ? newBackup : FAILED,
                                       std::memory_order_acq_rel, std::memory_order_relaxed);
        backup.notify_all();
    }
    // hook 失败后 item 会带着 FAILED 留在 hooked_methods 里，这里把它重置回“未尝试”，
    // 使同一次运行内还能重新 hook 这个方法（否则该方法在本进程内永远 hook 不上）。
    bool TryResetFailed() {
        jobject failed = FAILED;
        return backup.compare_exchange_strong(failed, nullptr,
                                              std::memory_order_acq_rel,
                                              std::memory_order_relaxed);
    }
};

template <class K, class V, class Hash = phmap::priv::hash_default_hash<K>,
        class Eq = phmap::priv::hash_default_eq<K>,
        class Alloc = phmap::priv::Allocator<phmap::priv::Pair<const K, V>>, size_t N = 4>
using SharedHashMap = phmap::parallel_flat_hash_map<K, V, Hash, Eq, Alloc, N, std::shared_mutex>;

SharedHashMap<jmethodID, std::unique_ptr<HookItem>> hooked_methods;

jmethodID invoke = nullptr;
jmethodID get_parameter_types = nullptr;
jmethodID get_declaring_class = nullptr;

struct BoxedPrimitiveMethods {
    jclass boxed_class = nullptr;
    jclass primitive_class = nullptr;
    jmethodID unbox = nullptr;
    jmethodID box = nullptr;
};

BoxedPrimitiveMethods boxed_int;
BoxedPrimitiveMethods boxed_double;
BoxedPrimitiveMethods boxed_long;
BoxedPrimitiveMethods boxed_float;
BoxedPrimitiveMethods boxed_short;
BoxedPrimitiveMethods boxed_byte;
BoxedPrimitiveMethods boxed_char;
BoxedPrimitiveMethods boxed_boolean;

jclass invocation_target_exception = nullptr;
jmethodID invocation_target_exception_ctor = nullptr;
std::once_flag invocation_support_once;
std::atomic_bool invocation_support_ready = false;

jclass object_class = nullptr;
jclass object_array_class = nullptr;
std::once_flag callback_classes_once;

jfieldID class_loader_path_list = nullptr;
jfieldID path_list_dex_elements = nullptr;
jfieldID element_dex_file = nullptr;
jfieldID dex_file_cookie = nullptr;
std::once_flag trusted_dex_fields_once;
std::atomic_bool trusted_dex_fields_ready = false;

void EnsureCallbackClasses(JNIEnv *env) {
    std::call_once(callback_classes_once, [env] {
        auto local_object_class = env->FindClass("java/lang/Object");
        object_class = static_cast<jclass>(env->NewGlobalRef(local_object_class));
        env->DeleteLocalRef(local_object_class);

        auto local_object_array_class = env->FindClass("[Ljava/lang/Object;");
        object_array_class = static_cast<jclass>(env->NewGlobalRef(local_object_array_class));
        env->DeleteLocalRef(local_object_array_class);
    });
}

void EnsureTrustedDexFields(JNIEnv *env) {
    if (trusted_dex_fields_ready.load(std::memory_order_acquire)) return;
    std::call_once(trusted_dex_fields_once, [env] {
        auto base_dex_class_loader = env->FindClass("dalvik/system/BaseDexClassLoader");
        auto dex_path_list = env->FindClass("dalvik/system/DexPathList");
        auto element = env->FindClass("dalvik/system/DexPathList$Element");
        auto dex_file = env->FindClass("dalvik/system/DexFile");
        if (base_dex_class_loader && dex_path_list && element && dex_file) {
            class_loader_path_list = env->GetFieldID(
                    base_dex_class_loader, "pathList", "Ldalvik/system/DexPathList;");
            path_list_dex_elements = env->GetFieldID(
                    dex_path_list, "dexElements", "[Ldalvik/system/DexPathList$Element;");
            element_dex_file = env->GetFieldID(
                    element, "dexFile", "Ldalvik/system/DexFile;");
            dex_file_cookie = env->GetFieldID(
                    dex_file, "mCookie", "Ljava/lang/Object;");
        }
        if (base_dex_class_loader) env->DeleteLocalRef(base_dex_class_loader);
        if (dex_path_list) env->DeleteLocalRef(dex_path_list);
        if (element) env->DeleteLocalRef(element);
        if (dex_file) env->DeleteLocalRef(dex_file);
        if (env->ExceptionCheck()) env->ExceptionClear();
        trusted_dex_fields_ready.store(
                class_loader_path_list && path_list_dex_elements
                        && element_dex_file && dex_file_cookie,
                std::memory_order_release);
    });
}

void EnsureInvocationSupport(JNIEnv *env) {
    if (invocation_support_ready.load(std::memory_order_acquire)) return;
    std::call_once(invocation_support_once, [env] {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        get_parameter_types = env->GetMethodID(
                executable, "getParameterTypes", "()[Ljava/lang/Class;");
        get_declaring_class = env->GetMethodID(
                executable, "getDeclaringClass", "()Ljava/lang/Class;");
        env->DeleteLocalRef(executable);

        auto initialize_primitive = [env](BoxedPrimitiveMethods &methods,
                                          const char *class_name,
                                          const char *unbox_name,
                                          const char *unbox_signature,
                                          const char *box_signature) {
            auto local_class = env->FindClass(class_name);
            methods.boxed_class = static_cast<jclass>(env->NewGlobalRef(local_class));
            methods.unbox = env->GetMethodID(local_class, unbox_name, unbox_signature);
            methods.box = env->GetStaticMethodID(local_class, "valueOf", box_signature);
            auto type_field = env->GetStaticFieldID(
                    local_class, "TYPE", "Ljava/lang/Class;");
            auto primitive_class = env->GetStaticObjectField(local_class, type_field);
            methods.primitive_class = static_cast<jclass>(env->NewGlobalRef(primitive_class));
            env->DeleteLocalRef(primitive_class);
            env->DeleteLocalRef(local_class);
        };
        initialize_primitive(boxed_int, "java/lang/Integer", "intValue", "()I",
                             "(I)Ljava/lang/Integer;");
        initialize_primitive(boxed_double, "java/lang/Double", "doubleValue", "()D",
                             "(D)Ljava/lang/Double;");
        initialize_primitive(boxed_long, "java/lang/Long", "longValue", "()J",
                             "(J)Ljava/lang/Long;");
        initialize_primitive(boxed_float, "java/lang/Float", "floatValue", "()F",
                             "(F)Ljava/lang/Float;");
        initialize_primitive(boxed_short, "java/lang/Short", "shortValue", "()S",
                             "(S)Ljava/lang/Short;");
        initialize_primitive(boxed_byte, "java/lang/Byte", "byteValue", "()B",
                             "(B)Ljava/lang/Byte;");
        initialize_primitive(boxed_char, "java/lang/Character", "charValue", "()C",
                             "(C)Ljava/lang/Character;");
        initialize_primitive(boxed_boolean, "java/lang/Boolean", "booleanValue", "()Z",
                             "(Z)Ljava/lang/Boolean;");

        auto local_invocation_target_exception =
                env->FindClass("java/lang/reflect/InvocationTargetException");
        invocation_target_exception = static_cast<jclass>(
                env->NewGlobalRef(local_invocation_target_exception));
        invocation_target_exception_ctor = env->GetMethodID(
                local_invocation_target_exception, "<init>", "(Ljava/lang/Throwable;)V");
        env->DeleteLocalRef(local_invocation_target_exception);
        invocation_support_ready.store(true, std::memory_order_release);
    });
}

void ThrowIllegalArgument(JNIEnv *env, const char *message) {
    auto exception = env->FindClass("java/lang/IllegalArgumentException");
    if (exception) {
        env->ThrowNew(exception, message);
        env->DeleteLocalRef(exception);
    }
}

bool IsBoxed(JNIEnv *env, jobject value, const BoxedPrimitiveMethods &type) {
    return value && env->IsInstanceOf(value, type.boxed_class);
}

bool UnboxArgument(JNIEnv *env, jobject value, jchar target_type, jvalue &result) {
    switch (target_type) {
        case 'I':
            if (IsBoxed(env, value, boxed_int)) {
                result.i = env->CallIntMethod(value, boxed_int.unbox);
            } else if (IsBoxed(env, value, boxed_char)) {
                result.i = env->CallCharMethod(value, boxed_char.unbox);
            } else if (IsBoxed(env, value, boxed_short)) {
                result.i = env->CallShortMethod(value, boxed_short.unbox);
            } else if (IsBoxed(env, value, boxed_byte)) {
                result.i = env->CallByteMethod(value, boxed_byte.unbox);
            } else {
                return false;
            }
            break;
        case 'D':
            if (IsBoxed(env, value, boxed_double)) {
                result.d = env->CallDoubleMethod(value, boxed_double.unbox);
            } else if (IsBoxed(env, value, boxed_float)) {
                result.d = env->CallFloatMethod(value, boxed_float.unbox);
            } else if (IsBoxed(env, value, boxed_long)) {
                result.d = static_cast<jdouble>(env->CallLongMethod(value, boxed_long.unbox));
            } else if (IsBoxed(env, value, boxed_int)) {
                result.d = env->CallIntMethod(value, boxed_int.unbox);
            } else if (IsBoxed(env, value, boxed_char)) {
                result.d = env->CallCharMethod(value, boxed_char.unbox);
            } else if (IsBoxed(env, value, boxed_short)) {
                result.d = env->CallShortMethod(value, boxed_short.unbox);
            } else if (IsBoxed(env, value, boxed_byte)) {
                result.d = env->CallByteMethod(value, boxed_byte.unbox);
            } else {
                return false;
            }
            break;
        case 'J':
            if (IsBoxed(env, value, boxed_long)) {
                result.j = env->CallLongMethod(value, boxed_long.unbox);
            } else if (IsBoxed(env, value, boxed_int)) {
                result.j = env->CallIntMethod(value, boxed_int.unbox);
            } else if (IsBoxed(env, value, boxed_char)) {
                result.j = env->CallCharMethod(value, boxed_char.unbox);
            } else if (IsBoxed(env, value, boxed_short)) {
                result.j = env->CallShortMethod(value, boxed_short.unbox);
            } else if (IsBoxed(env, value, boxed_byte)) {
                result.j = env->CallByteMethod(value, boxed_byte.unbox);
            } else {
                return false;
            }
            break;
        case 'F':
            if (IsBoxed(env, value, boxed_float)) {
                result.f = env->CallFloatMethod(value, boxed_float.unbox);
            } else if (IsBoxed(env, value, boxed_long)) {
                result.f = static_cast<jfloat>(env->CallLongMethod(value, boxed_long.unbox));
            } else if (IsBoxed(env, value, boxed_int)) {
                result.f = env->CallIntMethod(value, boxed_int.unbox);
            } else if (IsBoxed(env, value, boxed_char)) {
                result.f = env->CallCharMethod(value, boxed_char.unbox);
            } else if (IsBoxed(env, value, boxed_short)) {
                result.f = env->CallShortMethod(value, boxed_short.unbox);
            } else if (IsBoxed(env, value, boxed_byte)) {
                result.f = env->CallByteMethod(value, boxed_byte.unbox);
            } else {
                return false;
            }
            break;
        case 'S':
            if (IsBoxed(env, value, boxed_short)) {
                result.s = env->CallShortMethod(value, boxed_short.unbox);
            } else if (IsBoxed(env, value, boxed_byte)) {
                result.s = env->CallByteMethod(value, boxed_byte.unbox);
            } else {
                return false;
            }
            break;
        case 'B':
            if (!IsBoxed(env, value, boxed_byte)) return false;
            result.b = env->CallByteMethod(value, boxed_byte.unbox);
            break;
        case 'C':
            if (!IsBoxed(env, value, boxed_char)) return false;
            result.c = env->CallCharMethod(value, boxed_char.unbox);
            break;
        case 'Z':
            if (!IsBoxed(env, value, boxed_boolean)) return false;
            result.z = env->CallBooleanMethod(value, boxed_boolean.unbox);
            break;
        default:
            return false;
    }
    return !env->ExceptionCheck();
}

void WrapInvocationTargetException(JNIEnv *env) {
    auto cause = env->ExceptionOccurred();
    if (!cause) return;
    env->ExceptionClear();
    auto wrapper = env->NewObject(invocation_target_exception,
                                  invocation_target_exception_ctor, cause);
    env->DeleteLocalRef(cause);
    if (wrapper && !env->ExceptionCheck()) {
        env->Throw(static_cast<jthrowable>(wrapper));
        env->DeleteLocalRef(wrapper);
    }
}

jobject InvokeSpecial(JNIEnv *env, jmethodID target, const jchar *shorty, jsize shorty_len,
                      jclass cls, jobject thiz, jobjectArray args) {
    EnsureInvocationSupport(env);
    if (env->ExceptionCheck()) return nullptr;
    if (shorty_len < 1) {
        ThrowIllegalArgument(env, "shorty is empty");
        return nullptr;
    }
    const auto param_len = shorty_len - 1;
    const auto args_len = args ? env->GetArrayLength(args) : 0;
    if (args_len != param_len) {
        ThrowIllegalArgument(env, "args.length != parameters.length");
        return nullptr;
    }
    if (!thiz) {
        auto exception = env->FindClass("java/lang/NullPointerException");
        if (exception) {
            env->ThrowNew(exception, "this == null");
            env->DeleteLocalRef(exception);
        }
        return nullptr;
    }
    if (!env->IsInstanceOf(thiz, cls)) {
        ThrowIllegalArgument(env, "this is not an instance of the declaring class");
        return nullptr;
    }

    std::vector<jvalue> call_args(static_cast<size_t>(param_len));
    for (jsize i = 0; i < param_len; ++i) {
        auto element = env->GetObjectArrayElement(args, i);
        if (env->ExceptionCheck()) return nullptr;
        if (shorty[i + 1] == 'L') {
            call_args[i].l = element;
            continue;
        }
        const bool converted = UnboxArgument(env, element, shorty[i + 1], call_args[i]);
        if (element) env->DeleteLocalRef(element);
        if (!converted) {
            if (!env->ExceptionCheck()) {
                ThrowIllegalArgument(env, "argument type mismatch");
            }
            return nullptr;
        }
    }

    jvalue result{};
    switch (shorty[0]) {
        case 'I': result.i = env->CallNonvirtualIntMethodA(thiz, cls, target, call_args.data()); break;
        case 'D': result.d = env->CallNonvirtualDoubleMethodA(thiz, cls, target, call_args.data()); break;
        case 'J': result.j = env->CallNonvirtualLongMethodA(thiz, cls, target, call_args.data()); break;
        case 'F': result.f = env->CallNonvirtualFloatMethodA(thiz, cls, target, call_args.data()); break;
        case 'S': result.s = env->CallNonvirtualShortMethodA(thiz, cls, target, call_args.data()); break;
        case 'B': result.b = env->CallNonvirtualByteMethodA(thiz, cls, target, call_args.data()); break;
        case 'C': result.c = env->CallNonvirtualCharMethodA(thiz, cls, target, call_args.data()); break;
        case 'Z': result.z = env->CallNonvirtualBooleanMethodA(thiz, cls, target, call_args.data()); break;
        case 'L': result.l = env->CallNonvirtualObjectMethodA(thiz, cls, target, call_args.data()); break;
        case 'V': env->CallNonvirtualVoidMethodA(thiz, cls, target, call_args.data()); break;
        default:
            ThrowIllegalArgument(env, "invalid return type in shorty");
            return nullptr;
    }
    if (env->ExceptionCheck()) {
        WrapInvocationTargetException(env);
        return nullptr;
    }

    switch (shorty[0]) {
        case 'I': return env->CallStaticObjectMethod(boxed_int.boxed_class, boxed_int.box, result.i);
        case 'D': return env->CallStaticObjectMethod(boxed_double.boxed_class, boxed_double.box, result.d);
        case 'J': return env->CallStaticObjectMethod(boxed_long.boxed_class, boxed_long.box, result.j);
        case 'F': return env->CallStaticObjectMethod(boxed_float.boxed_class, boxed_float.box, result.f);
        case 'S': return env->CallStaticObjectMethod(boxed_short.boxed_class, boxed_short.box, result.s);
        case 'B': return env->CallStaticObjectMethod(boxed_byte.boxed_class, boxed_byte.box, result.b);
        case 'C': return env->CallStaticObjectMethod(boxed_char.boxed_class, boxed_char.box, result.c);
        case 'Z': return env->CallStaticObjectMethod(boxed_boolean.boxed_class, boxed_boolean.box, result.z);
        case 'L': return result.l;
        case 'V': return nullptr;
        default: return nullptr;
    }
}

jchar GetTypeShorty(JNIEnv *env, jobject type) {
    if (env->IsSameObject(type, boxed_int.primitive_class)) return 'I';
    if (env->IsSameObject(type, boxed_double.primitive_class)) return 'D';
    if (env->IsSameObject(type, boxed_long.primitive_class)) return 'J';
    if (env->IsSameObject(type, boxed_float.primitive_class)) return 'F';
    if (env->IsSameObject(type, boxed_short.primitive_class)) return 'S';
    if (env->IsSameObject(type, boxed_byte.primitive_class)) return 'B';
    if (env->IsSameObject(type, boxed_char.primitive_class)) return 'C';
    if (env->IsSameObject(type, boxed_boolean.primitive_class)) return 'Z';
    return 'L';
}
}

namespace lspd {
LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, hookMethod, jboolean useModernApi, jobject hookMethod,
                      jclass hooker, jint priority, jobject callback) {
    bool newHook = false;
#ifndef NDEBUG
    struct finally {
        std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
        bool &newHook;
        ~finally() {
            auto finish = std::chrono::steady_clock::now();
            if (newHook) {
                LOGV("New hook took {}us",
                     std::chrono::duration_cast<std::chrono::microseconds>(finish - start).count());
            }
        }
    } finally {
        .newHook = newHook
    };
#endif
    auto target = env->FromReflectedMethod(hookMethod);
    HookItem * hook_item = nullptr;
    hooked_methods.lazy_emplace_l(target, [&hook_item](auto &it) {
        hook_item = it.second.get();
    }, [&hook_item, &target, &newHook](const auto &ctor) {
        auto ptr = std::make_unique<HookItem>();
        hook_item = ptr.get();
        ctor(target, std::move(ptr));
        newHook = true;
    });
    if (!newHook && !hook_item->GetBackup() && hook_item->TryResetFailed()) {
        // 之前失败过：把 FAILED 标记重置后重试，否则本进程内这个方法永远 hook 不上。
        LOGW("Retrying a hook that previously failed");
        newHook = true;
    }
    if (newHook) {
        EnsureCallbackClasses(env);
        auto init = env->GetMethodID(hooker, "<init>", "(Ljava/lang/reflect/Executable;)V");
        auto callback_method = env->ToReflectedMethod(hooker, env->GetMethodID(hooker, "callback",
                                                                               "([Ljava/lang/Object;)Ljava/lang/Object;"),
                                                      false);
        jobject hooker_object = nullptr;
        jobject new_backup = nullptr;
        if (!env->ExceptionCheck() && init && callback_method) {
            hooker_object = env->NewObject(hooker, init, hookMethod);
        }
        if (env->ExceptionCheck()) {
            // 原来这里既不检查也不清异常，异常会一路传回 Java；且 item 被标成 FAILED 后
            // 永久留在 hooked_methods 里，该方法在本进程内再也 hook 不上。
            LOGE("Failed to create the hooker object for the target method");
            env->ExceptionClear();
        } else if (hooker_object) {
            new_backup = lsplant::Hook(env, hookMethod, hooker_object, callback_method);
            if (env->ExceptionCheck()) {
                LOGE("lsplant::Hook threw an exception");
                env->ExceptionClear();
                new_backup = nullptr;
            }
        }
        if (hooker_object) env->DeleteLocalRef(hooker_object);
        hook_item->SetBackup(new_backup);
        if (!new_backup) return JNI_FALSE;
    }
    jobject backup = hook_item->GetBackup();
    if (!backup) return JNI_FALSE;
    JNIMonitor monitor(env, backup);
    auto callback_ref = env->NewGlobalRef(callback);
    if (!callback_ref) return JNI_FALSE;
    if (useModernApi) {
        hook_item->modern_callbacks.emplace(priority, ModuleCallback{
                .hooker_callback = callback_ref,
        });
    } else {
        hook_item->legacy_callbacks.emplace(priority, callback_ref);
    }
    return JNI_TRUE;
}

LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, unhookMethod, jboolean useModernApi, jobject hookMethod, jobject callback) {
    auto target = env->FromReflectedMethod(hookMethod);
    HookItem * hook_item = nullptr;
    hooked_methods.if_contains(target, [&hook_item](const auto &it) {
        hook_item = it.second.get();
    });
    if (!hook_item) return JNI_FALSE;
    jobject backup = hook_item->GetBackup();
    if (!backup) return JNI_FALSE;
    JNIMonitor monitor(env, backup);
    if (useModernApi) {
        for (auto i = hook_item->modern_callbacks.begin(); i != hook_item->modern_callbacks.end(); ++i) {
            if (env->IsSameObject(i->second.hooker_callback, callback)) {
                env->DeleteGlobalRef(i->second.hooker_callback);
                hook_item->modern_callbacks.erase(i);
                return JNI_TRUE;
            }
        }
    } else {
        for (auto i = hook_item->legacy_callbacks.begin(); i != hook_item->legacy_callbacks.end(); ++i) {
            if (env->IsSameObject(i->second, callback)) {
                env->DeleteGlobalRef(i->second);
                hook_item->legacy_callbacks.erase(i);
                return JNI_TRUE;
            }
        }
    }
    return JNI_FALSE;
}

LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, deoptimizeMethod, jobject hookMethod,
                      jclass hooker, jint priority, jobject callback) {
    return lsplant::Deoptimize(env, hookMethod);
}

LSP_DEF_NATIVE_METHOD(jobject, HookBridge, invokeOriginalMethod, jobject hookMethod,
                      jobject thiz, jobjectArray args) {
    auto target = env->FromReflectedMethod(hookMethod);
    HookItem * hook_item = nullptr;
    hooked_methods.if_contains(target, [&hook_item](const auto &it) {
        hook_item = it.second.get();
    });
    auto original = hookMethod;
    if (hook_item) {
        if (auto backup = hook_item->GetBackup()) {
            original = backup;
        }
    }
    return env->CallObjectMethod(original, invoke, thiz, args);
}

LSP_DEF_NATIVE_METHOD(jobject, HookBridge, invokeOriginalConstructor, jobject constructor,
                      jobject thiz, jobjectArray args) {
    auto target = env->FromReflectedMethod(constructor);
    HookItem *hook_item = nullptr;
    hooked_methods.if_contains(target, [&hook_item](const auto &it) {
        hook_item = it.second.get();
    });
    if (hook_item) {
        if (auto backup = hook_item->GetBackup()) {
            return env->CallObjectMethod(backup, invoke, thiz, args);
        }
    }

    EnsureInvocationSupport(env);
    if (env->ExceptionCheck()) return nullptr;
    auto declaring_class = static_cast<jclass>(
            env->CallObjectMethod(constructor, get_declaring_class));
    if (env->ExceptionCheck()) return nullptr;
    auto parameter_types = static_cast<jobjectArray>(
            env->CallObjectMethod(constructor, get_parameter_types));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(declaring_class);
        return nullptr;
    }

    const auto parameter_count = env->GetArrayLength(parameter_types);
    std::vector<jchar> shorty(static_cast<size_t>(parameter_count) + 1, 'L');
    shorty[0] = 'V';
    for (jsize i = 0; i < parameter_count; ++i) {
        auto parameter_type = env->GetObjectArrayElement(parameter_types, i);
        shorty[static_cast<size_t>(i) + 1] = GetTypeShorty(env, parameter_type);
        env->DeleteLocalRef(parameter_type);
    }
    env->DeleteLocalRef(parameter_types);

    auto result = InvokeSpecial(env, target, shorty.data(),
                                static_cast<jsize>(shorty.size()), declaring_class, thiz, args);
    env->DeleteLocalRef(declaring_class);
    return result;
}

LSP_DEF_NATIVE_METHOD(jobject, HookBridge, allocateObject, jclass cls) {
    return env->AllocObject(cls);
}

LSP_DEF_NATIVE_METHOD(jobject, HookBridge, invokeSpecialMethod, jobject method, jcharArray shorty,
                      jclass cls, jobject thiz, jobjectArray args) {
    auto target = env->FromReflectedMethod(method);
    HookItem *hook_item = nullptr;
    hooked_methods.if_contains(target, [&hook_item](const auto &it) {
        hook_item = it.second.get();
    });
    if (hook_item) {
        if (auto backup = hook_item->GetBackup()) {
            return env->CallObjectMethod(backup, invoke, thiz, args);
        }
    }

    if (!shorty) {
        ThrowIllegalArgument(env, "shorty == null");
        return nullptr;
    }
    const auto shorty_len = env->GetArrayLength(shorty);
    auto *shorty_char = env->GetCharArrayElements(shorty, nullptr);
    if (!shorty_char) return nullptr;
    auto value = InvokeSpecial(env, target, shorty_char, shorty_len, cls, thiz, args);
    env->ReleaseCharArrayElements(shorty, shorty_char, JNI_ABORT);
    return value;
}

LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, instanceOf, jobject object, jclass expected_class) {
    return env->IsInstanceOf(object, expected_class);
}

LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, setTrusted, jobject cookie) {
    return lsplant::MakeDexFileTrusted(env, cookie);
}

LSP_DEF_NATIVE_METHOD(jboolean, HookBridge, setTrustedClassLoader, jobject class_loader) {
    if (!class_loader) return JNI_FALSE;
    EnsureTrustedDexFields(env);
    if (!trusted_dex_fields_ready.load(std::memory_order_acquire)) return JNI_FALSE;

    auto path_list = JNI_GetObjectField(env, class_loader, class_loader_path_list);
    if (!path_list) return JNI_FALSE;
    auto elements = JNI_Cast<jobjectArray>(
            JNI_GetObjectField(env, path_list, path_list_dex_elements));
    if (!elements) return JNI_FALSE;

    bool trusted = true;
    for (const auto &element: elements) {
        if (!element.get()) continue;
        auto dex_file = JNI_GetObjectField(env, element, element_dex_file);
        if (!dex_file) continue;
        auto cookie = JNI_GetObjectField(env, dex_file, dex_file_cookie);
        if (cookie && !lsplant::MakeDexFileTrusted(env, cookie.get())) trusted = false;
    }
    return trusted ? JNI_TRUE : JNI_FALSE;
}

LSP_DEF_NATIVE_METHOD(jobjectArray, HookBridge, callbackSnapshot, jclass callback_class, jobject method) {
    auto target = env->FromReflectedMethod(method);
    HookItem *hook_item = nullptr;
    hooked_methods.if_contains(target, [&hook_item](const auto &it) {
        hook_item = it.second.get();
    });
    if (!hook_item) return nullptr;
    jobject backup = hook_item->GetBackup();
    if (!backup) return nullptr;
    JNIMonitor monitor(env, backup);
    (void)callback_class;

    auto res = env->NewObjectArray(2, object_array_class, nullptr);
    auto modern = env->NewObjectArray(
            static_cast<jsize>(hook_item->modern_callbacks.size()), object_class, nullptr);
    auto legacy = env->NewObjectArray(
            static_cast<jsize>(hook_item->legacy_callbacks.size()), object_class, nullptr);
    for (jsize i = 0; const auto &callback: hook_item->modern_callbacks) {
        env->SetObjectArrayElement(modern, i++, callback.second.hooker_callback);
    }
    for (jsize i = 0; const auto &callback: hook_item->legacy_callbacks) {
        env->SetObjectArrayElement(legacy, i++, callback.second);
    }
    env->SetObjectArrayElement(res, 0, modern);
    env->SetObjectArrayElement(res, 1, legacy);
    env->DeleteLocalRef(modern);
    env->DeleteLocalRef(legacy);
    return res;
}

LSP_DEF_NATIVE_METHOD(jobject, HookBridge, getStaticInitializer, jclass target_class) {
    // <clinit> is the internal name for a static initializer.
    // Its signature is always ()V (no arguments, void return).
    jmethodID mid = env->GetStaticMethodID(target_class, "<clinit>", "()V");
    if (!mid) {
        // If GetStaticMethodID fails, it throws an exception.
        // We clear it and return null to let the Java side handle it gracefully.
        env->ExceptionClear();
        return nullptr;
    }
    // Convert the method ID to a java.lang.reflect.Method object.
    // The last parameter must be JNI_TRUE because it's a static method.
    return env->ToReflectedMethod(target_class, mid, JNI_TRUE);
}

static JNINativeMethod gMethods[] = {
    LSP_NATIVE_METHOD(HookBridge, hookMethod, "(ZLjava/lang/reflect/Executable;Ljava/lang/Class;ILjava/lang/Object;)Z"),
    LSP_NATIVE_METHOD(HookBridge, unhookMethod, "(ZLjava/lang/reflect/Executable;Ljava/lang/Object;)Z"),
    LSP_NATIVE_METHOD(HookBridge, deoptimizeMethod, "(Ljava/lang/reflect/Executable;)Z"),
    LSP_NATIVE_METHOD(HookBridge, invokeOriginalMethod, "(Ljava/lang/reflect/Executable;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
    LSP_NATIVE_METHOD(HookBridge, invokeOriginalConstructor, "(Ljava/lang/reflect/Constructor;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
    LSP_NATIVE_METHOD(HookBridge, invokeSpecialMethod, "(Ljava/lang/reflect/Executable;[CLjava/lang/Class;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
    LSP_NATIVE_METHOD(HookBridge, allocateObject, "(Ljava/lang/Class;)Ljava/lang/Object;"),
    LSP_NATIVE_METHOD(HookBridge, instanceOf, "(Ljava/lang/Object;Ljava/lang/Class;)Z"),
    LSP_NATIVE_METHOD(HookBridge, setTrusted, "(Ljava/lang/Object;)Z"),
    LSP_NATIVE_METHOD(HookBridge, setTrustedClassLoader, "(Ljava/lang/ClassLoader;)Z"),
    LSP_NATIVE_METHOD(HookBridge, callbackSnapshot, "(Ljava/lang/Class;Ljava/lang/reflect/Executable;)[[Ljava/lang/Object;"),
    LSP_NATIVE_METHOD(HookBridge, getStaticInitializer, "(Ljava/lang/Class;)Ljava/lang/reflect/Method;"),
};

void RegisterHookBridge(JNIEnv *env) {
    jclass method = env->FindClass("java/lang/reflect/Method");
    invoke = env->GetMethodID(
            method, "invoke",
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(method);
    REGISTER_LSP_NATIVE_METHODS(HookBridge);
}
} // namespace lspd
