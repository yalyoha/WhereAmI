# Whereami — задача для Android-разработчика

> Этот документ самодостаточный. Серверная часть уже существует и работает на `https://whereami.alekseylosev.ru`. От тебя — Android-приложение, которое раз в минуту в фоне шлёт координаты телефона на сервер, и опционально показывает на простой карте, где сейчас остальные пользователи.

---

## 1. Что нужно сделать

Android-приложение для **двух или более телефонов** (например: мой, жены, ребёнка — сколько настроим). Каждый телефон отдельно настроен на свой `user_slug` и свой токен. Сервер хранит позиции всех в одной БД и отдаёт их через API.

Бэкенд поддерживает произвольное число юзеров — владелец сервера управляет ими через мини-админку в кабинете `whereami.alekseylosev.ru` (шестерёнка в правом верхнем углу): добавить юзера, выдать токен, ротировать, удалить. После создания юзера сервер показывает токен **один раз** — владелец копирует его и отдаёт Android-разработчику отдельно (в чате/мессенджере). `GET /api/users` возвращает всех настроенных юзеров (без токенов), `GET /api/last/:slug` работает для любого из них.

**MVP (на стороне Android):**
1. Постоянная фоновая отправка моих координат на сервер раз в минуту.
2. Экран со списком/картой точек настроенных партнёров (1, 2, N — сколько укажу в Settings), обновляется при открытии и pull-to-refresh.

---

## 2. API контракт (твой единственный источник правды)

База: `https://whereami.alekseylosev.ru/api/`

Все запросы используют HTTPS. Сертификат валидный (Let's Encrypt), pinning не нужен.

### 2.1. POST /api/location — отправить мою координату

```
POST https://whereami.alekseylosev.ru/api/location
Authorization: Bearer <MOY-TOKEN>
Content-Type: application/json; charset=utf-8
```

Тело запроса:
```json
{
  "lat": 55.7558,
  "lon": 37.6173,
  "accuracy_m": 12.3,
  "speed_mps": 0,
  "battery": 78,
  "recorded_at": 1782264000
}
```

| Поле | Тип | Обязательное | Описание |
|---|---|---|---|
| `lat` | float | **да** | Широта, диапазон [-90, 90] |
| `lon` | float | **да** | Долгота, диапазон [-180, 180] |
| `accuracy_m` | float | нет | Точность GPS в метрах |
| `speed_mps` | float | нет | Скорость в м/с |
| `battery` | int | нет | Заряд батареи 0-100 |
| `recorded_at` | int | нет | UNIX-секунды момента замера. Если не передал — сервер использует своё время. **Рекомендуется передавать.** |

**Важно:** в payload **НЕТ user_id**. Сервер определяет пользователя по `Bearer <token>`. Каждый телефон имеет свой токен из настроек.

Ответы:

| Код | Тело | Что это значит | Что делать |
|---|---|---|---|
| 200 | `{"ok": true, "thinned": false, "position_id": 123}` | Координата записана | Считать отправку успешной, очистить из очереди |
| 200 | `{"ok": true, "thinned": true, "position_id": null}` | Сервер уже видел такую же точку (близко к прошлой + недавно). Не записано, но это нормально | **Считать успехом.** Очистить из очереди, не повторять |
| 400 | `{"error": "bad_payload", "detail": "..."}` | Невалидные координаты | Залогировать, не повторять |
| 401 | `{"error": "invalid_token"}` | Токен битый или не настроен | **Остановить foreground service**, показать нотификацию «Whereami: токен невалиден, проверьте настройки» |
| 5xx | любое | Сервер упал | Положить в retry-очередь, повторить позже |
| timeout / network | — | Нет сети | В retry-очередь |

Таймаут запроса: **10 секунд**.

### 2.2. GET /api/last/:user_slug — последняя позиция любого пользователя

Используется на главном экране приложения, чтобы показать, где сейчас второй человек. Тебе нужно знать `user_slug` второго пользователя (`alex` или `wife`). Это знает владелец телефона, ты вписываешь в Settings.

```
GET https://whereami.alekseylosev.ru/api/last/wife
Authorization: Bearer <MOY-TOKEN>
```

(Используй СВОЙ токен — сервер не привязывает доступ к конкретному пользователю, токен нужен только для аутентификации запроса.)

Ответы:

200 — есть данные:
```json
{
  "user": { "slug": "wife", "display_name": "Жена", "color": "#db2777" },
  "online": true,
  "position": {
    "lat": 55.7558, "lon": 37.6173,
    "accuracy_m": 12.3, "speed_mps": null, "battery": 67,
    "recorded_at": 1782264000
  }
}
```

`online: true` означает «партнёр сейчас активен» — его последняя точка свежее `ONLINE_THRESHOLD_SEC` (на сервере 180 сек). Если `online: false`, можно отрисовать маркер серым / показать «был X минут назад» (используй `recorded_at`).

200 — пользователь есть, но позиций ещё нет:
```json
{
  "user": { "slug": "wife", "display_name": "Жена", "color": "#db2777" },
  "online": false,
  "position": null
}
```

404 — такого `user_slug` в системе нет:
```json
{ "error": "not_found" }
```

401 — твой токен невалиден.

---

## 3. Жизненный цикл отправки

### 3.1. Получение координат — адаптивный SLOW/FAST режим

Клиент держит **два разных `LocationRequest`** и переключает их на лету в зависимости от скорости.
Это нужно, чтобы на велосипеде/в авто маркер на веб-карте не прыгал на 300+ м, но при этом
на пешем темпе и стоянке не разряжать батарею быстрым GPS.

**SLOW** (пешеход / стоянка, по умолчанию):

```kotlin
LocationRequest.Builder(60_000)              // interval
    .setMinUpdateIntervalMillis(30_000)      // fastest
    .setMaxUpdateDelayMillis(60_000)         // batching
    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    .setWaitForAccurateLocation(false)
    .build()
```

**FAST** (велосипед / авто):

```kotlin
LocationRequest.Builder(15_000)              // interval
    .setMinUpdateIntervalMillis(5_000)       // fastest
    .setMaxUpdateDelayMillis(20_000)         // batching
    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
    .setWaitForAccurateLocation(false)
    .build()
```

**Переключение режимов — в каждом `onLocationResult`**:

```kotlin
val mps = if (loc.hasSpeed()) loc.speed else return
val wantFast = if (fastMode) mps >= 1.5f   // гистерезис: выходим из FAST только ниже 1.5 м/с
               else          mps >= 3.0f   // входим в FAST с 3.0 м/с (~11 км/ч)
if (wantFast != fastMode) {
    fastMode = wantFast
    fused.requestLocationUpdates(buildRequest(fastMode), callback, looper)
    // requestLocationUpdates с тем же callback'ом просто перезапишет конфиг — подписки не плодятся.
}
```

Пороги: вход 3 м/с (~11 км/ч — нижняя граница спокойного велосипеда), выход 1.5 м/с — гистерезис,
чтобы на каждой остановке у светофора не дёргать подписку.

### 3.2. Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Runtime-разрешения: запрашиваем поэтапно (сперва FINE_LOCATION, потом BACKGROUND_LOCATION) — Android 11+ требует именно такой порядок.

### 3.3. Foreground Service + WorkManager

**Foreground Service** (`foregroundServiceType="location"`):
- Постоянная нотификация: «Whereami: отслеживание включено».
- Регистрирует `LocationCallback`. На каждое обновление:
  1. Прочитать battery: `(getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BATTERY_PROPERTY_CAPACITY)`.
  2. Сформировать JSON.
  3. POST через OkHttp (или Ktor) с таймаутом 10 сек.
  4. Если 2xx → удалить запись из локальной retry-очереди (если она там была).
  5. Если ошибка / 4xx (кроме 401) / 5xx → INSERT в Room-таблицу `pending_uploads`.
  6. Если 401 → stop foreground service, поднять Notification, очистить позицию ниже.

**WorkManager** (`PeriodicWorkRequest` каждые 15 минут):
- Constraints: `NETWORK_CONNECTED`, backoff exponential.
- Достать из `pending_uploads` до 100 записей FIFO.
- На каждую — POST. На 2xx — удалить из очереди. На ошибку — оставить (backoff WorkManager сам сделает).

### 3.4. Хранение настроек

`EncryptedSharedPreferences` (androidx.security.crypto:1.1.0+):

| Ключ | Значение |
|---|---|
| `server_url` | По умолчанию `https://whereami.alekseylosev.ru` |
| `bearer_token` | Hex-строка 32 символа. Владелец сервера сгенерирует её в админке кабинета и передаст тебе. Если токен «истёк» (401), попроси сгенерировать заново |
| `my_user_slug` | `alex` или `wife` (для UI) |
| `partner_user_slug` | Slug второго пользователя, для запроса `/api/last/...` |

---

## 4. UI приложения (минимум)

### 4.1. Settings

| Поле | Тип | Дефолт |
|---|---|---|
| Server URL | text | `https://whereami.alekseylosev.ru` |
| Token | password (masked) | пусто |
| My user slug | text | пусто |
| Partner user slug | text | пусто |
| Кнопка «Тест отправки» | — | посылает один POST с фиктивной координатой (центр экрана) и показывает Toast с кодом ответа и thinning-флагом |

### 4.2. Main

| Элемент | Описание |
|---|---|
| Большая кнопка Start/Stop | Включает/выключает foreground service |
| Текст статуса | «Последняя отправка: HH:MM. lat=XX.XXXXX, lon=YY.YYYYY. Ответ: 200 ok / 200 thinned / ошибка <code>» |
| Счётчик очереди | «В очереди: N» (берётся из Room `pending_uploads`) |
| Карта (опционально) | Можно показать `osmdroid` или Google Map с двумя маркерами: моя позиция (locally) + партнёр (через `GET /api/last/<partner_slug>`). Обновлять при открытии экрана и pull-to-refresh, каждые 30 секунд (если экран активен) |

Карта — приятный бонус, **MVP без карты тоже принимается**, главное — стабильная отправка.

---

## 5. Минимальные технические требования

| Параметр | Значение |
|---|---|
| `minSdkVersion` | 29 (Android 10 — для стабильного background-location API) |
| `targetSdkVersion` | 34 (или 35, если доступно) |
| Язык | Kotlin |
| UI | Jetpack Compose ИЛИ XML — на твой выбор |
| Архитектура | На твой выбор. Главное — расходимость UI и фонового сервиса минимизирована, оба читают одни и те же EncryptedSharedPreferences и Room |
| Зависимости | Google Play Services Location 21+, androidx.security.crypto, androidx.work, OkHttp 4+ (или Ktor) |
| HTTP-стек | OkHttp или Ktor — без разницы. Главное — таймаут 10 сек и поддержка HTTPS из коробки |

---

## 6. Acceptance criteria

Приложение считается готовым, когда:

1. **Permission flow**: первый запуск — запрашивает FINE_LOCATION, потом BACKGROUND_LOCATION (Android 11+), потом POST_NOTIFICATIONS (Android 13+). Если что-то отказали — показывает экран «нужны разрешения, кнопка Settings».
2. **Settings**: после ввода server URL + token + my_user_slug, кнопка «Тест отправки» возвращает `200 ok` или `200 thinned`. Без валидного токена — `401` и понятный текст в Toast.
3. **Foreground service**: после Start работает 24+ часа без прерывания. Нотификация постоянная, не убиваема свайпом. После Reboot — НЕ требуется автозапуск (это вопрос отдельный).
4. **Сеть выкл/вкл**: при выключенной сети накапливает в `pending_uploads`. При включении — WorkManager разгребает в течение 15 минут.
5. **Battery**: за 24 часа non-stop отправки расход батареи дополнительно не больше 5-8%.
6. **401 handling**: если токен битый, foreground service останавливается, поднимается одна Notification «whereami: токен невалиден, открой настройки».
7. **GET /api/last/:slug**: на главном экране при открытии (или pull-to-refresh) виден текст «партнёр: HH:MM, расстояние от меня XX км» или «партнёр: нет данных».

---

## 7. Что НЕ нужно делать

- **Не отправлять `user_id` в body запроса**. Сервер вычисляет пользователя по токену. Передавать что-то «дополнительно для надёжности» — лишнее.
- **Не считать `thinned: true` ошибкой**. Это успешный ответ, координата дошла, сервер сам решил, что её не имеет смысла дублировать.
- **Не делать клиентский retry для `400`**. Это битый payload — баг в твоём коде, повторять бесполезно.
- **Не пытаться реализовать аутентификацию через PHP/cookie**. Это для браузера. Android идёт ТОЛЬКО через Bearer на `/api/location` и `/api/last/*`. Другие эндпоинты для тебя недоступны и не нужны.
- **Не хранить токен в обычных SharedPreferences или Hardcoded**. Используй `EncryptedSharedPreferences`.

---

## 8. Что я (владелец сервера) сделаю на своей стороне

- Заведу нужное число юзеров (например, `alex`, `wife`, `kid`) через мини-админку кабинета `whereami.alekseylosev.ru` (шестерёнка в правом верхнем углу → «Добавить юзера»). Минимум — два, можно больше.
- Передам тебе индивидуальные токены — по одному на каждый телефон (показываются один раз при создании).
- Если токен потеряется или нужно «инвалидировать» (например, телефон украли) — в админке нажму «Перегенерировать токен» и передам новый, старый перестанет работать.

**Хранение позиций:** сервер хранит точки до 30 дней. Старше — автоматически удаляются (раз в сутки). Это покрывает любые маршрутные периоды (1 день / 1 неделя / 1 месяц), которые UI показывает на карте.

**Online/offline:** сервер считает юзера online, если его последняя точка свежее `ONLINE_THRESHOLD_SEC` (дефолт 180 сек = 3 мин) И эта точка пришла ПОСЛЕ последнего «выхода». Веб-карта рисует маркер всегда, где есть позиция: для online-юзеров — полная непрозрачность, для offline — тусклый (opacity 0.45) на последней известной точке с пометкой «X мин назад» в табло.

**Два способа стать offline:**
1. **Неявно**: просто перестать слать координаты (stop foreground service, нет сети). Через ≤3 мин маркер тускнеет.
2. **Явно**: вызвать `POST /api/logout` с Bearer-токеном — маркер тускнеет в следующее обновление карты (≤12 сек). Используй это, когда юзер нажимает «Выйти» в Android-приложении.

Окончательно метка пропадает только если юзер удалён через `/api/admin/users`.

### POST /api/logout — явный выход

```
POST https://whereami.alekseylosev.ru/api/logout
Authorization: Bearer <MOY-TOKEN>
```

(Тело не нужно. Заголовок Bearer обязателен.)

Ответ:
```json
{"ok": true, "offline_at": 1782264000}
```

После logout новый `POST /api/location` автоматически снова сделает юзера online — `offline_at` не «сжигает» аккаунт, только метит «сейчас не активен».
- Заведу базовый Yandex Maps API key для веб-интерфейса (тебе не нужен — у тебя в Android своя карта, если делаешь).

---

## 9. Полезные ссылки

- Прямой API health-check (без auth): `https://whereami.alekseylosev.ru/api/health` → `{"ok":true}`.
- Тест endpoint'а `/api/location` через curl:

```bash
curl -X POST https://whereami.alekseylosev.ru/api/location \
  -H "Authorization: Bearer <YOUR-TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"lat":55.7558,"lon":37.6173,"accuracy_m":10}'
```

Если возвращает `{"ok":true,...}` — твой токен работает.

---

## 10. Сдача работы

- Apk собран в release build, подписан debug или release-ключом (любым).
- Краткое README с инструкцией «как настроить» (server URL + token + my_user_slug + partner_user_slug).
- Исходники в zip или приватный git-репо.
- Желательно — короткое видео экрана, где видно: первый запуск, ввод настроек, тест отправки, статус «отправлено в HH:MM».
