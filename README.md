# WhereAmI — Android-клиент

Минимальное Android-приложение, которое шлёт GPS-координаты на бэкенд
`https://whereami.alekseylosev.ru` и показывает на карте Яндекса, где сейчас
остальные участники группы (alex / nadya / mama).

**Адаптивная частота отправки** (`LocationService.kt`):
- **SLOW** — пешеход/стоянка: интервал 60 сек, `BALANCED_POWER_ACCURACY`.
- **FAST** — велосипед/авто (скорость ≥ 3 м/с): интервал 15 сек, `HIGH_ACCURACY`.
- Переключение автоматическое в каждом `onLocationResult`, с гистерезисом
  (возврат в SLOW только при <1.5 м/с) — чтобы не дёргать подписку на каждом
  светофоре.

Бэкенд и его контракт — отдельный проект; полное ТЗ и тестовый план в
[`ANDROID-TASK.md`](ANDROID-TASK.md) и [`ANDROID-TEST.md`](ANDROID-TEST.md).

## Структура

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/example/whereami/
│   │   ├── WhereamiApp.kt          — Application: запускает RetryWorker
│   │   ├── AuthActivity.kt         — экран авторизации (token + slug)
│   │   ├── MainActivity.kt         — host для фрагментов + bottom nav
│   │   ├── MapFragment.kt          — карта Яндекса с маркерами всех юзеров
│   │   ├── PathFragment.kt         — мой трек за период
│   │   ├── InfoFragment.kt         — суммарная статистика + кнопка «Выйти»
│   │   ├── LocationService.kt      — foreground-сервис, fused location, адаптивный SLOW/FAST режим
│   │   ├── RetryWorker.kt          — WorkManager-воркер, разгребает offline-очередь
│   │   ├── UploadQueue.kt          — JSONL-файл с накопленными точками
│   │   ├── ApiClient.kt            — OkHttp + Bearer
│   │   ├── SettingsRepository.kt   — EncryptedSharedPreferences
│   │   ├── Haversine.kt            — расстояние между двумя координатами
│   │   └── Models.kt               — LocationUpload, PartnerLast, …
│   └── res/layout/                  — activity_auth.xml, activity_main.xml, fragment_*.xml
└── build.gradle.kts
```

## Сборка

```sh
.\gradlew.bat assembleDebug
# APK: app\build\outputs\apk\debug\WhereAmI-debug.apk
```

## Установка и запуск на эмуляторе

```sh
# 1. Поставить APK
adb -s emulator-5554 install -r app\build\outputs\apk\debug\WhereAmI-debug.apk

# 2. Запустить
adb -s emulator-5554 shell am start -n com.example.whereami/.AuthActivity

# 3. Дать фейковую геопозицию (центр Москвы)
adb -s emulator-5554 emu geo fix 37.6173 55.7558

# 4. Логи приложения
adb -s emulator-5554 logcat -v color *:S LocationService:V AuthActivity:V MapActivity:V RetryWorker:V
```

В эмуляторе можно менять координаты через GUI: «…» (Extended Controls) →
**Location** → Single point / Route.

## Конфигурация

Базовый URL зашит в [`app/build.gradle.kts`](app/build.gradle.kts) как
`buildConfigField` (`API_BASE_URL`). Из UI не редактируется.

Bearer-токены пользователей выдаёт владелец сервера через мини-админку на
`whereami.alekseylosev.ru` (шестерёнка в правом верхнем углу).

## Логика «вошёл/вышел»

- На запуске приложения: если token+slug сохранены — сразу `MainActivity`,
  сервис стартует, маркер появляется на карте у всех.
- Кнопка «Выйти» в `InfoFragment` → останавливает сервис, чистит настройки,
  дёргает `POST /api/logout`, возвращает на `AuthActivity`. Маркер
  на веб-карте тускнеет (opacity 0.45) в следующее обновление (≤12 сек) и
  остаётся на последней точке с пометкой «X мин назад» в табло.

## Известные ограничения

- Список slug'ов участников захардкожен в `MapActivity.KNOWN_SLUGS`.
  Когда `/api/users` начнёт принимать Bearer (сейчас за SSO) — заменим на
  динамический fetch.
- Yandex Maps JS API key в `app/src/main/assets/map.html` — клиентский,
  привязка к домену настраивается в кабинете Яндекса.
- Web-клиент / бэкенд — отдельный репозиторий, здесь не лежит.
