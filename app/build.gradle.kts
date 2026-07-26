plugins {
    alias(libs.plugins.android.application)
    // Kotlin-plugin не подключаем явно: AGP 9.x уже регистрирует kotlin-extension
    // автоматически (поэтому исходный scaffold с MainActivity.kt компилировался
    // без kotlin-android). При повторном применении плагина получаем
    // "Cannot add extension with name 'kotlin'".
}

// Имя выходного APK: WhereAmI-debug.apk / WhereAmI-release.apk
base { archivesName.set("WhereAmI") }

// Читаем креды release-keystore из env vars (в CI подаются через GitHub Secrets)
// или из локального .env в корне (для локальной сборки). Отсутствие любого поля
// означает «keystore не настроен» → падаем на debug-signingConfig.
val releaseStoreFile: File? = run {
    val fromEnv = System.getenv("RELEASE_KEYSTORE_PATH")
    val path = if (!fromEnv.isNullOrBlank()) fromEnv else {
        val dotenv = rootProject.file(".env")
        if (dotenv.exists()) {
            dotenv.readLines()
                .firstOrNull { it.startsWith("RELEASE_KEYSTORE_PATH=") }
                ?.substringAfter("=")?.trim()
        } else null
    }
    path?.let { rootProject.file(it) }?.takeIf { it.exists() }
}
val releaseCreds: Map<String, String>? = releaseStoreFile?.let {
    fun read(key: String): String? = System.getenv(key)
        ?: rootProject.file(".env").takeIf { f -> f.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.trim()
    val store = read("RELEASE_KEYSTORE_STORE_PASS")
    val alias = read("RELEASE_KEYSTORE_KEY_ALIAS")
    val key   = read("RELEASE_KEYSTORE_KEY_PASS")
    if (store != null && alias != null && key != null)
        mapOf("store" to store, "alias" to alias, "key" to key) else null
}

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
        versionCode = 24
        versionName = "5.1"

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

    signingConfigs {
        if (releaseStoreFile != null && releaseCreds != null) {
            create("release") {
                storeFile     = releaseStoreFile
                storePassword = releaseCreds["store"]
                keyAlias      = releaseCreds["alias"]
                keyPassword   = releaseCreds["key"]
            }
        }
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
            // Постоянный release-keystore если настроен, иначе debug (для эмуляторных
            // тестовых сборок). Только release-сборки подписанные жёстким keystore
            // могут OTA-обновляться поверх ранее установленной.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
    // Google Play Services убраны: Huawei/HarmonyOS без GMS показывает странное
    // поведение при наличии любых com.google.android.gms классов в APK.
    // Используем системный android.location.LocationManager — работает везде.
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
