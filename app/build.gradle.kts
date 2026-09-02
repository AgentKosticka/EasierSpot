import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}

fun versionInt(key: String): Int = versionProperties.getProperty(key)
    ?.toIntOrNull()
    ?: error("Missing or invalid $key in version.properties")

fun commitsSince(baseCommit: String): Int {
    System.getenv("EASIERSPOT_VERSION_OFFSET")?.toIntOrNull()?.let {
        return it.coerceAtLeast(0)
    }
    return runCatching {
        val process = ProcessBuilder("git", "rev-list", "--count", "$baseCommit..HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { output }
        output.toInt()
    }.getOrDefault(0)
}

val versionMajor = versionInt("version.major")
val versionMinor = versionInt("version.minor")
val basePatch = versionInt("version.basePatch")
val baseVersionCode = versionInt("version.baseCode")
val versionOffset = commitsSince(
    versionProperties.getProperty("version.baseCommit")
        ?: error("Missing version.baseCommit in version.properties")
)
val calculatedVersionCode = baseVersionCode + versionOffset
val calculatedVersionName = "$versionMajor.$versionMinor.${basePatch + versionOffset}"

android {
    namespace = "com.agentkosticka.easierspot"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.agentkosticka.easierspot"
        minSdk = 31
        targetSdk = 36
        versionCode = calculatedVersionCode
        versionName = calculatedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    buildFeatures {
        aidl = true
        buildConfig = true
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

tasks.register("printVersion") {
    group = "help"
    description = "Prints the commit-derived application version."
    doLast {
        println("v$calculatedVersionName (versionCode $calculatedVersionCode)")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.hiddenapibypass)
    
    // System API stubs for Shizuku AIDL access
    compileOnly(project(":system-api-stubs"))
    
    // HiddenApiRefine for type-safe hidden API access
    implementation(libs.runtime)
    compileOnly(libs.annotation)
    
    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    
    // Preferences
    implementation(libs.androidx.preference.ktx)
    
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
