plugins { id("com.android.application") }
android {
    namespace = "com.darsbridge.app"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        // Separate ID preserves the user's working 0.2 install and its saved pages.
        applicationId = "com.darsbridge.app.v04"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
