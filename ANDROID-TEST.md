# Whereami — тестовая инструкция Android ↔ Сервер

> Этот документ — пошаговый чек-лист, чтобы **до написания приложения** убедиться, что сервер принимает координаты от телефона и показывает их на карте. Полезен и Android-разработчику для отладки контракта.

Если будешь тыкать вживую — нужен **токен**. Получишь его у владельца сервера (через мини-админку кабинета, шестерёнка → «Добавить юзера» → скопировать показанный токен).

---

## 0. Подготовка

| Что | Где |
|---|---|
| Сервер | `https://whereami.alekseylosev.ru` |
| API health (open) | `https://whereami.alekseylosev.ru/api/health` |
| Карта (cookie auth) | `https://whereami.alekseylosev.ru/` |
| Мой `user_slug` | (выдаёт владелец, например `alex` / `wife` / `kid`) |
| Мой `bearer_token` | (выдаёт владелец, hex 32 символа) |
| Партнёрский `user_slug` | (для просмотра — `wife` если ты `alex` и т.д.) |

**Ничего, кроме токена и slug'а, тебе не нужно знать про сервер.** Все остальные настройки — в админке владельца.

---

## 1. Smoke-test: проверить, жив ли сервер

С любого устройства (компьютер, телефон) — открой в браузере:

```
https://whereami.alekseylosev.ru/api/health
```

Ожидаемый ответ:
```json
{"ok": true, "service": "whereami", "ts": "2026-06-24T..."}
```

Если 404 / 502 / timeout — пиши владельцу, у него лежит сервис.

---

## 2. Тест #1 — отправить координату через curl (с компьютера)

Самый быстрый способ убедиться, что **твой токен работает**.

Поставь свой токен в переменную и пошли тестовую точку (центр Москвы):

```bash
TOK="ваш-токен-32-hex-символа"
curl -X POST https://whereami.alekseylosev.ru/api/location \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -d '{"lat":55.7558,"lon":37.6173,"accuracy_m":15}'
```

Возможные ответы:

| Код | Тело | Что значит |
|---|---|---|
| 200 | `{"ok":true,"thinned":false,"position_id":123}` | ✅ Точка записана. На карте у тебя появится маркер в центре Москвы |
| 200 | `{"ok":true,"thinned":true,"position_id":null}` | ✅ Сервер видел ровно такую же точку <2 мин назад. Считай это тоже успехом, повторять не надо |
| 400 | `{"error":"bad_payload",...}` | Неверный JSON / диапазон lat/lon |
| 401 | `{"error":"invalid_token"}` | Токен битый. Попроси владельца сгенерировать новый |
| 5xx | — | Сервер упал. Сообщи владельцу |

---

## 3. Тест #2 — увидеть точку на карте

Открой в браузере (нужно быть залогиненным через `https://alekseylosev.ru`):

```
https://whereami.alekseylosev.ru/
```

После логина — карта должна показать маркер твоего юзера в центре Москвы.

Если маркер «прыгает» куда-то ещё — проверь, что предыдущие точки уже отчищены retention'ом (30 дней), либо посмотри `GET /api/positions` напрямую (нужен cookie):

```
https://whereami.alekseylosev.ru/api/positions
```

---

## 4. Тест #3 — получить позицию партнёра (через Bearer)

Это нужно, если в Android-приложении ты будешь показывать на карте, где сейчас второй человек.

```bash
PARTNER="wife"   # или alex / kid / ваш second slug
curl https://whereami.alekseylosev.ru/api/last/$PARTNER \
  -H "Authorization: Bearer $TOK"
```

Ответы:

```json
// есть данные
{
  "user": {"slug":"wife","display_name":"Жена","color":"#db2777"},
  "position": {
    "lat": 55.7558, "lon": 37.6173,
    "accuracy_m": 12.3, "speed_mps": null, "battery": 67,
    "recorded_at": 1782264000
  }
}

// партнёр настроен, но точек ещё нет
{"user":{...}, "position": null}

// такого slug'а в системе нет
{"error":"not_found"}  // HTTP 404
```

---

## 5. Тест #4 — на самом Android-устройстве (без приложения)

### Вариант A — Termux + curl

1. Поставь [Termux](https://f-droid.org/packages/com.termux/) из F-Droid.
2. В Termux:
   ```bash
   pkg install curl
   TOK="ваш-токен"
   curl -X POST https://whereami.alekseylosev.ru/api/location \
     -H "Authorization: Bearer $TOK" \
     -H "Content-Type: application/json" \
     -d '{"lat":55.75,"lon":37.62,"battery":80}'
   ```
3. Проверь, что вернулось `200 ok`.

### Вариант B — HTTP Shortcuts (без терминала)

1. Поставь [HTTP Request Shortcuts](https://play.google.com/store/apps/details?id=ch.rmy.android.http_shortcuts) — бесплатная.
2. Создай новый shortcut:
   - **Method:** POST
   - **URL:** `https://whereami.alekseylosev.ru/api/location`
   - **Headers:**
     - `Authorization: Bearer ваш-токен-32-hex`
     - `Content-Type: application/json`
   - **Body (JSON):**
     ```json
     {"lat":55.7558,"lon":37.6173,"accuracy_m":20,"battery":85}
     ```
3. Нажми «Run» — увидишь ответ сервера.
4. Можно сохранить shortcut на рабочий стол и быстро слать тестовые точки.

### Вариант C — Postman / RESTed на телефоне

Любой клиент с поддержкой headers + JSON body работает. Контракт идентичный.

---

## 6. Тест #5 — реальные координаты твоего телефона

В Termux:

```bash
pkg install termux-api
# принять permission: Location, Network
LAT=$(termux-location -p network | grep -oP '"latitude":\s*-?\d+\.\d+' | grep -oP '[-\d.]+$')
LON=$(termux-location -p network | grep -oP '"longitude":\s*-?\d+\.\d+' | grep -oP '[-\d.]+$')
echo "Сейчас: $LAT,$LON"
curl -X POST https://whereami.alekseylosev.ru/api/location \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -d "{\"lat\":$LAT,\"lon\":$LON,\"accuracy_m\":50}"
```

Открой `https://whereami.alekseylosev.ru/` в браузере — маркер должен переместиться к твоему реальному местоположению.

---

## 7. Тест прореживания

Сервер не пишет точки, которые ближе **25 м** к предыдущей и моложе **120 сек**.

Проверь:

```bash
# Точка 1 — записалась
curl -X POST https://whereami.alekseylosev.ru/api/location \
  -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
  -d '{"lat":55.7558,"lon":37.6173}'
# → {"ok":true,"thinned":false,"position_id":123}

# Точка 2 — та же, через 5 секунд → thinned
sleep 5
curl -X POST https://whereami.alekseylosev.ru/api/location \
  -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
  -d '{"lat":55.7558,"lon":37.6173}'
# → {"ok":true,"thinned":true,"position_id":null}

# Точка 3 — далеко (5 км в сторону), не thinned
curl -X POST https://whereami.alekseylosev.ru/api/location \
  -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" \
  -d '{"lat":55.8,"lon":37.7}'
# → {"ok":true,"thinned":false,"position_id":124}
```

В Android-клиенте: `thinned: true` — это **успех**, **не пытайся повторно слать ту же точку**.

---

## 8. Тест трека и retention

После того, как накопится несколько точек, открой карту → правая верхняя панель → селектор «Трек». Варианты:
- нет
- за час
- за день
- за неделю
- за месяц

Опция «за месяц» рисует точки за последние 30 дней. Раньше этого срока сервер ничего не хранит — retention каждый сутки чистит positions старше `POSITION_RETENTION_DAYS` (дефолт 30 дней).

---

## 9. Что проверить в Android-приложении

После того как разработчик соберёт apk, протестируй вживую:

| Сценарий | Ожидание |
|---|---|
| Первый запуск | Запрашивает permissions (Fine + Background Location + Notifications) |
| Settings → ввод server URL + token + slug + кнопка «Тест» | Toast `200 ok` или `200 thinned`, ошибок нет |
| Settings с битым токеном | Toast `401 invalid_token`, foreground service не стартует |
| Main → Start | Постоянная нотификация «Whereami: отслеживание», статус «Отправлено: HH:MM» обновляется минимум раз в минуту |
| Выключить WiFi+мобильную сеть | Статус «В очереди: N». N растёт |
| Включить сеть обратно | Через 1-15 мин N падает до 0 (WorkManager разгрёб) |
| 24 часа non-stop работы | Доп. расход батареи не больше 5-8% |
| На сервере (через карту) | Маркер юзера двигается, трек за день показывает реальный путь |
| Owner ротирует токен через UI | Старый телефон получает 401 на следующей отправке, foreground service стопится |

---

## 10. Логи на сервере (для отладки)

Если что-то странное — попроси владельца показать:

```bash
# на VPS
sudo journalctl -u lav-whereami.service -n 50 --no-pager
# или
sudo tail -20 /var/log/lav/whereami.{out,err}.log
```

В out-логе видно каждый запрос:
```
<-- POST /api/location
--> POST /api/location 200 3ms
<-- GET /api/last/wife
--> GET /api/last/wife 200 2ms
```

Если запросы не доходят до сервера (нет строки `<-- POST /api/location`) — проблема в Android-клиенте или сети.
Если есть `<--` но нет `-->`, или код 4xx/5xx — проблема на стороне сервера или payload'е.

---

## 11. Контрольный список (TL;DR)

- [ ] `GET /api/health` возвращает `{ok:true}`
- [ ] curl POST `/api/location` с токеном → `200 ok`
- [ ] curl POST с битым токеном → `401`
- [ ] curl POST без поля `lat`/`lon` → `400`
- [ ] два одинаковых POST'а подряд → второй `thinned:true`
- [ ] GET `/api/last/<partner>` с любым валидным токеном → видна позиция партнёра
- [ ] Karta `https://whereami.alekseylosev.ru/` показывает маркер на твоей последней координате
- [ ] Селектор «за месяц» на карте рисует точки за 30 дней

Если все галочки — контракт работает, Android-клиент можно писать с уверенностью.
