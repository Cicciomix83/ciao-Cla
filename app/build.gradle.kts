plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "it.ciaocla.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "it.ciaocla.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }
    signingConfigs {
        create("fisso") {
            storeFile = file("ciaocla.keystore")
            storePassword = "ciaocla123"
            keyAlias = "ciaocla"
            keyPassword = "ciaocla123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fisso") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("fisso") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
