plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val appVersion = (findProperty("appVersion") as String?) ?: "1.0"
require(Regex("""\d+\.\d""").matches(appVersion))
val versionNumber = (findProperty("appVersionCode") as String?)?.toIntOrNull()
    ?: appVersion.split(".").let { it[0].toInt() * 10 + it[1].toInt() }
android {
    namespace = "dev.n2k02.mcplogger"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.n2k02.mcplogger"
        minSdk = 26
        targetSdk = 35
        versionCode = versionNumber
        versionName = appVersion + ((findProperty("appVersionSuffix") as String?) ?: "")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
dependencies {
    implementation("dev.n2k02:theme:0.1.4")
    implementation("dev.n2k02:updater:0.1.19")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.7.0")
    testImplementation("junit:junit:4.13.2")
}
val verifySharedLibraries = tasks.register("verifySharedLibraries") {
    doLast {
        val found = configurations.getByName("implementation").dependencies
            .filter { it.group == "dev.n2k02" }
            .associate { it.name to it.version }
        check(found["theme"] == "0.1.4" && found["updater"] == "0.1.19") {
            "Shared N2K02 theme and updater must be pinned to approved versions"
        }
    }
}
tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
    dependsOn(verifySharedLibraries)
}
