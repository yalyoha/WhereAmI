# WhereAmI — план работ (ANDROID-TODO)

> **Для агентов-исполнителей:** используйте `superpowers:executing-plans` или `superpowers:subagent-driven-development`, идите по задачам сверху вниз, отмечайте `- [x]` по мере выполнения. Каждая задача = отдельный коммит.

**Цель:** три улучшения клиента WhereAmI:
1. Автообновление через GitHub Releases (в облаке — только последний релиз, локально — только последний APK).
2. Проверка обновлений автоматическая — по каждому пятому апдейту локации.
3. Переключатель «Работать в фоновом режиме» с принудительным прохождением всех системных разрешений.

**Архитектура:** правки локальные — `UpdateManager` меняет источник манифеста на GitHub API; `LocationService` дёргает `UpdateWorker.checkNow()` по счётчику; `InfoFragment` получает Switch, привязанный к новому флагу `SettingsRepository.keepInBackground`, который триггерит `BackgroundReliabilityWalker` — визард по системным экранам Android (battery opt, autostart вендоров, background location). `BootReceiver` поднимает сервис после перезагрузки. Плюс — GitHub Actions workflow, чистящий старые релизы.

**Стек (без изменений):** Kotlin, Google Play Services Location 21+, OkHttp, WorkManager, EncryptedSharedPreferences, Material Components.

---

## Задача 1. Перевести UpdateManager на GitHub Releases API

**Файлы:**
- Изменить: `app/build.gradle.kts` (BuildConfig-поля)
- Изменить: `app/src/main/java/com/example/whereami/UpdateManager.kt` (парсинг GitHub JSON)
- Создать: `.github/workflows/release.yml` (публикация нового релиза + удаление старых)

### Договорённость о формате релиза (документируем в workflow)

- Тег релиза: `v<versionName>` (например `v3.1`) — источник versionName.
- В release body первой строкой: `versionCode=<int>` (например `versionCode=4`) — источник versionCode.
- В assets релиза ровно один `.apk` (например `WhereAmI-release.apk`) — источник URL.

Всё, что не подходит под конвенцию, — клиент игнорирует (считает «нет обновления»), в лог warning.

### Шаги

- [x] **1.1. Заменить `UPDATE_MANIFEST_URL` на GitHub API-адрес в `app/build.gradle.kts`.**

В блоке `defaultConfig` вместо строки
```kotlin
buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://whereami.alekseylosev.ru/update/version.json\"")
```
поставить (владелец проекта подставит `OWNER/REPO`):
```kotlin
buildConfigField(
    "String",
    "GITHUB_RELEASES_URL",
    "\"https://api.github.com/repos/OWNER/REPO/releases/latest\""
)
```

- [x] **1.2. Переписать `fetchManifest()` в `UpdateManager.kt` под ответ GitHub.**

Заменить тело метода:
```kotlin
private fun fetchManifest(): VersionManifest? = try {
    val req = Request.Builder()
        .url(BuildConfig.GITHUB_RELEASES_URL)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            Log.w(TAG, "GitHub releases HTTP ${resp.code}")
            return null
        }
        val json = JSONObject(resp.body?.string() ?: return null)
        val tag = json.getString("tag_name")                 // "v3.1"
        val versionName = tag.removePrefix("v")
        val body = json.optString("body", "")
        val versionCode = Regex("""versionCode\s*=\s*(\d+)""")
            .find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: run {
                Log.w(TAG, "no versionCode= in release body")
                return null
            }
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.getString("browser_download_url")
                break
            }
        }
        if (apkUrl == null) {
            Log.w(TAG, "no .apk asset in release")
            return null
        }
        VersionManifest(versionCode = versionCode, versionName = versionName, apkUrl = apkUrl)
    }
} catch (e: Throwable) {
    Log.w(TAG, "manifest fetch failed: ${e.message}")
    null
}
```
Никаких других правок в файле не нужно: сравнение с `installedVersionCode()`, скачивание, `deleteStaleCachedApk()` и уведомление уже работают правильно.

- [x] **1.3. Убедиться, что локально «только последний APK» уже выполняется.**

`deleteStaleCachedApk()` в `UpdateManager` (строка 137–141) удаляет закешированный `update/WhereAmI.apk`, если он не новее установленной версии. После успешной установки APK перестанет быть новее — при следующем `checkAndUpdate()` он удалится. Дополнительных правок не нужно, только упомянуть это в комментарии к методу для наглядности:

```kotlin
/**
 * Удаляет кешированный APK, если он не новее установленной версии.
 * Вызывается при каждой проверке — «только последний APK на диске» держится автоматически.
 */
private fun deleteStaleCachedApk() { … }
```

- [x] **1.4. Создать `.github/workflows/release.yml` с удалением старых релизов.**

Файл (владелец репозитория дополнит триггером сборки APK под свой пайплайн):

```yaml
name: Release
on:
  push:
    tags: ['v*']

permissions:
  contents: write

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }

      - name: Build release APK
        run: ./gradlew :app:assembleRelease

      - name: Extract versionCode
        id: v
        run: |
          code=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts | head -n1)
          echo "code=$code" >> "$GITHUB_OUTPUT"

      - name: Publish release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          gh release create "${GITHUB_REF_name}" \
            app/build/outputs/apk/release/WhereAmI-release.apk \
            --title "${GITHUB_REF_NAME}" \
            --notes "versionCode=${{ steps.v.outputs.code }}"

      - name: Delete old releases (keep only the latest)
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          gh release list --limit 100 --json tagName,createdAt \
            --jq 'sort_by(.createdAt) | reverse | .[1:] | .[].tagName' \
            | while read tag; do
                echo "delete $tag"
                gh release delete "$tag" --cleanup-tag --yes
              done
```

- [ ] **1.5. Ручная проверка (после того как владелец подставит `OWNER/REPO` и опубликует первый релиз).**

Собрать debug-APK, установить, дождаться проверки обновлений (см. задачу 2). В логе `UpdateWorker`/`UpdateManager` должно быть:
```
manifest fetch → up to date: installed=N server=N
```
Затем поднять `versionCode`, запушить тег `v<name>` → workflow создаст релиз, старые удалит. Клиент при следующей проверке увидит `update available: N+1 > N` и покажет нотификацию «Установить».

- [ ] **1.6. Коммит.**

```bash
git add app/build.gradle.kts app/src/main/java/com/example/whereami/UpdateManager.kt .github/workflows/release.yml
git commit -m "feat(update): switch update source to GitHub Releases, prune old releases in workflow"
```

---

## Задача 2. Проверять обновления каждым 5-м POST-локации (вместо периодического воркера)

**Файлы:**
- Изменить: `app/src/main/java/com/example/whereami/LocationService.kt` (счётчик + вызов `UpdateWorker.checkNow`)
- Изменить: `app/src/main/java/com/example/whereami/WhereamiApp.kt` (снять периодическую регистрацию)
- Изменить: `app/src/main/java/com/example/whereami/UpdateWorker.kt` (удалить `schedulePeriodic`, оставить `checkNow`)

### Шаги

- [x] **2.1. Добавить счётчик в `LocationService.kt`.**

Внутри класса `LocationService`, рядом с `private var fastMode: Boolean = false`:
```kotlin
// § ANDROID-TODO задача 2: проверяем обновления каждым 5-м апдейтом.
// Отсчёт от старта сервиса — переживёт логаут/старт-стоп циклы,
// т. к. сервис пересоздаётся вместе со счётчиком.
private var sendCounter: Int = 0
```

- [x] **2.2. Инкрементировать счётчик и триггерить проверку в `handleLocation`.**

В конце метода `handleLocation(loc: Location)` — сразу перед закрывающей скобкой — добавить:
```kotlin
sendCounter++
if (sendCounter % UPDATE_CHECK_EVERY_N == 0) {
    Log.d(TAG, "trigger update check on send #$sendCounter")
    UpdateWorker.checkNow(applicationContext)
}
```

В `companion object` добавить константу:
```kotlin
/** Проверять обновление каждый N-й POST-локации. § ANDROID-TODO 2. */
private const val UPDATE_CHECK_EVERY_N = 5
```

**Важно:** инкрементируем после `when (res)`, т. е. после любого исхода (200/thinned/network err) — считаем именно «попытки отправки», а не «успешные ответы». Это укладывается в формулировку задачи «каждый пятый запрос местоположения».

- [x] **2.3. Убрать периодический воркер из `WhereamiApp.kt`.**

Стереть строку `UpdateWorker.schedulePeriodic(this)`. Оставить только:
```kotlin
override fun onCreate() {
    super.onCreate()
    RetryWorker.schedulePeriodic(this)
    UpdateWorker.checkNow(this)     // one-shot при старте приложения
}
```

- [x] **2.4. Удалить `schedulePeriodic` из `UpdateWorker.kt`.**

Стереть метод `schedulePeriodic` и константу `PERIODIC_NAME` — их больше никто не зовёт. Оставить только `checkNow` и `NET`.

Дополнительно снести устаревший worker из очереди WorkManager разово при апгрейде — в `WhereamiApp.onCreate()` добавить:
```kotlin
// одноразовая уборка: снимаем старый периодический воркер, если он остался
// у уже установленных клиентов (заменили на счётчик в LocationService).
androidx.work.WorkManager.getInstance(this)
    .cancelUniqueWork("whereami_update_periodic")
```
(Через один-два релиза этот cleanup можно вырезать.)

- [ ] **2.5. Ручная проверка.**

Запустить сервис, в logcat отфильтровать `LocationService:V UpdateWorker:V`. Каждые 5 апдейтов локации в логе:
```
LocationService D: trigger update check on send #5
UpdateWorker I: checking for update
```

- [ ] **2.6. Коммит.**

```bash
git add app/src/main/java/com/example/whereami/LocationService.kt \
        app/src/main/java/com/example/whereami/WhereamiApp.kt \
        app/src/main/java/com/example/whereami/UpdateWorker.kt
git commit -m "feat(update): trigger update check every 5th location send instead of periodic worker"
```

---

## Задача 3. Переключатель «Работать в фоновом режиме»

Смысл: одна галка, которая честно проводит пользователя через все необходимые системные экраны и после этого гарантирует, что сервис не будет тихо убит ОС. Отдельно от «включён ли шаринг локации» (`sharingEnabled`).

**Файлы:**
- Изменить: `app/src/main/java/com/example/whereami/SettingsRepository.kt` (новый флаг `keepInBackground`)
- Создать: `app/src/main/java/com/example/whereami/BackgroundReliabilityWalker.kt` (визард по системным экранам)
- Создать: `app/src/main/java/com/example/whereami/BootReceiver.kt` (перезапуск после ребута)
- Изменить: `app/src/main/AndroidManifest.xml` (регистрация `BootReceiver`)
- Изменить: `app/src/main/res/layout/fragment_info.xml` (добавить SwitchMaterial)
- Изменить: `app/src/main/res/values/strings.xml` (тексты)
- Изменить: `app/src/main/java/com/example/whereami/InfoFragment.kt` (обработчик Switch)

### Шаги

- [x] **3.1. Добавить флаг `keepInBackground` в `SettingsRepository.kt`.**

Рядом с `batteryOptAsked`:
```kotlin
/** Пользователь явно включил режим «работать в фоне» и прошёл визард системных настроек. */
var keepInBackground: Boolean
    get() = prefs.getBoolean(KEY_KEEP_BG, false)
    set(v) = prefs.edit().putBoolean(KEY_KEEP_BG, v).apply()
```
И константу:
```kotlin
private const val KEY_KEEP_BG = "keep_in_background"
```

- [x] **3.2. Создать `BackgroundReliabilityWalker.kt`.**

Полный файл:
```kotlin
package com.example.whereami

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Визард «Работать в фоновом режиме»: гоняет пользователя по системным экранам,
 * которые у Android/вендоров нужны для того, чтобы foreground-сервис не убивали.
 *
 * Использование: walker.run(fragment, onDone). onDone вызывается, когда все шаги
 * пройдены (или пользователь отменил очередной).
 *
 * Шаги (по порядку):
 *   1. ACCESS_BACKGROUND_LOCATION — если ещё не выдано.
 *   2. Отключение battery optimization для пакета.
 *   3. Экран автозапуска у вендора (Xiaomi/Huawei/Oppo/Vivo/OnePlus/Samsung), best-effort.
 */
class BackgroundReliabilityWalker(private val context: Context) {

    fun nextRequiredIntent(): Intent? {
        if (!hasBackgroundLocation()) return null   // выдаётся через RequestPermission, не Intent
        if (!isBatteryOptIgnored()) return batteryOptIntent()
        vendorAutostartIntent()?.let { return it }
        return null
    }

    fun needsBackgroundLocationPermission(): Boolean = !hasBackgroundLocation()

    fun isBatteryOptIgnored(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun batteryOptIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * Каждый вендор прячет autostart в своём кастомном Activity.
     * Проверяем через PackageManager.resolveActivity — если такой Activity нет,
     * возвращаем null и пропускаем шаг.
     */
    fun vendorAutostartIntent(): Intent? {
        val candidates = listOf(
            // Xiaomi (MIUI)
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Huawei
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            // Oppo
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            // Vivo
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            // OnePlus / Realme
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            // Samsung
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        val pm = context.packageManager
        return candidates
            .firstOrNull { cn ->
                val i = Intent().setComponent(cn)
                pm.resolveActivity(i, 0) != null
            }
            ?.let { cn ->
                Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    companion object {
        const val BG_LOCATION_PERM: String = "android.permission.ACCESS_BACKGROUND_LOCATION"
    }
}
```

- [x] **3.3. Создать `BootReceiver.kt` — старт сервиса после перезагрузки, если `keepInBackground=true`.**

```kotlin
package com.example.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Поднимает LocationService после перезагрузки устройства, если пользователь
 * ранее включил «Работать в фоновом режиме» и уже сконфигурирован токен/slug.
 * Без флага не делаем ничего, чтобы не запускать сервис у тех, кто явно этого не просил.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val settings = SettingsRepository(context)
        if (!settings.keepInBackground) return
        if (!settings.isConfigured()) return
        Log.i(TAG, "BOOT_COMPLETED → restart LocationService")
        LocationService.start(context)
    }
    companion object { private const val TAG = "BootReceiver" }
}
```

- [x] **3.4. Зарегистрировать `BootReceiver` в `AndroidManifest.xml`.**

Внутри `<application>`, рядом с `<service .LocationService …/>`:
```xml
<receiver
    android:name=".BootReceiver"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.RECEIVE_BOOT_COMPLETED">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```
(`RECEIVE_BOOT_COMPLETED` уже задекларирован в манифесте, строка 22.)

- [x] **3.5. Добавить строки в `res/values/strings.xml`.**

```xml
<string name="keep_bg_switch">Работать в фоновом режиме</string>
<string name="keep_bg_hint">Гарантирует, что отправка координат не будет остановлена системой</string>
<string name="keep_bg_walkthrough_title">Настройка фона</string>
<string name="keep_bg_walkthrough_msg">Сейчас откроются системные настройки: разреши WhereAmI работать без ограничений энергосбережения и в автозапуске. Приложение вернётся сюда после каждого шага.</string>
<string name="keep_bg_need_bg_location">Нужно разрешение «Всегда» для локации</string>
```

- [x] **3.6. Добавить `SwitchMaterial` в `res/layout/fragment_info.xml`.**

Первым дочерним элементом (над MaterialButtonToggleGroup или выше — куда логичнее по вёрстке):
```xml
<com.google.android.material.materialswitch.MaterialSwitch
    android:id="@+id/info_keep_bg_switch"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginHorizontal="16dp"
    android:layout_marginTop="8dp"
    android:text="@string/keep_bg_switch" />
<TextView
    android:id="@+id/info_keep_bg_hint"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginHorizontal="16dp"
    android:layout_marginBottom="16dp"
    android:textAppearance="?attr/textAppearanceBodySmall"
    android:text="@string/keep_bg_hint" />
```

- [x] **3.7. Подключить Switch в `InfoFragment.kt`.**

В верхних импортах:
```kotlin
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.materialswitch.MaterialSwitch
```

Поля класса:
```kotlin
private lateinit var keepBgSwitch: MaterialSwitch
private lateinit var walker: BackgroundReliabilityWalker

private val bgLocationLauncher: ActivityResultLauncher<String> =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) advanceBackgroundWalk()
        else revertKeepBgSwitch()
    }

private val systemSettingsLauncher: ActivityResultLauncher<Intent> =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // возвращение с любого системного экрана — просто продолжаем визард
        advanceBackgroundWalk()
    }
```

В конец `onViewCreated`:
```kotlin
walker = BackgroundReliabilityWalker(requireContext())
keepBgSwitch = view.findViewById(R.id.info_keep_bg_switch)
keepBgSwitch.isChecked = settings.keepInBackground
keepBgSwitch.setOnCheckedChangeListener { _, isChecked ->
    if (isChecked) startBackgroundWalk()
    else settings.keepInBackground = false
}
```

Новые методы:
```kotlin
private fun startBackgroundWalk() {
    AlertDialog.Builder(requireContext())
        .setTitle(R.string.keep_bg_walkthrough_title)
        .setMessage(R.string.keep_bg_walkthrough_msg)
        .setPositiveButton(android.R.string.ok) { _, _ -> advanceBackgroundWalk() }
        .setNegativeButton(android.R.string.cancel) { _, _ -> revertKeepBgSwitch() }
        .show()
}

private fun advanceBackgroundWalk() {
    if (walker.needsBackgroundLocationPermission()) {
        bgLocationLauncher.launch(BackgroundReliabilityWalker.BG_LOCATION_PERM)
        return
    }
    val next = walker.nextRequiredIntent()
    if (next != null) {
        systemSettingsLauncher.launch(next)
        return
    }
    // визард пройден
    settings.keepInBackground = true
    if (settings.isConfigured()) LocationService.start(requireContext())
}

private fun revertKeepBgSwitch() {
    keepBgSwitch.setOnCheckedChangeListener(null)
    keepBgSwitch.isChecked = false
    settings.keepInBackground = false
    keepBgSwitch.setOnCheckedChangeListener { _, isChecked ->
        if (isChecked) startBackgroundWalk() else settings.keepInBackground = false
    }
}
```

- [ ] **3.8. Ручная проверка.**

1. Запустить приложение, зайти в таб «Инфо», включить Switch.
2. Должен появиться диалог, после «OK» — системный экран запроса background location (если ещё не выдано) → экран battery optimization → экран autostart (на не-Xiaomi обычно пропустится, будет `null`).
3. Возврат в приложение — Switch остался `ON`, сервис работает. Отключить Wi-Fi, свернуть приложение, подождать 15 минут — сервис не должен упасть.
4. Reboot устройства → через несколько секунд после разлочки в logcat: `BootReceiver: BOOT_COMPLETED → restart LocationService`.

- [ ] **3.9. Коммит.**

```bash
git add app/src/main/java/com/example/whereami/SettingsRepository.kt \
        app/src/main/java/com/example/whereami/BackgroundReliabilityWalker.kt \
        app/src/main/java/com/example/whereami/BootReceiver.kt \
        app/src/main/java/com/example/whereami/InfoFragment.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/res/layout/fragment_info.xml \
        app/src/main/res/values/strings.xml
git commit -m "feat(settings): 'keep in background' switch with system-settings walkthrough + BootReceiver"
```

---

## Финальный чек-лист

- [ ] Владелец подставил `OWNER/REPO` в `GITHUB_RELEASES_URL` и запушил первый релиз тегом `v<versionName>` с `versionCode=<int>` в теле.
- [ ] `.github/workflows/release.yml` при следующем релизе удалил старые релизы (в UI GitHub остался ровно один).
- [ ] На устройстве через 5 отправок локации в logcat триггерится проверка обновлений.
- [ ] Свежий release-APK докачивается в кеш и предлагается через нотификацию/Snackbar.
- [ ] Включение Switch «Работать в фоновом режиме» проводит пользователя через background-location → battery-opt → autostart и после этого сервис переживает 15+ минут в свёрнутом виде без Wi-Fi.
- [ ] `BootReceiver` поднимает сервис после reboot только при `keepInBackground=true`.

---

## Дальнейшие планы (после стабилизации задач 1–3)

- [ ] **Перевести проверку обновлений на «раз в сутки» вместо каждых 5 отправок локации.**

  Мотивация: 5 отправок при быстрой езде = ~1 минута, при стоянке — ~5 минут. Дёргать GitHub API так часто нет смысла и упирается в rate limit (60 req/hour анонимно на IP). Правильный периодический режим — раз в сутки.

  Правила: делаем только **после** того, как задачи 1–3 обкатаны в бою и правки/фиксы после них накатаны. Тогда:
  1. В `LocationService.kt` удалить блок с `sendCounter` и константу `UPDATE_CHECK_EVERY_N`.
  2. В `UpdateWorker.kt` вернуть `schedulePeriodic`, но с интервалом `1, TimeUnit.DAYS` (было 6 часов).
  3. В `WhereamiApp.kt` восстановить вызов `UpdateWorker.schedulePeriodic(this)` рядом с `checkNow(this)`.
  4. Оставить `checkNow` при старте приложения — это даёт мгновенную проверку, если пользователь давно не открывал.

  Коммит: `refactor(update): switch from per-send counter to daily periodic check`.
