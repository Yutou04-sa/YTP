plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "org.ytp.share"

    buildFeatures {
        androidResources = false
        buildConfig = false
    }
}

dependencies {
    implementation(projects.services.daemonService)
    implementation(projects.share.java)
}
