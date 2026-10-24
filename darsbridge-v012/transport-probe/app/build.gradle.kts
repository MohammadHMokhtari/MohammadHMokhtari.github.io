plugins { id("com.android.application") }
android {
    namespace = "com.darsbridge.probe"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        // Isolated CI fixture; never distributed as a browser update.
        applicationId = "com.darsbridge.transportprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.12-probe"
        testApplicationId = "com.darsbridge.transportprobe.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}
dependencies {
    implementation("com.netzarchitekten:IPtProxy:5.7.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
