plugins {
    alias(libs.plugins.agp.app)
}

android {
    defaultConfig {
        multiDexEnabled = false
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }
    namespace = "org.ytp.loader"
}

androidComponents.onVariants { variant ->
    val variantCapped = variant.name.replaceFirstChar { it.uppercase() }

    val modifyDexTask = tasks.register("modifyDex$variantCapped") {
        dependsOn("assemble$variantCapped")

        doLast {
            val dexFile = layout.buildDirectory.file("intermediates/dex/${variant.name}/mergeDex$variantCapped/classes.dex").get().asFile
            //备份一个在当前文件夹
            val backupFile = File(dexFile.parentFile, "classes_bak.dex")
            if (backupFile.exists()) {
                backupFile.delete()
            }
            if (dexFile.exists()) {
                dexFile.copyTo(backupFile)
            }

            if (dexFile.exists()) {
                // 读取原始dex文件
                val dexBytes = dexFile.readBytes()

                // 检查文件是否以"dex"开头
                if (dexBytes.size >= 3 &&
                    dexBytes[0].toInt().toChar() == 'd' &&
                    dexBytes[1].toInt().toChar() == 'e' &&
                    dexBytes[2].toInt().toChar() == 'x') {
                    // 去掉前8个字节
                    val modifiedBytes = dexBytes.drop(8).toByteArray()
                    dexFile.writeBytes(modifiedBytes)
                    println("Modified dex file: removed 'dex' header, size: ${modifiedBytes.size} bytes")
                }
            }
        }
    }

    val copyDexTask = tasks.register<Copy>("copyDex$variantCapped") {
        dependsOn(modifyDexTask)
        from(layout.buildDirectory.file("intermediates/dex/${variant.name}/mergeDex$variantCapped/classes.dex"))
        rename("classes.dex", "core.so")
        into("${rootProject.projectDir}/out/assets/${variant.name}/ytp")
    }

    // Sync, not Copy: the destination is re-created on every run, so libraries for an ABI that is not
    // part of this build (a narrowed -PytpAbis run, or a stale lib from an older build) can never leak
    // into the APK through out/assets.
    val copySoTask = tasks.register<Sync>("copySo$variantCapped") {
        dependsOn("assemble$variantCapped")
        dependsOn("strip${variantCapped}DebugSymbols")
        val libDir = variant.name + "/strip${variantCapped}DebugSymbols"
        from(
            fileTree(
                "dir" to layout.buildDirectory.dir("intermediates/stripped_native_libs/${variant.name}/strip${variantCapped}DebugSymbols/out/lib"),
                "include" to listOf("**/libytp.so")
            )
        )
        into("${rootProject.projectDir}/out/assets/${variant.name}/ytp/so")
    }

    tasks.register("copy$variantCapped") {
        dependsOn(copySoTask)
        dependsOn(copyDexTask)

        doLast {
            println("Dex and so files has been copied to ${rootProject.projectDir}${File.separator}out")
        }
    }
}

dependencies {
    compileOnly(projects.hiddenapi.stubs)
    implementation(projects.core)
    implementation(projects.external.apache)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.daemonService)
    implementation(projects.share.android)
    implementation(projects.share.java)

    implementation(libs.gson)
}