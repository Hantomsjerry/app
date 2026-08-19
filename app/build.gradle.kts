plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val x86EmulatorBuild = providers.gradleProperty("x86EmulatorBuild")
    .map(String::toBoolean)
    .getOrElse(false)

android {
    namespace = "com.example.myapp"
    // 与本机安装的 NDK 固定版本保持一致，确保两套 C++ 推理库可重复构建。
    ndkVersion = "29.0.13113456"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 模型面向实际 Android 设备部署，仅打包 64 位 ARM native 库。
        ndk {
            abiFilters += if (x86EmulatorBuild) "x86_64" else "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_PLATFORM=android-29"
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    buildFeatures {
        // 整个界面使用 Jetpack Compose，不包含传统 XML layout。
        compose = true
    }
    androidResources {
        // 大模型必须保持原始字节，native 运行时会从私有目录按文件路径加载。
        noCompress += "gguf"
        noCompress += "bin"
        noCompress += "onnx"
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation("androidx.activity:activity-ktx:1.9.3")
}
