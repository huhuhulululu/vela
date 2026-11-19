import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePath = System.getenv("VELA_KEYSTORE_PATH")
val keystorePassword = System.getenv("VELA_KEYSTORE_PASSWORD")
val keystoreAlias = System.getenv("VELA_KEYSTORE_ALIAS")
val keystoreKeyPassword = System.getenv("VELA_KEYSTORE_KEY_PASSWORD")
val keystoreValues = listOf(keystorePath, keystorePassword, keystoreAlias, keystoreKeyPassword)
val keystoreComplete = keystoreValues.all { !it.isNullOrEmpty() }
val keystorePartial = keystoreValues.any { !it.isNullOrEmpty() } && !keystoreComplete

android {
    namespace = "vela.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "vela.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val upstream = providers.gradleProperty("vela.upstream").orNull ?: ""
        require(!upstream.any { it == '"' || it == '\\' || it == '\n' || it == '\r' }) {
            "vela.upstream 含有不能写入 BuildConfig 的字符"
        }
        buildConfigField("String", "VELA_UPSTREAM", "\"$upstream\"")
    }

    signingConfigs {
        if (keystoreComplete) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        create("perf") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePartial) {
                error(
                    "VELA_KEYSTORE_PATH、VELA_KEYSTORE_PASSWORD、VELA_KEYSTORE_ALIAS、VELA_KEYSTORE_KEY_PASSWORD 必须同时设置",
                )
            }
            signingConfig = if (keystoreComplete) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    sourceSets {
        getByName("debug") {
            kotlin.directories.add("src/hooks/kotlin")
            res.directories.add("src/testnet/res")
            manifest.srcFile("src/testnet/AndroidManifest.xml")
        }
        getByName("perf") {
            kotlin.directories.add("src/hooks/kotlin")
            res.directories.add("src/testnet/res")
            manifest.srcFile("src/testnet/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // P1 播放管线实验按墙钟跑数分钟，只在 -Pspike 时执行；日常与 CI 不跑。
            if (!project.hasProperty("spike")) it.exclude("vela/app/spike/**")
            // 取消 HTTP 时要关掉 java.net 的底层 socket。这些包没有对测试模块打开。
            it.jvmArgs(
                "--add-opens", "java.base/sun.net.www.protocol.http=ALL-UNNAMED",
                "--add-opens", "java.base/sun.net.www.http=ALL-UNNAMED",
                "--add-opens", "java.base/sun.net=ALL-UNNAMED",
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.rules)
    testImplementation(libs.androidx.test.ext.junit)

    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.uiautomator)
}
