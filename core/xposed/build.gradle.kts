plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktfmt)
}

ktfmt { kotlinLangStyle() }

android {
    namespace = "org.lsposed.xposed"

    buildFeatures { androidResources { enable = false } }
}

dependencies {
    api(libs.libxposedApi)
    compileOnly(libs.androidx.annotation)
    lintPublish(libs.libxposedLint)
}
