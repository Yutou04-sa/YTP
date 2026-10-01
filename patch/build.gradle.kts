val androidSourceCompatibility: JavaVersion by rootProject.extra
val androidTargetCompatibility: JavaVersion by rootProject.extra

plugins {
    id("java-library")
}

java {
    sourceCompatibility = androidSourceCompatibility
    targetCompatibility = androidTargetCompatibility
    sourceSets {
        main {
            java.srcDirs("libs/manifest-editor/lib/src/main/java")
            resources.srcDirs("libs/manifest-editor/lib/src/main")
        }
    }
}

dependencies {
    implementation(projects.external.axml)
    implementation(projects.share.java)

    implementation(lspatch.commons.io)
    implementation(lspatch.beust.jcommander)
    implementation(lspatch.google.gson)
    implementation(lspatch.dexlib2)
    implementation(lspatch.apkzlib)
    api(lspatch.guava)

    // 单元测试：沿用上游 patch 模块的 JUnit 4 写法，用 `gradlew :patch:test` 跑。
    testImplementation("junit:junit:4.13.2")
}