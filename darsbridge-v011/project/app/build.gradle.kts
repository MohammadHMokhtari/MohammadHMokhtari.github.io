plugins { id("com.android.application") }
val phoneAbi = providers.gradleProperty("phoneAbi").orNull
val releaseQa = providers.gradleProperty("releaseQa").orNull == "true"
android {
    namespace = "com.darsbridge.app"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        // This identity is permanent. 0.9 stays installed separately during one-time migration.
        applicationId = "com.darsbridge.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = if (releaseQa) providers.gradleProperty("upgradeVersionCode").orElse("11").get().toInt() else 11
        versionName = "0.11.0"
        ndk { if (phoneAbi != null) abiFilters.add(phoneAbi) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = if (releaseQa) "com.darsbridge.browser.upgradetest" else "com.darsbridge.browser.qa.test"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        // Disposable CI-only signing, never the production key; disabled for distribution builds.
        if (releaseQa) create("upgradeQa") {
            storeFile = file(providers.gradleProperty("ciKeyStore").get())
            storePassword = "android"
            keyAlias = "upgrade-qa"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".qa" }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (releaseQa) signingConfig = signingConfigs.getByName("upgradeQa")
        }
    }
    testBuildType = if (releaseQa) "release" else "debug"
    sourceSets.getByName("androidTest").java.setSrcDirs(
        listOf(if (releaseQa) "src/upgradeTest/java" else "src/androidTest/java")
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { animationsDisabled = true }
    lint { abortOnError = true }
}
dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.core:core:1.16.0")
    implementation("info.guardianproject:tor-android:0.4.9.14")
    implementation("com.netzarchitekten:IPtProxy:5.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
