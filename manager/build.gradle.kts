import java.util.Properties

val defaultManagerPackageName: String by rootProject.extra
val apiCode: Int by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra
val coreVerCode: Int by rootProject.extra
val coreVerName: String by rootProject.extra

// 签名配置
// 只有本地存在 signing.properties 且密钥库文件存在时才启用自定义签名。
// 这样从仓库克隆出来的工作区（不含任何签名物料）也能直接构建：
// release 不签名（产出 *-unsigned.apk），debug 回退到 AGP 自带的 debug 密钥。
// 参见 manager/signing/signing.properties.example。
val signingProps = Properties().apply {
    val propsFile = rootProject.file("manager/signing/signing.properties")
    if (propsFile.exists()) {
        load(propsFile.inputStream())
    }
}
val releaseStoreFile: File? =
    signingProps.getProperty("KEYSTORE_FILE")?.let { rootProject.file(it) }
        ?: rootProject.file("manager/signing/ytp-signing.jks").takeIf { it.exists() }
val releaseSigningAvailable: Boolean = releaseStoreFile != null &&
    signingProps.getProperty("KEYSTORE_PASSWORD") != null &&
    signingProps.getProperty("KEY_ALIAS") != null &&
    signingProps.getProperty("KEY_PASSWORD") != null

plugins {
    alias(libs.plugins.agp.app)
    alias(lspatch.plugins.compose.compiler)
    alias(lspatch.plugins.google.devtools.ksp)
    alias(lspatch.plugins.rikka.tools.refine)
    alias(lspatch.plugins.kotlin.android)
    id("kotlin-parcelize")
}

android {
    defaultConfig {
        applicationId = defaultManagerPackageName
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = signingProps.getProperty("KEY_ALIAS")
                keyPassword = signingProps.getProperty("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    androidResources {
        noCompress.add(".so")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (releaseSigningAvailable) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = if (releaseSigningAvailable) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        all {
            sourceSets[name].assets.srcDirs(rootProject.projectDir.resolve("out/assets/$name"))
            // 修补提示音的素材不进仓库（见 .gitignore）：本地存在才当资源目录，
            // 缺了也不影响构建，只是提示音库里没有这些内置素材。
            val localSounds = project.projectDir.resolve("sounds")
            if (localSounds.isDirectory) {
                sourceSets[name].assets.srcDir(localSounds)
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.13"
    }

    namespace = "org.ytp"

    applicationVariants.all {
        kotlin.sourceSets {
            getByName(name) {
                kotlin.srcDir("build/generated/ksp/$name/kotlin")
            }
        }
    }
}

afterEvaluate {
    android.applicationVariants.forEach { variant ->
        val variantLowered = variant.name.lowercase()
        val variantCapped = variant.name.replaceFirstChar { it.uppercase() }

        task<Copy>("copy${variantCapped}Assets") {
            dependsOn(":meta-loader:copy$variantCapped")
            dependsOn(":patch-loader:copy$variantCapped")
            tasks["merge${variantCapped}Assets"].dependsOn(this)

            into("$buildDir/intermediates/assets/$variantLowered/merge${variantCapped}Assets")
            from("${rootProject.projectDir}/out/assets/${variant.name}")
        }

        task<Copy>("build$variantCapped") {
            dependsOn(tasks["assemble$variantCapped"])
            from(variant.outputs.map { it.outputFile })
            into("${rootProject.projectDir}/out/$variantLowered")
            rename(".*.apk", "YTP-v$verName-$variantLowered.apk")
        }
    }
}

dependencies {
    implementation(libs.androidx.foundation)
    implementation(projects.patch)
    implementation(projects.services.daemonService)
    implementation(projects.share.android)
    implementation(projects.share.java)
    implementation(platform(lspatch.androidx.compose.bom))

    annotationProcessor(lspatch.androidx.room.compiler)
    compileOnly(lspatch.rikka.hidden.stub)
    debugImplementation(lspatch.androidx.compose.ui.tooling)
    debugImplementation(lspatch.androidx.customview)
    debugImplementation(lspatch.androidx.customview.poolingcontainer)
    implementation(lspatch.androidx.activity.compose)
    implementation(lspatch.androidx.compose.material.icons.extended)
    implementation(lspatch.androidx.compose.material3)
    implementation(lspatch.androidx.compose.ui)
    implementation(lspatch.androidx.compose.ui.tooling.preview)
    implementation(lspatch.androidx.core.ktx)
    implementation(lspatch.androidx.lifecycle.viewmodel.compose)
    implementation(lspatch.androidx.navigation.compose)
    implementation(libs.androidx.preference)
    implementation(lspatch.androidx.room.ktx)
    implementation(lspatch.androidx.room.runtime)
    implementation(lspatch.google.accompanist.navigation.animation)
    implementation(lspatch.google.accompanist.pager)
    implementation(lspatch.google.accompanist.swiperefresh)
    implementation(libs.material)
    implementation(libs.gson)
    implementation(lspatch.rikka.refine)
    implementation(lspatch.raamcosta.compose.destinations)
    implementation(libs.appiconloader)
    implementation(libs.hiddenapibypass)
    implementation(lspatch.apkzlib)
    implementation(projects.external.axml)
    ksp(lspatch.androidx.room.compiler)
    ksp(lspatch.raamcosta.compose.destinations.ksp)
}
