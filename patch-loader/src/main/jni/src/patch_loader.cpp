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

//
// Created by Nullptr on 2022/3/17.
//

#include "patch_loader.h"

#include "art/runtime/jit/profile_saver.h"
#include "art/runtime/oat_file_manager.h"
#include "elf_util.h"
#include "native_util.h"
#include "symbol_cache.h"
#include "utils/jni_helper.hpp"

using namespace lsplant;

namespace lspd {

    void PatchLoader::LoadDex(JNIEnv* env, Context::PreloadedDex&& dex) {
        auto class_activity_thread = JNI_FindClass(env, "android/app/ActivityThread");
        auto class_activity_thread_app_bind_data = JNI_FindClass(env, "android/app/ActivityThread$AppBindData");
        auto class_loaded_apk = JNI_FindClass(env, "android/app/LoadedApk");

        auto mid_current_activity_thread = JNI_GetStaticMethodID(env, class_activity_thread, "currentActivityThread", "()Landroid/app/ActivityThread;");
        auto mid_get_classloader = JNI_GetMethodID(env, class_loaded_apk, "getClassLoader", "()Ljava/lang/ClassLoader;");
        auto fid_m_bound_application = JNI_GetFieldID(env, class_activity_thread, "mBoundApplication", "Landroid/app/ActivityThread$AppBindData;");
        auto fid_info = JNI_GetFieldID(env, class_activity_thread_app_bind_data, "info", "Landroid/app/LoadedApk;");

        auto activity_thread = JNI_CallStaticObjectMethod(env, class_activity_thread, mid_current_activity_thread);
        auto m_bound_application = JNI_GetObjectField(env, activity_thread, fid_m_bound_application);
        auto info = JNI_GetObjectField(env, m_bound_application, fid_info);
        auto stub_classloader = JNI_CallObjectMethod(env, info, mid_get_classloader);

        if (!stub_classloader) {
            LOGE("getStubClassLoader failed!!!");
            return;
        }

        auto in_memory_classloader = JNI_FindClass(env, "dalvik/system/InMemoryDexClassLoader");
        auto mid_init = JNI_GetMethodID(env, in_memory_classloader, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        auto dex_buffer = env->NewDirectByteBuffer(dex.data(), dex.size());

        ScopedLocalRef<jobject> my_cl(JNI_NewObject(env, in_memory_classloader, mid_init, dex_buffer, stub_classloader));
        if (!my_cl) {
            LOGE("InMemoryDexClassLoader creation failed!!!");
            return;
        }

        inject_class_loader_ = JNI_NewGlobalRef(env, my_cl.get());
    }

    void PatchLoader::InitArtHooker(JNIEnv* env, const InitInfo& initInfo) {
        Context::InitArtHooker(env, initInfo);
        handler = initInfo;
        art::ProfileSaver::DisableInline(initInfo);
        art::FileManager::DisableBackgroundVerification(initInfo);
    }

    void PatchLoader::InitHooks(JNIEnv* env) {
        Context::InitHooks(env);
    }

    void PatchLoader::SetupEntryClass(JNIEnv* env) {
        ScopedLocalRef<jclass> entry_class(FindClassFromLoader(env, GetCurrentClassLoader(), "org.ytp.loader.Application"));
        if (entry_class) {
            entry_class_ = JNI_NewGlobalRef(env, entry_class.get());
        } else {
            LOGE("Failed to find entry class.");
        }
    }

    void PatchLoader::Load(JNIEnv* env) {
        lsplant::InitInfo initInfo{
                .inline_hooker =
                [](auto t, auto r) {
                    void* bk = nullptr;
                    return HookInline(t, r, &bk) == 0 ? bk : nullptr;
                },
                .inline_unhooker = [](auto t) { return UnhookInline(t) == 0; },
                .art_symbol_resolver = [](auto symbol) { return GetArt()->getSymbAddress(symbol); },
                .art_symbol_prefix_resolver =
                [](auto symbol) { return GetArt()->getSymbPrefixFirstAddress(symbol); },
        };

        auto stub = JNI_FindClass(env, "androidx/app/Init");
        auto dex_field = JNI_GetStaticFieldID(env, stub, "core", "[B");
        ScopedLocalRef<jbyteArray> array = JNI_GetStaticObjectField(env, stub, dex_field);

        if (!array) {
            LOGE("Failed to get dex byte array from stub.");
            return;
        }


        // 设置DEX头部: d(0x64), e(0x65), x(0x78)
        jbyte dex_header[8] = {0x64, 0x65, 0x78, 0xA, 0x30, 0x33, 0x39, 0x0};
        jint dex_header_size = sizeof(dex_header);

        //在array开头添加dex等字节
        jsize original_length = JNI_GetArrayLength(env, array.get());
        jbyteArray new_array = env->NewByteArray(original_length + dex_header_size);
        if (!new_array) {
            LOGE("Failed to create new byte array for dex header.");
            return;
        }

        env->SetByteArrayRegion(new_array, 0, dex_header_size, dex_header);

        // 复制原始数据
        jbyte* original_data = env->GetByteArrayElements(array.get(), nullptr);
        env->SetByteArrayRegion(new_array, dex_header_size, original_length, original_data);
        env->ReleaseByteArrayElements(array.get(), original_data, JNI_ABORT);

        auto dex = PreloadedDex{env->GetByteArrayElements(new_array, nullptr), static_cast<size_t>(JNI_GetArrayLength(env, new_array))};

        InitArtHooker(env, initInfo);
        LoadDex(env, std::move(dex));
        InitHooks(env);

        GetArt(true);

        SetupEntryClass(env);
        FindAndCall(env, "onLoad", "()V");
    }
}  // namespace lspd