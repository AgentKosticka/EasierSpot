plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.agentkosticka.easierspot.systemapistubs"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
