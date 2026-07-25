# WhereAmI — Android-клиент

Семейный pet-проект: три телефона (муж / жена / мама) шлют свои GPS-координаты
на бэкенд `https://whereami.alekseylosev.ru` и видят друг друга на карте.

Веб-морда и сервер живут в отдельном репо; здесь — только Android-клиент.
Контракт API, тестовый план и последовательность задач — в
[`ANDROID-TASK.md`](ANDROID-TASK.md), [`ANDROID-TEST.md`](ANDROID-TEST.md),
[`ANDROID-TODO.md`](ANDROID-TODO.md).

## Стек

- **Kotlin**, Android `minSdk=29`, `targetSdk=36`, AGP 9.x, Gradle KTS.
- **Foreground Service** (`foregroundServiceType="location"`) + **Google Play
  Services Location 21+** (`FusedLocationProviderClient`) — фоновая отправка.
- **OkHttp 4+** — HTTP-стек, Bearer-токен в `Authorization`.
- **WorkManager** — периодический разгреб offline-очереди (`RetryWorker`)
  и одноразовая проверка обновлений (`UpdateWorker`).
- **EncryptedSharedPreferences** (`androidx.security.crypto`) — хранение
  токена и slug'а.
- **Material Components** — `BottomNavigationView`, `MaterialSwitch`,
  диалоги, темы Material 3.
- **Yandex Maps JS API** внутри `WebView` (`assets/map.html`,
  `assets/path_map.html`) — карта участников + собственный трек.
- **GitHub Releases API** — источник манифеста автообновления
  (`UpdateManager` тянет `releases/latest`, `.apk` берётся из assets,
  `versionCode` — из body релиза).

## Что делает каждый файл

```
app/src/main/java/com/example/whereami/
├── WhereamiApp.kt                 — Application: RetryWorker.schedulePeriodic, UpdateWorker.checkNow при старте
├── AuthActivity.kt                — экран авторизации (token + slug)
├── MainActivity.kt                — host для 4 фрагментов + BottomNavigation, permission flow
├── MapFragment.kt                 — WebView с Я-картой, JS-bridge, список участников через GET /api/me/users
├── PathFragment.kt                — мой трек за период
├── InfoFragment.kt                — статистика + Switch «Работать в фоновом режиме»
├── LocationService.kt             — foreground-сервис, адаптивный SLOW/FAST, POST /api/location; каждый 5-й send → UpdateWorker.checkNow
├── RetryWorker.kt                 — WorkManager: разгребает offline-очередь
├── UpdateManager.kt               — проверяет GitHub Releases, качает APK, показывает нотификацию/Snackbar «Установить»
├── UpdateWorker.kt                — one-shot обёртка для UpdateManager
├── UploadQueue.kt                 — JSONL-файл с накопленными точками
├── ApiClient.kt                   — OkHttp + Bearer, все ручки API
├── SettingsRepository.kt          — EncryptedSharedPreferences (token/slug/sharingEnabled/keepInBackground)
├── BackgroundReliabilityWalker.kt — визард системных настроек (background-location → battery opt → autostart вендоров)
├── BootReceiver.kt                — поднимает LocationService после reboot, если keepInBackground=true
├── Haversine.kt                   — расстояние между двумя координатами
└── Models.kt                      — LocationUpload, PartnerLast, TrackResult, …
```

## Ключевая логика

### Адаптивная частота отправки (`LocationService.kt`)

- **SLOW** — пешеход/стоянка: интервал 60 с, `BALANCED_POWER_ACCURACY`.
- **FAST** — велосипед/авто (скорость ≥ 3 м/с): интервал 15 с, `HIGH_ACCURACY`.
- Переключение автоматическое в каждом `onLocationResult`, с гистерезисом
  (выход из FAST только ниже 1.5 м/с) — чтобы не дёргать подписку на
  каждом светофоре.

### Автообновление (`UpdateManager.kt` + `.github/workflows/release.yml`)

- Тег `v<versionName>` в GitHub-репо → workflow собирает APK,
  публикует релиз с `versionCode=N` в теле, удаляет все прошлые релизы.
- Клиент бьёт `GET https://api.github.com/repos/OWNER/REPO/releases/latest`
  каждый 5-й `POST /api/location` (+ один раз при старте приложения).
  Если `versionCode` больше установленного — качает `.apk`-asset в
  `externalCacheDir/update/`, показывает нотификацию и Snackbar
  «Обновление готово · Установить».
- Локально на диске держится только последний APK — при следующей
  проверке кеш старой версии затирается.

### «Вошёл / вышел»

- На запуске приложения: если token+slug сохранены — сразу `MainActivity`,
  сервис стартует, маркер появляется на карте у всех.
- Кнопка «Выйти» в тапе на нижнюю навигацию → confirm-dialog → останавливает
  сервис, чистит настройки, дёргает `POST /api/logout`, возвращает на
  `AuthActivity`. У остальных маркер тускнеет (opacity 0.45) в следующее
  обновление карты (≤12 с) и остаётся на последней точке с пометкой
  «X мин назад».

### «Работать в фоновом режиме» (`InfoFragment` → `BackgroundReliabilityWalker`)

Один Switch, гоняющий пользователя по всем системным экранам, где Android
или вендор могут прибить фоновый сервис:

1. `ACCESS_BACKGROUND_LOCATION` (если ещё не выдан).
2. `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
3. Экран autostart у вендора (Xiaomi/Huawei/Oppo/Vivo/OnePlus/Samsung) —
   через `resolveActivity`, несуществующие пропускаются.

`BootReceiver` поднимает `LocationService` после reboot только когда флаг
`keepInBackground` включён и токен сконфигурирован.

## Конфигурация

Все URL зашиты в [`app/build.gradle.kts`](app/build.gradle.kts) как
`buildConfigField`:

- `API_BASE_URL` — бэкенд.
- `GITHUB_RELEASES_URL` — источник манифеста автообновления (подставлен
  реальный owner/repo).

Из UI не редактируются.

Bearer-токены выдаются владельцем сервера через мини-админку на
`whereami.alekseylosev.ru` (шестерёнка в правом верхнем углу).
