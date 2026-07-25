plugins {
    alias(libs.plugins.android.application)
    // Kotlin-plugin не подключаем явно: AGP 9.x уже регистрирует kotlin-extension
    // автоматически (поэтому исходный scaffold с MainActivity.kt компилировался
    // без kotlin-android). При повторном применении плагина получаем
    // "Cannot add extension with name 'kotlin'".
}

// Имя выходного APK: WhereAmI-debug.apk / WhereAmI-release.apk
base { archivesName.set("WhereAmI") }

android {
    namespace = "com.example.whereami"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.whereami"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "3.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Базовый URL API — слэш в конце обязателен. Можно переопределить в Settings.
        buildConfigField("String", "API_BASE_URL", "\"https://whereami.alekseylosev.ru/api/\"")
        // GitHub Releases API — источник манифеста автообновления.
        buildConfigField(
            "String",
            "GITHUB_RELEASES_URL",
            "\"https://api.github.com/repos/yalyoha/WhereAmI/releases/latest\""
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Lint vital падает на Windows из-за file-lock'ов в кешах. Сборке не нужен.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        release {
            // R8 shrinking + resource shrinking — даёт минимальный APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Для личной установки подписываем debug-ключом (Play Store не нужен).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // kotlinOptions { jvmTarget = "11" } — оставляем дефолт от AGP.
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
