# Hypnosia Visuals — Полная техническая инструкция

## Содержание
1. [Общая архитектура](#общая-архитектура)
2. [Фронтенд — Страницы и компоненты](#фронтенд)
3. [Бэкенд — API роутеры](#бэкенд)
4. [База данных — Таблицы](#база-данных)
5. [Авторизация](#авторизация)
6. [Привязка Minecraft](#привязка-minecraft)
7. [Админ панель и 2FA](#админ-панель)
8. [Система поинтов и магазин](#система-поинтов)
9. [Синхронизация Server 1 ↔ Server 2](#синхронизация)
10. [Что менять при деплое на продакшен](#продакшен-чеклист)

---

## Общая архитектура

```
┌─────────────────────────────────────────────────────────────┐
│                        Браузер                              │
│  React 19 + TypeScript + Vite + Tailwind CSS + shadcn/ui  │
│  Хранит: localStorage (dev_login, session state)            │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP (tRPC + Hono)
┌──────────────────────────▼──────────────────────────────────┐
│                    Vite Dev Server                          │
│  Port 3000 — обслуживает и API и статические файлы          │
└──────────────────────────┬──────────────────────────────────┘
                           │ SQL (Drizzle ORM)
┌──────────────────────────▼──────────────────────────────────┐
│                    MySQL Database                           │
│  Все таблицы: users, profiles, codes, sync_queue и т.д.    │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP Polling
┌──────────────────────────▼──────────────────────────────────┐
│                   Server 1 (Minecraft)                      │
│  Java-приложение, которое опрашивает sync_queue              │
│  Отвечает за: роли, HWID, конфиги, лобби                   │
└─────────────────────────────────────────────────────────────┘
```

---

## Фронтенд

### Маршрутизация (App.tsx)

| Путь | Компонент | Для кого |
|------|-----------|----------|
| `/` `/welcome` | `WelcomeModulesPage` | Все |
| `/tops` | `TopsPage` | Все |
| `/profile/:id` | `ProfilePage` | Все |
| `/team` | `TeamPage` | Все |
| `/store` | `StorePage` | Все |
| `/login` | `Login` | Неавторизованные |
| `/link` | `MinecraftLinkPage` | Авторизованные |
| `/profile/settings` | `ProfileSettingsPage` | Авторизованные |
| `/admin` | `AdminPage` | Только admin (id=1) |
| `*` | `NotFound` | Все |

### Компоненты

#### Navbar (`src/sections/Navbar.tsx`)
- **Фиксированная шапка** с blur-эффектом
- **Логотип** HYPNOSIA — ведет на /welcome
- **Вкладки**: Welcome, Tops, Team, Store (+ Admin для админа)
- **Кнопка Download** — пока заглушка (`#`)
- **Аккаунт**: Для неавторизованных — "Login", для авторизованных — дропдаун с:
  - Аватар (первая буква имени на градиенте)
  - Ник с градиентом + роль DEVELOPER
  - Меню: Настройки, Привязка, Градиент, Панель управления, Выйти
- **Закрытие дропдауна** — при клике вне меню (useEffect + mousedown)

#### WelcomeModulesPage (`src/sections/WelcomeModulesPage.tsx`)
- 7 модулей в timeline-лейауте
- Скриншоты из `/screenshots/` (4 штуки)
- Hero-секция "HI" с описанием мода
- Stats-блок в футере секции

#### TopsPage (`src/sections/TopsPage.tsx`)
- Переключатель Monthly / All-Time
- Список игроков с позициями
- Каждая строка: аватар, ник (с градиентом), роль (с градиентом), часы
- Поле поиска по ID

#### ProfilePage (`src/sections/ProfilePage.tsx`)
- 3D скин-вьювер через skinview3d (по `skinUrl`, иначе дефолтный Steve)
- Карточка с онлайн-статусом, датами
- Ник и роль с градиентами

#### TeamPage (`src/sections/TeamPage.tsx`)
- Две секции: Команда мода + Команда сайта
- Участники с аватарами и ролями
- Открытые позиции с кнопкой "Apply"

#### StorePage (`src/sections/StorePage.tsx`)
- Баланс поинтов (из localStorage)
- 3 тира спонсорки: Base (30дн), Plus (60дн), PlusPlus (90дн)
- Раздел Cosmetics (градиенты)
- Раздел Tech (HWID сброс)
- Модальное окно FunPay для оплаты

#### Login (`src/pages/Login.tsx`)
- 3D SkinViewer
- Кнопка "Войти через Discord" → редирект на OAuth
- Обязательный чекбокс согласия с Политикой конфиденциальности и Правилами пользования

#### MinecraftLinkPage (`src/pages/MinecraftLink.tsx`)
- **Статус привязки**: показывает ник, ID, роль (через `minecraft.licenseLinkStatus`)
- **Баланс поинтов** с кнопкой пополнения
- **Модальные окна**:
  - Points — выбор пакета поинтов
  - Gradient — смена градиента ника и роли (отдельно), 5 пресетов
  - HWID — подтверждение сброса HWID
- **Привязка**: игрок вводит 6-значный код из игровой команды `/hypnosia link` (см. раздел «Привязка аккаунта»)

#### ProfileSettingsPage (`src/pages/ProfileSettings.tsx`)
- **Превью** — показывает как профиль выглядит в Tops
- **Видимость** — 4 тумблера (часы, дата MC, онлайн, позиция в топе)
- **Градиент ника** — 2 color picker'а + 5 пресетов
- **Градиент роли** — 2 color picker'а + 5 пресетов
- **Discord Bot** — инфо о командах `/role`
- **Сохранить** — показывает toast (без реального API)

#### AdminPage (`src/pages/AdminPage.tsx`)
- **2FA вход**: форма с 6-значным кодом
- **Настройка 2FA** (первый вход): показ секретного ключа + активация
- **Статистика**: 4 карточки (кодов всего, использовано, ожидает sync, активных подписок)
- **Генерация кодов**: выбор суммы поинтов × количество → генерация
- **Таблица кодов**: список с статусом (активен/использован)

#### DynamicBackground (`src/sections/DynamicBackground.tsx`)
- Canvas 2D с частицами
- Частицы двигаются, соединяются линиями при приближении
- Реакция на мышь (притяжение)
- Оптимизировано через requestAnimationFrame

#### useAuth (`src/hooks/useAuth.ts`)
- Проверяет `localStorage.getItem('dev_login') === 'nachosia'`
- Если dev mode — возвращает mock-пользователя (id=1, name=Nachosia, role=admin)
- Если нет — делает tRPC запрос `auth.me`
- `logout()` — чистит localStorage и перезагружает страницу

---

## Бэкенд

### Структура API

```
api/
├── router.ts          # Регистрация всех роутеров
├── middleware.ts      # Процедуры (publicQuery, authedQuery, adminQuery)
├── auth-router.ts     # OAuth авторизация (Kimi SDK)
├── minecraft-router.ts # Привязка аккаунта через License Server
├── admin-router.ts    # Админ панель: 2FA, коды, статистика
├── kimi/              # SDK для OAuth (не трогать)
├── lib/               # Внутренние утилиты (не трогать)
└── queries/
    └── connection.ts  # Подключение к MySQL через Drizzle
```

### auth-router.ts

| Метод | Описание |
|-------|----------|
| `auth.me` | Возвращает текущего пользователя по JWT сессии |
| `auth.logout` | Уничтожает сессию, чистит куки |

**OAuth Flow:**
1. Пользователь кликает "Войти через Discord"
2. Фронтенд редиректит на `VITE_KIMI_AUTH_URL`
3. Kimi авторизует и редиректит на `/api/oauth/callback`
4. Сервер создает/обновляет пользователя в таблице `users`
5. Устанавливает JWT в cookie
6. Редиректит обратно на фронтенд

### minecraft-router.ts

> Привязка идёт через **License Server**: клиент мода отправляет на сайт только `accountKey` + хэш HWID, без UUID/ника Minecraft. Ник берётся из License Server (`displayName`).

| Метод | Входные данные | Описание |
|-------|---------------|----------|
| `minecraft.verifyLicenseCode` | `{ code: string }` (6 символов A-Z0-9) | Авторизованный пользователь вводит код из `/hypnosia link`. Сервер находит код в `mod_link_codes` (по хэшу), привязывает `modAccounts.discordId`, тянет роль/градиенты с License Server, создаёт/обновляет `player_profiles`. Rate-limit 5 попыток / 5 мин. |
| `minecraft.licenseLinkStatus` | — | Возвращает `{ linked, accountId, displayName, role }` для текущего пользователя (по `modAccounts.discordId`). |

> Legacy-процедуры (`generateCode`, `verifySiteCode`, `registerMCCode`, `verifyMCCode`, `status`, `unlink`) и старый `mod-router.ts` **удалены**. UUID Minecraft нигде не собирается.

### admin-router.ts

| Метод | Входные данные | Описание |
|-------|---------------|----------|
| `admin.get2FAStatus` | — | Проверяет включен ли 2FA |
| `admin.setup2FA` | `{ secret: string, token: string }` | Включает 2FA с проверкой первого кода |
| `admin.verifySession` | `{ token: string }` | Проверяет TOTP код, выдает sessionToken |
| `admin.generateCode` | `{ points: number, count: number }` | Генерирует N кодов на X поинтов |
| `admin.listCodes` | — | Список всех кодов (последние 50) |
| `admin.dashboard` | — | Статистика: коды, sync, подписки |

**TOTP верификация** (реализована через `speakeasy`):
```typescript
import * as speakeasy from 'speakeasy';
function verifyTOTP(token: string, secret: string): boolean {
  return speakeasy.totp.verify({ secret, encoding: 'base32', token, window: 2 });
}
```

---

## База данных

### Таблицы

#### `users` — Пользователи (авторизация)
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | Уникальный ID |
| unionId | varchar(255) | Kimi union ID |
| name | varchar(255) | Имя пользователя |
| email | varchar(320) | Email |
| avatar | text | URL аватара |
| role | enum(user,admin) | Роль по умолчанию user |
| createdAt | timestamp | Дата регистрации |
| lastSignInAt | timestamp | Последний вход |

#### `mod_link_codes` — Одноразовые коды привязки (новая система)
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| accountId | int | Ссылка на `mod_accounts.id` |
| codeHash | varchar | Хэш 6-значного кода (сам код не хранится) |
| accountKey | varchar | Зашифрованный ключ аккаунта License Server |
| expiresAt | timestamp | Истекает через ~10 минут |
| usedAt | timestamp | Когда код использован (NULL = не использован) |

> ⚠️ Таблицы `minecraft_links` и `link_codes`, а также колонки `minecraft_uuid` **удалены**. UUID Minecraft больше не собирается и не хранится.

#### `player_profiles` — Публичные профили
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| discordId | varchar(32) | Discord ID |
| displayName | varchar(16) | Отображаемый ник |
| role | enum | user/vip/developer/sponsor/sponsor_plus/sponsor_plusplus |
| hoursPlayed | serial | Часы в игре |
| mcJoined | varchar(10) | Дата регистрации MC |
| siteJoined | timestamp | Дата регистрации на сайте |
| isOnline | enum(true,false) | Онлайн статус |
| showHours/showMcJoined/showOnline | enum | Настройки видимости |
| nickGradientFrom/To | varchar(7) | HEX цвета градиента ника |
| roleGradientFrom/To | varchar(7) | HEX цвета градиента роли |
| discordBotEnabled | enum | Включен ли Discord бот |
| lastSeen | timestamp | Последняя активность |

#### `redemption_codes` — Коды для пополнения поинтов
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| code | varchar(12) | Уникальный код (10 символов) |
| points | serial | Сколько поинтов дает |
| used | enum(true,false) | Использован |
| usedBy | varchar(32) | Discord ID кто использовал |
| createdBy | varchar(32) | Discord ID админа |
| createdAt | timestamp | Когда создан |
| usedAt | timestamp | Когда использован |

#### `subscription_purchases` — Покупки спонсорки
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| discordId | varchar(32) | Кто купил |
| tier | enum | sponsor/sponsor_plus/sponsor_plusplus |
| days | serial | На сколько дней |
| pricePoints | serial | Цена в поинтах |
| roleAssigned | enum(true,false) | Выдана ли роль на Server 1 |
| roleAssignedAt | timestamp | Когда роль выдана |
| expiresAt | timestamp | Когда истекает |

#### `payment_sessions` — Платежи FunPay/Crypto
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| discordId | varchar(32) | Кто платит |
| amount | serial | Сумма в рублях |
| pointsGiven | serial | Сколько поинтов начислено |
| provider | enum | funpay/cryptomus/freekassa |
| status | enum | pending/paid/cancelled/failed |
| externalId | varchar(128) | ID транзакции у провайдера |

#### `admin_2fa` — Двухфакторная аутентификация
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| discordId | varchar(32) | Discord ID админа |
| secret | varchar(64) | TOTP секрет (base32) |
| enabled | enum(true,false) | Включен ли 2FA |
| verifiedAt | timestamp | Когда включен |

#### `role_logs` — Логи изменения ролей
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| discordId | varchar(32) | Чья роль изменена |
| action | enum | assign/remove/upgrade |
| oldRole/newRole | enum | Старая/новая роль |
| performedBy | varchar(32) | Кто изменил (бот/админ) |
| reason | varchar(255) | Причина |

#### `hwid_logs` — Логи сброса HWID
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| discordId | varchar(32) | Кто сбросил |
| createdAt | timestamp | Когда сброшен |

#### `sync_queue` — Очередь для Server 1
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| action | enum | role_assign/role_remove/hwid_reset/config_update |
| discordId | varchar(32) | Целевой пользователь |
| payload | text | JSON с данными для Server 1 |
| status | enum | pending/sent/acknowledged/failed |
| retryCount | serial | Сколько попыток |
| createdAt | timestamp | Когда создан |
| processedAt | timestamp | Когда обработан |

#### `weekly_stats` — Еженедельная статистика
| Поле | Тип | Описание |
|------|-----|----------|
| id | serial PK | — |
| discordId | varchar(32) | Пользователь |
| date | varchar(10) | Дата YYYY-MM-DD |
| hoursPlayed | serial | Часы за день |
| configsUploaded | serial | Конфигов загружено |
| uniqueKey | varchar(64) | discordId_date (для upsert) |

---

## Авторизация

### Dev Login (удалён)
> ⚠️ **dev_login был удалён.** Локальная разработка теперь требует настоящий Discord OAuth. Роль вычисляется исключительно на сервере (`computeEffectiveRole`), подмена через `localStorage` невозможна.

### Discord OAuth (продакшен)
1. Кнопка "Войти через Discord" → редирект на портал Kimi
2. После авторизации callback создает/обновляет запись в `users`
3. JWT сессия в httpOnly cookie
4. `auth.me` возвращает реального пользователя

---

## Привязка аккаунта (через License Server)

Привязки по UUID Minecraft **больше нет**. Используется единый поток через клиент мода и License Server. UUID/ник Minecraft на сайт не передаются.

### Поток привязки

```
[Игрок в Minecraft (мод)]              [Игрок на сайте]
     |                                      |
     | 1. Пишет в игре /hypnosia link       |
     | 2. Мод (AccountManager) шлёт на сайт |
     |    accountKey + hwidHash (без UUID)  |
     | 3. Сайт создаёт запись в             |
     |    mod_link_codes (codeHash, TTL)    |
     | 4. Мод показывает 6-значный код      |
     |                        5. Игрок (авторизованный
     |                           через Discord) вводит код
     |                           на /link
     |                        6. minecraft.verifyLicenseCode:
     |                           - находит код по хэшу
     |                           - привязывает modAccounts.discordId
     |                           - тянет роль/градиенты с License Server
     |                           - создаёт/обновляет player_profiles
     |                        7. Привязка готова!
```

**API:**
- `POST /api/trpc/minecraft.verifyLicenseCode` — ввод кода на сайте (авторизованный пользователь)
- `POST /api/trpc/minecraft.licenseLinkStatus` — статус привязки
- Регистрация кода модом идёт через защищённый mod-эндпоинт (см. `mod-api.ts`, `modLinkCodes`)

### Запись mod_accounts после привязки:
```
id: 42
accountId: "<License Server account id>"
discordId: "<реальный Discord ID>"
displayName: "Nachosia"   # приходит с License Server
licenseRoles: ["SLIHA"]
accountKeyEnc: "<зашифрованный ключ>"
```

### Запись player_profiles (создаётся/обновляется автоматически):
```
discordId: "<Discord ID>"
displayName: "Nachosia"   # ник с License Server
role: "developer"
siteJoined: 2025-01-15
isOnline: "false"
nickGradientFrom: "#80FF97"
nickGradientTo: "#6BB7FF"
roleGradientFrom: "#6BB7FF"
roleGradientTo: "#FFD700"
skinUrl: null             # кастомный скин (если загружен), иначе Steve
```

> Скин в профиле и топах рендерится по `skinUrl` (загруженный пользователем). Если его нет — показывается дефолтный Steve. UUID-скины Mojang больше не используются.

---

## Админ панель

### Первый вход (настройка 2FA)
1. Открыть `/admin`
2. Сервер проверяет `admin_2fa` таблицу — записи нет
3. Показывается экран настройки 2FA
4. Отображается секретный ключ (базовый формат base32)
5. Игрок вводит код из приложения (Google Authenticator)
6. Сервер проверяет код и сохраняет запись:
   ```
   discordId: "nachosia"
   secret: "JBSWY3DPEHPK3PXP"
   enabled: "true"
   ```

### Последующие входы
1. Открыть `/admin`
2. Сервер находит запись 2FA (enabled="true")
3. Показывается форма ввода 6-значного кода
4. После верификации — доступ к панели

**Реализация:** `verifyTOTP()` использует библиотеку `speakeasy`:
```typescript
import speakeasy from 'speakeasy';
function verifyTOTP(token: string, secret: string): boolean {
  return speakeasy.totp.verify({ secret, encoding: 'base32', token, window: 2 });
}
```

---

## Система поинтов

### Пополнение через FunPay
1. Игрок нажимает "+ Пополнить" на странице /link
2. Выбирает пакет поинтов (1000, 1500, 2000, 3500, 5000, 7500, 10000)
3. Открывается модальное окно с формой FunPay
4. После оплаты:
   - FunPay шлет webhook на `/api/webhook/funpay`
   - Сервер создает запись в `payment_sessions` (status="paid")
   - Начисляет поинты пользователю
   - Показывает toast с подтверждением

### Пополнение через код
1. Игрок получает код (из админ панели или другое)
2. Вводит код в поле "Активировать код"
3. Сервер проверяет `redemption_codes`:
   - Код существует?
   - used === "false"?
4. Если ок:
   - Обновляет used="true", usedBy=discordId, usedAt=now
   - Начисляет поинты
   - Показывает сколько поинтов получено

### Магазин (/store)
- **Sponsor tiers** — загружаются динамически из `store_items` (type=`subscription_key`)
- При покупке `subscription_key`: генерируется ключ, применяется на License Server, создаётся `userEntitlements`
- **Cosmetics** — `gradient_pass` (500 HY-P)
- **Tech** — `hwid_reset` (1000 HY-P), сбрасывает HWID на License Server сразу при покупке
- `purchase` mutation атомарна (`db.transaction`)

---

## Синхронизация

### `sync_queue` — схема только, consumer отсутствует
> ⚠️ Таблица `sync_queue` существует в схеме, но **не используется**. Нет endpoint'ов для чтения/ack, нет background worker'а, нет INSERT'ов в коде. Cross-server синхронизация идёт напрямую через License Server API (`applyKeyOnLicenseServer`, `resetHwidOnLicenseServer`).

---

## Продакшен чеклист

### 1. База данных
```bash
cd /mnt/agents/output/app
npm run db:push        # Синхронизировать схему с MySQL
```

### 2. Discord OAuth (Kimi)
- Создать приложение на https://kimi.moonshot.cn
- Получить `APP_ID` и `APP_SECRET`
- Указать callback URL: `https://your-domain.com/api/oauth/callback`
- Обновить `.env`:
  ```
  VITE_APP_ID=your_app_id
  APP_SECRET=your_app_secret
  VITE_KIMI_AUTH_URL=https://kimi.moonshot.cn/oauth/authorize?client_id=...
  ```

### 3. 2FA (обязательно!)
```bash
npm install speakeasy
```
- Заменить `verifyTOTP()` в `api/admin-router.ts` на реальную проверку через speakeasy
- Убрать заглушку `return /^\d{6}$/.test(token)`
- Первый вход в админку создаст реальную 2FA запись

### 4. FunPay вебхук
- Настроить URL вебхука в личном кабинете FunPay
- Указать: `https://your-domain.com/api/webhook/funpay`
- Реализовать обработчик в `api/router.ts`:
  ```typescript
  webhook: publicQuery
    .input(z.object({ orderId: z.string(), status: z.string(), amount: z.number() }))
    .mutation(async ({ input }) => {
      // Проверить подпись, обновить payment_sessions, начислить поинты
    })
  ```

### 5. Server 1 интеграция
- Реализовать polling `GET /api/sync/pending` на стороне Java-приложения
- Реализовать ack `POST /api/sync/ack` после выполнения задачи
- Добавить retry логику (5 попыток с экспоненциальной задержкой)

### 6. Привязка через мод (команда /hypnosia link)
- Команда регистрируется в моде (`LinkCommand.kt`), UUID/ник игрока не отправляются
- Мод (`AccountManager.registerLinkCodeAsync`) шлёт на сайт только `accountKey` + хэш HWID:
  ```
  POST https://your-domain.com/api/mod/...  (защищённый mod-эндпоинт)
  Body: { "accountKey": "...", "hwidHash": "..." }
  ```
- Игрок вводит полученный 6-значный код на `/link`:
  ```
  POST https://your-domain.com/api/trpc/minecraft.verifyLicenseCode
  Body: { "json": { "code": "ABC123" } }
  ```
- Ник и роль берутся из License Server, UUID Minecraft нигде не используется

### 7. Discord Bot
- Создать бота на https://discord.com/developers/applications
- Добавить scope: `bot`, permissions: `Manage Roles`
- Реализовать команды:
  - `/role give @user <role>`
  - `/role remove @user`
  - Бот читает/пишет в таблицу `role_logs`

### 8. Скриншоты
- Положить 4 скриншота в `public/screenshots/`
- Назвать: `screenshot1.jpg`, `screenshot2.jpg`, `screenshot3.jpg`, `screenshot4.jpg`

### 9. Dev Login (отключить!)
- Удалить кнопку "Войти от лица Nachosia" со страницы Login
- Или обернуть в `process.env.NODE_ENV === 'development'`

### 10. HashRouter → BrowserRouter (опционально)
- Если деплоишь на сервер с настроенным nginx (все пути ведут на index.html) — можешь оставить HashRouter
- Для "чистых" URL без `#` — заменить на BrowserRouter и настроить fallback

### 11. Защита админки
- Реализовано через `adminQuery` middleware (`api/middleware.ts`)
- Проверяет `effectiveRole` серверно: `requireRole("admin", "owner")`
- Дополнительно: 2FA TOTP через `speakeasy`
- ⚠️ `ADMIN_DISCORD_ID` захардкожен в `admin-router.ts` — только один админ может настроить 2FA

### 12. Список файлов которые трогать НЕЛЬЗЯ
```
api/lib/          # Внутренности фреймворка
api/kimi/         # OAuth SDK
api/middleware.ts # Только добавлять новые процедуры, не удалять
api/queries/connection.ts  # Подключение к БД
drizzle.config.ts # Конфиг миграций
.env              # Сгенерирован init.sh
vite.config.ts    # Можно добавлять алиасы, не удалять существующие
```

### 13. Список файлов которые МОЖНО и НУЖНО менять
```
db/schema.ts            # Добавлять таблицы
api/router.ts           # Регистрировать новые роутеры
api/*-router.ts         # Создавать новые роутеры
src/pages/*.tsx         # Создавать страницы
src/sections/*.tsx      # Создавать секции
src/components/*.tsx    # Создавать компоненты
src/hooks/*.ts          # Кастомные хуки
src/App.tsx             # Добавлять маршруты
public/screenshots/     # Скриншоты мода
```

---

## Быстрый старт (для нового разработчика)

```bash
# 1. Установить зависимости
cd G:\.site\app
npm install

# 2. Синхронизировать БД
npm run db:push

# 3. Запустить dev-сервер
npm run dev

# 4. Открыть http://localhost:3000

# 5. Войти через Discord OAuth

# 6. Все страницы доступны:
#    /welcome — главная
#    /tops — топы
#    /team — команда
#    /store — магазин
#    /link — привязка аккаунта (через License Server)
#    /profile/settings — настройки
#    /admin — админ панель (требуется 2FA)
```

---

## Техподдержка

Если что-то сломалось:

1. **Ошибка типов** — `npm run check`
2. **БД не подключается** — проверь `DATABASE_URL` в `.env`
3. **OAuth не работает** — проверь `VITE_APP_ID` и callback URL
4. **Скриншоты не грузятся** — проверь что файлы в `public/screenshots/`
5. **Navbar не показывает админку** — проверь серверную роль пользователя (`effectiveRole` должен содержать admin/owner)
