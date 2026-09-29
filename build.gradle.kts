import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension

plugins {
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    alias(lspatch.plugins.compose.compiler) apply false
    alias(lspatch.plugins.kotlin.android) apply false
}

// sync from https://github.com/JingMartix/LSPosed/blob/master/build.gradle.kts
val defaultManagerPackageName by extra("org.ytp")
val apiCode by extra(102)
// 版本号只在一处定义：gradle/version.properties（独立构建 core 时也读同一个文件）。
val versionProps = java.util.Properties().apply {
    file("gradle/version.properties").inputStream().use { load(it) }
}
val verCode by extra(versionProps.getProperty("verCode").trim().toInt())
val verName by extra(versionProps.getProperty("verName").trim())
// 内嵌的框架（core）随管理器一起发布，因此与管理器使用同一套版本号。
val coreVerCode by extra(verCode)
val coreVerName by extra(verName)
val androidMinSdkVersion by extra(28)
val androidTargetSdkVersion by extra(36)
val androidCompileSdkVersion by extra(37)
val androidCompileNdkVersion by extra("29.0.13113456")
val androidBuildToolsVersion by extra("37.0.0")
val androidSourceCompatibility by extra(JavaVersion.VERSION_21)
val androidTargetCompatibility by extra(JavaVersion.VERSION_21)

// NOTE: clang splits "-Wl,--thinlto-cache-dir=<path>" at spaces, so keep the thinlto cache
// outside the project directory (and outside any path that contains spaces).
val ltoCachePath = File(gradle.gradleUserHomeDir, "caches/ytp-lto-cache").absolutePath.replace('\\', '/')

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

listOf("Debug", "Release").forEach { variant ->
    tasks.register("build$variant") {
        description = "Build the YTP manager app ($variant)"
        dependsOn(tasks.findByPath(":manager:build$variant") ?: "manager:build$variant")
    }
}

tasks.register("buildAll") {
    dependsOn("buildDebug", "buildRelease")
}

tasks.register("build2Release") {
    dependsOn( "buildRelease")
}

fun Project.configureBaseExtension() {
    extensions.findByType(BaseExtension::class)?.run {
        // 说明：旧的 Int 重载会把 37 映射成 "android-37"，而安装了小版本平台的 SDK 实际注册名是
        // "platforms;android-37.0"，所以这里传完整的平台名字符串。
        compileSdkVersion("android-37.0")
        ndkVersion = androidCompileNdkVersion
        buildToolsVersion = androidBuildToolsVersion

        externalNativeBuild.cmake {
            version = "3.29.8+"
            buildStagingDirectory = layout.buildDirectory.get().asFile
        }

        defaultConfig {
            minSdk = androidMinSdkVersion
            targetSdk = androidTargetSdkVersion
            versionCode = verCode
            versionName = verName

            signingConfigs.create("config") {
                val androidStoreFile = project.findProperty("androidStoreFile") as String?
                if (!androidStoreFile.isNullOrEmpty()) {
                    storeFile = rootProject.file(androidStoreFile)
                    storePassword = project.property("androidStorePassword") as String
                    keyAlias = project.property("androidKeyAlias") as String
                    keyPassword = project.property("androidKeyPassword") as String
                }
            }

            externalNativeBuild {
                cmake {
                    arguments += "-DEXTERNAL_ROOT=${File(rootDir.absolutePath, "core/external")}"
                    arguments += "-DCORE_ROOT=${File(rootDir.absolutePath,
                        "core/core/src/main/jni")}"
                    // 框架支持的全部 ABI 默认都会构建，因为打补丁时会把这些库拷进目标应用。
                    // 只想给测试设备构建时传 -PytpAbis=arm64-v8a（多种用逗号分隔）：原生构建
                    // 耗时随 ABI 数量增长。
                    val abis = (this@configureBaseExtension.findProperty("ytpAbis") as String?)
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    abiFilters(*abis.toTypedArray())
                    val flags = arrayOf(
                        "-Wall",
                        "-Qunused-arguments",
                        "-Wno-gnu-string-literal-operator-template",
                        "-fno-rtti",
                        "-fvisibility=hidden",
                        "-fvisibility-inlines-hidden",
                        "-fno-exceptions",
                        "-fno-stack-protector",
                        "-fomit-frame-pointer",
                        "-Wno-builtin-macro-redefined",
                        "-Wno-unused-value",
                        "-D__FILE__=__FILE_NAME__",
                    )
                    cppFlags("-std=c++20", *flags)
                    cFlags("-std=c18", *flags)
                    arguments(
                        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                        "-DVERSION_CODE=$verCode",
                        "-DVERSION_NAME=$verName",
                    )
                }
            }
        }

        compileOptions {
            targetCompatibility(androidTargetCompatibility)
            sourceCompatibility(androidSourceCompatibility)
        }

        buildTypes {
            all {
                signingConfig = if (signingConfigs["config"].storeFile != null) signingConfigs["config"] else signingConfigs["debug"]
            }
            named("debug") {
                externalNativeBuild {
                    cmake {
                        arguments.addAll(
                            arrayOf(
                                "-DCMAKE_CXX_FLAGS_DEBUG=-Og",
                                "-DCMAKE_C_FLAGS_DEBUG=-Og",
                            )
                        )
                    }
                }
            }
            named("release") {
                externalNativeBuild {
                    cmake {
                        val flags = arrayOf(
                            "-Wl,--exclude-libs,ALL",
                            "-ffunction-sections",
                            "-fdata-sections",
                            "-Wl,--gc-sections",
                            "-fno-unwind-tables",
                            "-fno-asynchronous-unwind-tables",
                            "-flto=thin",
                            "-Wl,--thinlto-cache-policy,cache_size_bytes=300m",
                            "-Wl,--thinlto-cache-dir=$ltoCachePath",
                        )
                        cppFlags.addAll(flags)
                        cFlags.addAll(flags)
                        val configFlags = arrayOf(
                            "-Oz",
                            "-DNDEBUG"
                        ).joinToString(" ")
                        arguments.addAll(
                            arrayOf(
                                "-DCMAKE_CXX_FLAGS_RELEASE=$configFlags",
                                "-DCMAKE_CXX_FLAGS_RELWITHDEBINFO=$configFlags",
                                "-DCMAKE_C_FLAGS_RELEASE=$configFlags",
                                "-DCMAKE_C_FLAGS_RELWITHDEBINFO=$configFlags",
                                "-DDEBUG_SYMBOLS_PATH=${layout.buildDirectory.get().asFile.absolutePath}/symbols",
                            )
                        )
                    }
                }
            }
        }
    }

    extensions.findByType(ApplicationExtension::class)?.lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    extensions.findByType(ApplicationAndroidComponentsExtension::class)?.let { androidComponents ->
        tasks.register("optimizeReleaseRes") {
            doLast {
                val aapt2 = File(
                    androidComponents.sdkComponents.sdkDirectory.get().asFile,
                    "build-tools/${androidBuildToolsVersion}/aapt2"
                )
                val zip = java.nio.file.Paths.get(
                    layout.buildDirectory.get().asFile.path,
                    "intermediates",
                    "optimized_processed_res",
                    "release",
                    "optimizeReleaseResources",
                    "resources-release-optimize.ap_"
                )
                val optimized = File("${zip}.opt")
                val process = ProcessBuilder(
                    aapt2.absolutePath, "optimize",
                    "--collapse-resource-names",
                    "--enable-sparse-encoding",
                    "-o", optimized.absolutePath,
                    zip.toAbsolutePath().toString()
                ).inheritIO().start()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    delete(zip)
                    optimized.renameTo(zip.toFile())
                }
            }
        }

        tasks.configureEach {
            if (name == "optimizeReleaseResources") {
                finalizedBy("optimizeReleaseRes")
            }
        }
    }
}

subprojects {
    plugins.withId("com.android.application") {
        configureBaseExtension()
    }
    plugins.withId("com.android.library") {
        configureBaseExtension()
    }
}


project(":core") {
    afterEvaluate {
        if (property("android") is LibraryExtension) {
            val android = property("android") as LibraryExtension
            android.run {
                buildTypes {
                    release { proguardFiles(rootProject.file("share/lspatch-rules.pro")) }
                }

                defaultConfig {
                    // 覆盖或添加新的 buildConfigField
                    buildConfigField("String", "FRAMEWORK_NAME", """"LSPosed"""")
                    buildConfigField("String", "VERSION_NAME", """"${coreVerName}"""")
                    buildConfigField("long", "VERSION_CODE", """${coreVerCode}""")
                }
            }
        }
    }
}
