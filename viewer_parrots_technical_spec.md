# Viewer Parrots — техническое задание для Fabric

Версия документа: 1.0  
Дата: 12 сентября 2026 года  
Статус: спецификация для реализации MVP; код мода ещё не разработан и показатели не измерены.  
Основание: обсуждение «Идеи программ для стримера», прежде всего последнее функциональное ТЗ на мод без TTS.

## 1. Цель и границы проекта

Создать Minecraft-мод, в котором логическому зрителю соответствует персональный попугай с именем, постоянной идентичностью, характером и управляемым поведением. Первый релиз должен позволять администратору проверить эту механику непосредственно в игре, без внешних сервисов.

Слова «должен» и «обязательно» обозначают требования к приёмке. Значения по умолчанию и архитектурные решения ниже являются принятыми для этого ТЗ проектными допущениями, если в исходном обсуждении они не были определены.

### 1.1 Входит в MVP

- Один логический попугай на неизменяемый `viewerId`; имя не является ключом.
- Создание, удаление, просмотр, переименование и административное восстановление.
- Команды `come`, `follow`, `sit`, `dance`, `scream`.
- Один целевой игрок-стример на мир; его UUID сохраняется.
- Пять ванильных расцветок, постоянная personality trait, ограниченное автономное поведение.
- Сохранение реестра, обработка загрузки/выгрузки сущностей, смерти, рестартов и конфликтующих экземпляров.
- Серверная проверка прав, cooldowns, конфигурация, события, диагностические команды и журналирование.
- Работа на dedicated server и в одиночной игре через integrated server.

### 1.2 Не входит в MVP

TTS, произвольные голосовые файлы, микрофон, voice cloning, сайт, HTTP/WebSocket-сервер, Twitch/YouTube/Discord, OAuth, внешняя авторизация, база данных внешних пользователей, валюта, XP, уровни, магазин, инвентарь, кража предметов, добыча ресурсов, бой, PvP, сложный собственный pathfinding, коллективные события и несколько стримеров.

`scream` воспроизводит только разрешённые ванильные звуковые события. Внутренний Java API входит в MVP, внешние сетевые адаптеры — нет. Сетевой обмен самой игры между сервером и клиентами необходим для отображения сущностей и не является внешней интеграцией.

### 1.3 Уточнения исходного обсуждения

| Неоднозначность | Решение этого ТЗ |
|---|---|
| `ACTIVE`, `MISSING` и `DANCING` в одном enum | Разделить жизненный цикл и поведение; `ACTIVE` оставить как отображаемое имя `LOADED + IDLE` |
| Попугай не найден по UUID | Это не доказательство смерти: сначала `UNLOADED`/`UNRESOLVED`, затем ограниченная проверка |
| Команды относительно отправителя или стримера | Все пространственные действия ориентированы на назначенного стримера |
| Танец без jukebox | Собственная сущность и синхронизируемый флаг танца; мод требуется на клиентах |
| 50 активных попугаев | Лимит зарезервированных физических экземпляров, включая выгруженные; выгрузка не освобождает слот |
| Все шесть traits | В MVP реализовать NORMAL, COWARD, LAZY, LOUD; HERO и THIEF хранить и показывать, но без эффекта и без случайной выдачи |
| Неуязвимость | Защита от обычного урона; административное удаление и аварийная потеря обрабатываются lifecycle |

## 2. Платформа и совместимость

### 2.1 Базовая матрица

| Компонент | Требование |
|---|---|
| Игра | Minecraft Java Edition **1.21.1**, намеренно выбранная фиксированная база, не заявление о последней версии |
| Java | JDK/JRE 21 |
| Загрузчик | Fabric Loader; точная совместимая версия фиксируется в этапе M0 |
| API | Fabric API для 1.21.1; точная версия фиксируется в M0 |
| Mappings | Yarn `1.21.1+build.3` как база именования этого документа |
| Сборка | Gradle Wrapper + Fabric Loom; совместимые точные версии фиксируются в M0, без динамических `+`/`latest` |
| Установка | Один JAR с common/server и client entrypoint; Fabric API и мод обязательны на сервере и всех клиентах |
| Mod ID / entity ID | `viewerparrots` / `viewerparrots:viewer_parrot` |
| Окружения | Dedicated server и integrated server; без ссылок на клиентские классы в серверной инициализации |

Fabric публикует отдельные сведения для ветки 1.21/1.21.1; Java 21 соответствует требованиям этой базы. Источники: [Fabric для 1.21 и 1.21.1](https://www.fabricmc.net/2024/05/31/121.html), [настройка среды разработки](https://wiki.fabricmc.net/tutorial:setup).

До основной разработки M0 должен завершиться успешной сборкой и запуском клиента и dedicated server с выбранным набором зависимостей. Версии записываются в `gradle.properties`, Wrapper и README; целевой Minecraft в metadata ограничивается 1.21.1. Поддержка других версий требует отдельного порта и повторной приёмки.

### 2.2 Ограничения совместимости

- Vanilla-клиенты, Forge, NeoForge, Bukkit/Paper и Bedrock не поддерживаются.
- Базовая гарантия относится к Minecraft + Fabric Loader + Fabric API + Viewer Parrots. Совместимость с модпаками проверяется отдельно, особенно при изменении AI, entity ticking, renderer и сохранения сущностей.
- Обычные ванильные попугаи не меняются. Не использовать глобальные перехваты поведения всех `ParrotEntity`.
- Мод не создаёт природный спавн, spawn egg или рецепт. `/summon` собственной сущности без допустимой записи реестра не создаёт зрителя.
- Поддерживаются ванильные измерения; удалённое или неизвестное измерение даёт `DIMENSION_UNAVAILABLE`, без автоматической подмены Overworld.
- Рекомендуемый сервер работает с `online-mode=true`. Идентификатор зрителя не считается UUID Minecraft-аккаунта. После смены online/offline режима UUID стримера может потребовать переназначения.
- Безопасность открытия мира после удаления мода не гарантируется для его custom entities; резервная копия мира обязательна в инструкции по обновлению/удалению.

## 3. Архитектура и потоки исполнения

```text
Brigadier command adapter
          |
          v
Authorization -> validation -> capacity/cooldown checks
          |
          v
ViewerParrotService / ParrotCommandService
     |             |                  |
     v             v                  v
Registry      StateMachine       EventDispatcher
     |             |
     v             v
Persistence   EntityController -> ViewerParrotEntity
                                      |
                                      v
                           tracked state -> client renderer
```

### 3.1 Основные правила

1. Сервер является единственным источником истины для идентичности, состояния, команд, traits, cooldowns и звуков.
2. Изменять Minecraft world/entity, реестр и state machine можно только на server thread. Клиент отвечает за визуализацию и не рассчитывает авторитетное поведение.
3. Файловый I/O выполняется одним выделенным writer executor. На него передаются неизменяемые снимки/записи, без ссылок на `World`, `Entity` или mutable registry.
4. Результат I/O возвращается на server thread. Перед применением проверяются версия записи, generation и актуальность операции.
5. Конструкторы/сервисы получают зависимости явно; не использовать глобальный изменяемый singleton между мирами. Контекст создаётся на экземпляр `MinecraftServer` и очищается при остановке.
6. Командный адаптер разбирает аргументы и выводит результат, но не манипулирует сущностью напрямую. Разрешения проверяются также на границе сервиса.
7. Время поведения считается по серверным тикам: 20 ticks = 1 игровая секунда при 20 TPS. При лаге анимации и задержки удлиняются. Audit timestamps — UTC `Instant`.
8. Никаких `sleep`, файловых записей и ожидания `Future.join()` в игровом тике. Ограниченное ожидание сохранения допускается при штатной остановке.

### 3.2 Структура Java-пакетов

Базовый пакет: `io.github.viewerparrots` — техническое имя проекта, не утверждение о существующем GitHub-аккаунте. Один Gradle-проект с логическими модулями; отдельные Gradle subprojects для MVP не требуются.

```text
io.github.viewerparrots
├── ViewerParrotsMod                 # common entrypoint
├── bootstrap
│   ├── ServerContext
│   └── FabricLifecycleAdapter
├── api
│   ├── ViewerParrotApi
│   ├── CommandRequest
│   ├── CommandResult
│   ├── ActorContext
│   └── event/ParrotEvent
├── command
│   ├── ViewerParrotCommands
│   └── CommandFeedback
├── domain
│   ├── ViewerId
│   ├── Viewer
│   ├── ViewerParrotRecord
│   ├── LifecycleState
│   ├── BehaviorState
│   ├── ParrotTrait
│   └── ParrotVariant
├── service
│   ├── ViewerParrotService
│   ├── ParrotCommandService
│   ├── StreamerService
│   └── PermissionService
├── registry
│   ├── ViewerParrotRegistry
│   └── LoadedEntityIndex
├── entity
│   ├── ViewerParrotEntity
│   ├── ModEntities
│   ├── ParrotEntityController
│   └── SafePositionFinder
├── lifecycle
│   ├── EntityRecoveryService
│   ├── GenerationValidator
│   └── DeathPolicy
├── behavior
│   ├── ParrotStateMachine
│   ├── BehaviorScheduler
│   ├── TraitBehavior
│   ├── CowardBehavior
│   ├── LazyBehavior
│   ├── LoudBehavior
│   └── goal/                    # follow, return, sit, bounded idle
├── ratelimit
│   ├── CooldownService
│   └── GlobalRateLimiter
├── storage
│   ├── ParrotRepository
│   ├── JsonParrotRepository
│   ├── JournalWriter
│   ├── StorageMigrator
│   └── dto/                     # versioned disk schema
├── config
│   ├── ViewerParrotsConfig
│   ├── ConfigLoader
│   └── ConfigValidator
├── diagnostics
│   ├── ErrorCode
│   ├── AuditLogger
│   └── MetricsSnapshot
└── client                       # только client source set
    ├── ViewerParrotsClient
    └── ViewerParrotRenderer
```

`domain` не зависит от Minecraft/Fabric. Minecraft-зависимости разрешены в adapters, entity, bootstrap и реализациях поведения. Storage DTO отделены от runtime-моделей. `api` выдаёт immutable snapshots, а не изменяемые сущности или коллекции.

### 3.3 Fabric и entity adapter

Использовать регистрацию команд через Fabric Command API v2, серверные lifecycle/tick callbacks и события загрузки/выгрузки сущностей. Точные сигнатуры сверять по зафиксированной в M0 Fabric API. Причину удаления обрабатывать отдельно от факта выгрузки: одно событие unload не доказывает смерть.

Реализовать `ViewerParrotEntity extends ParrotEntity`, зарегистрировать собственный тип и атрибуты. Переиспользовать полёт, навигацию, текстуры и модель ванильного попугая, задавая собственный набор goals. В выбранных Yarn mappings `ParrotEntity` содержит методы для NBT, навигации и танцевального состояния; конкретные точки расширения проверяются в M0. Источник: [ParrotEntity, Yarn 1.21.1](https://maven.fabricmc.net/docs/yarn-1.21.1%2Bbuild.3/net/minecraft/entity/passive/ParrotEntity.html).

Для принудительного танца завести tracked boolean `FORCED_DANCING`. Сервер выставляет/снимает его; клиентский renderer использует его для танцевальной позы ванильной модели. Не полагаться на одиночный вызов jukebox-метода, который может быть сброшен следующим тиком. Предпочтение наследованию и своему renderer; access widener/mixin допустим только при подтверждённой необходимости и с тестом dedicated server.

Посадка на плечо, приручение, размножение, поводок и перенос через порталы собственной сущности отключены в MVP: они не должны перемещать попугая в NBT игрока или обходить controller. Кормление не меняет владельца/состояние и не вызывает смерть от печенья. Дроп и XP от custom entity отключены. Межпространственный перенос выполняет только controller.

## 4. Модели данных и инварианты

### 4.1 Viewer и ViewerParrotRecord

| Поле | Тип | Назначение |
|---|---|---|
| `viewer.viewerId` | `ViewerId` / String | Неизменяемый ключ, регистрозависимый; regex `[A-Za-z0-9_.:-]{1,128}` |
| `viewer.displayName` | String | 1–32 Unicode code points после trim; без управляющих символов, line breaks и `§` |
| `viewer.createdAt` | Instant | Время первой регистрации |
| `incarnationId` | UUID | Идентификатор логической регистрации; новый при remove → create того же viewerId |
| `generation` | long ≥ 1 | Версия физического экземпляра; повышается при замене |
| `entityUuid` | UUID? | Ожидаемая физическая сущность; null при смерти/удалении |
| `variant` | enum | `RED_BLUE`, `BLUE`, `GREEN`, `YELLOW_BLUE`, `GRAY`; явный mapper к ванильным вариантам |
| `trait` | enum | `NORMAL`, `COWARD`, `HERO`, `LAZY`, `LOUD`, `THIEF` |
| `desiredBehavior` | enum | Сохраняемое намерение: `IDLE`, `SITTING`, `FOLLOWING` |
| `physicalStatus` | enum | Сохраняемый статус: `PRESENT`, `DEAD`, `MISSING`; `PRESENT` не гарантирует загрузку |
| `lastKnownLocation` | dimension ID, x/y/z | Последняя известная позиция; подсказка для recovery, не доказательство отсутствия |
| `lastSpawnedAt` / `updatedAt` | Instant? / Instant | Диагностика |
| `revision` | long | Версия записи для последовательного применения операций |

`displayName` хранится единожды в Viewer. Повторять его в ParrotRecord не требуется. Разные viewers могут иметь одинаковые имена; команды и подсказки различают их по `viewerId`.

Имя сущности — `Text.literal` после подстановки `{viewer_name}` в шаблон; пользовательский текст не трактуется как JSON text, selector или команда. По умолчанию `{viewer_name}`; символ 🦜 разрешён в шаблоне, но его отображение зависит от шрифта клиента.

### 4.2 Runtime-состояние

`ParrotRuntime` содержит lifecycle, текущий behavior, загруженный UUID, target UUID, время завершения танца, deadline движения, безопасное состояние возврата, отложенную LAZY-команду, cooldown deadlines и счётчики ошибок. Ссылки на Entity удаляются при unload; долгоживущий индекс предпочтительно хранит UUID и dimension.

Runtime-таймеры не сохраняются. После рестарта восстанавливается только `desiredBehavior`; незавершённые `DANCING`, `RETURNING` и LAZY-задания отменяются. Cooldowns после рестарта сбрасываются — это сознательное ограничение MVP с административным управлением.

### 4.3 Маркер в NBT сущности

В namespace `viewerparrots` сохранять `schemaVersion`, `viewerId`, `incarnationId`, `generation`. UUID самой сущности сохраняет Minecraft. Маркер используется для сверки с реестром; entity NBT не создаёт новую регистрацию самостоятельно.

Валидна только сущность, у которой совпадают viewerId, incarnationId, generation и ожидаемый entityUuid. Невалидная собственная сущность не получает AI/команды и удаляется без дропа после успешной загрузки реестра. Обычные попугаи не участвуют в этой проверке.

### 4.4 Обязательные инварианты

- В реестре не более одной живой регистрации на viewerId.
- Не более одной авторизованной загруженной сущности на регистрацию.
- Удалённая registration incarnation никогда не оживает из старого NBT.
- `DEAD` не содержит действующего entityUuid. `UNLOADED` сохраняет ожидаемый UUID и резерв физического слота.
- Ни выгрузка, ни перезапуск сами по себе не создают новый UUID.
- Реестр и world-save не считаются одной атомарной транзакцией; их рассогласование разрешается по правилам разделов 8–9.
- Скрытая в незагруженном чанке старая копия может физически оставаться на диске после замены, но должна быть отвергнута до начала AI при последующей загрузке.

## 5. State machine

### 5.1 LifecycleState

| Состояние | Значение | Допустимые действия |
|---|---|---|
| `UNRESOLVED` | Реестр прочитан, состояние entity ещё не установлено | info, remove, reconcile |
| `LOADED` | Совпавшая сущность доступна | Все игровые действия |
| `UNLOADED` | Известна выгрузка или ожидаемый чанк пока недоступен | info, remove, reconcile, безопасная попытка come |
| `MISSING` | Ограниченная проверка завершена без обнаружения | info, remove, respawn |
| `DEAD` | Подтверждена смерть | info, remove; respawn при разрешённой политике |
| `REMOVED` | Терминальное состояние incarnation | Только диагностика tombstone |

Переходы: startup с `PRESENT` → `UNRESOLVED`; совпадающий entity load → `LOADED`; chunk unload → `UNLOADED`; подтверждённая смерть → `DEAD`; завершённый recovery без совпадения → `MISSING`; remove → `REMOVED`. Для durable `DEAD`/`MISSING` startup сохраняет этот статус. Даже при `MISSING` поздняя загрузка совпадающего текущего UUID допускает bind, если ещё не была зафиксирована замена.

`operationPending` — отдельный флаг, блокирующий конкурирующие мутации во время durable операции. Это не поведение попугая. Таймаут операции не разрешает начать вторую, пока результат первой неизвестен.

### 5.2 BehaviorState при LOADED

| Текущее состояние / событие | Результат |
|---|---|
| Create / respawn | `IDLE`; desiredBehavior = IDLE |
| Любое + `sit` | Отмена движения, танца и LAZY-задания; `SITTING`, desiredBehavior = SITTING |
| Любое + `follow` | `FOLLOWING`, desiredBehavior = FOLLOWING |
| Любое + `come` | `RETURNING`; сохранить прежнее стабильное desiredBehavior |
| `RETURNING` + достигнут радиус 3 блока | Возврат к сохранённому desiredBehavior в новой позиции |
| `RETURNING` + 10 секунд без завершения | Безопасный teleport при разрешении; иначе завершение `PATH_NOT_FOUND`, возврат к desiredBehavior |
| IDLE/SITTING/FOLLOWING + `dance` | `DANCING` на 100 ticks по умолчанию, сохранить прежнее desiredBehavior |
| `DANCING` + таймер | Восстановить desiredBehavior |
| RETURNING или DANCING + `dance` | `INVALID_STATE`; не создавать вложенный стек состояний |
| Любое + `scream` | Звук без изменения состояния, если пройдены лимиты |
| Unload / death / remove | Отменить временные действия; не продолжать их после загрузки |

При замещении действия отправить `CANCELLED` для его commandId. Новый `sit` выполняется немедленно и не задерживается trait. Новые come/follow заменяют предыдущее движение после своей валидации; команда на cooldown ничего не отменяет.

### 5.3 Приоритеты и целевой игрок

Приоритет: lifecycle и запреты → `sit`/явная команда → текущий танец/движение → trait → idle. Trait не перехватывает управление в SITTING, DANCING и RETURNING. Несколько goals не должны одновременно управлять навигацией.

При выходе или смерти стримера FOLLOWING прекращает движение и фактически переходит в IDLE с сохранением desiredBehavior = FOLLOWING. Танец может завершиться на месте; незавершённый come отменяется с `TARGET_UNAVAILABLE`. После появления живого target следование возобновляется. Остальные игроки целью автоматически не становятся.

Смена стримера отменяет временное движение всех попугаев и перенаправляет FOLLOWING. В другом измерении controller может переносить только загруженную entity в безопасную позицию возле живого target, если `crossDimensionTeleport=true`; сохраняет UUID и identity. Если выбранный API переноса создаёт новый Java-объект, обязательно обновить loaded index и проверить сохранение UUID. Обычный портал не является путём миграции.

## 6. Команды и контракт API

Корневая команда `/viewerparrot`, alias `/vp`. Пространственные команды всегда используют назначенного стримера, включая вызов из консоли. Автоматического назначения первого вошедшего игрока нет.

| Команда | Результат / условия |
|---|---|
| `help` | Синтаксис доступных отправителю команд |
| `setstreamer <player>` | Один online player; сохранить UUID в данных мира, обновить цели |
| `streamer` | Показать UUID, имя при доступности и online/alive status |
| `create <viewerId> <displayName>` | Регистрация и спавн в безопасной позиции у target; случайные variant/trait |
| `remove <viewerId>` | Durable tombstone, отмена заданий, удаление загруженной entity; повторный вызов для tombstone — ALREADY_REMOVED |
| `info <viewerId>` | Identity, trait, variant, lifecycle, behavior, ожидаемый UUID, позиция, generation, cooldowns и pending operation |
| `list [page]` | По 20 записей, сортировка viewerId; без полного dump NBT |
| `rename <viewerId> <displayName>` | Сохранить имя; применить к entity сразу или при следующем bind |
| `come <viewerId>` | Подлететь/телепортироваться к target по правилам ниже |
| `follow <viewerId>` | Начать постоянное следование |
| `sit <viewerId>` | Остановиться и сесть; в воздухе прекратить навигацию и безопасно приземлиться |
| `dance <viewerId>` | Танец на текущем месте; автоматический come не выполняется |
| `scream <viewerId>` | Один ванильный звук у текущей позиции entity |
| `respawn <viewerId>` | Проверить текущее состояние; заменить потерянный/мёртвый экземпляр, сохранить identity/trait/variant |
| `trait <viewerId> <trait>` | Административно назначить enum; HERO/THIEF сопровождаются пометкой «без эффекта в MVP» |
| `reconcile <viewerId>` | Ограниченная проверка реестра и ожидаемого чанка, без создания |
| `save` | Запрос durable checkpoint; результат сообщается после завершения |
| `reload` | Валидировать и атомарно применить runtime-конфиг |
| `status` | Количество записей, loaded/reserved/dead/missing, состояние storage, очередь, ошибки и метрики |

Имя — последний greedy string argument, допускающий пробелы; кавычки специально не требуются. ViewerId — один word argument. Число страницы ≥ 1. Подсказки viewerId ограничены 100 совпадениями и доступны только авторизованным отправителям.

Пример сеанса:

```text
/viewerparrot setstreamer Streamer
/viewerparrot create viewer1 Дмитрий
/viewerparrot come viewer1
/viewerparrot sit viewer1
/viewerparrot dance viewer1
/viewerparrot scream viewer1
/viewerparrot info viewer1
```

### 6.1 Движение и безопасная позиция

- `come`: при загруженной entity в том же измерении на расстоянии ≤ 64 блоков использовать ванильную flight navigation. При большей дистанции — teleport, если разрешён.
- `follow`: начать движение дальше 6 блоков, остановить ближе 3; пересчитывать маршрут не чаще раза в 20 ticks на попугая. Дальше 64 блоков использовать ту же teleport policy.
- Для teleport/spawn проверить максимум 32 кандидата в радиусе 2–6 блоков от target: внутри border и высот мира, чанк уже загружен, bounding box свободен, нет жидкости, огня, лавы, кактуса или иной явно опасной опоры. При отсутствии места — `NO_SAFE_POSITION`; не помещать entity внутрь блока.
- Для come из UNLOADED выполнить ограниченный recovery. Не создавать новую entity как способ телепортации. Если исходный экземпляр не найден, вернуть `PARROT_ENTITY_MISSING` или `ENTITY_UNRESOLVED` и предложить администратору respawn.
- Автоматическое сопровождение не загружает удалённые чанки. На выгруженном попугае оно приостанавливается до загрузки или явного recovery.
- Перенос и поиск позиции идут через общую очередь с бюджетом, чтобы 50 попугаев не телепортировались в один тик.

### 6.2 Внутренний Java API

Предусмотреть интерфейсы по следующему контракту; сигнатуры — ориентир для реализации, не готовый исходный код:

```java
interface ViewerParrotApi {
    CompletionStage<CommandResult> submit(CommandRequest request, ActorContext actor);
    Optional<ParrotSnapshot> get(ViewerId viewerId); // server thread only
}

record CommandRequest(UUID commandId, ViewerId viewerId,
                      ParrotAction action, ActionParameters parameters) {}

record CommandResult(UUID commandId, CommandStatus status,
                     ErrorCode code, String messageKey,
                     Map<String, String> details, long retryAfterTicks) {}
```

Для create/rename/trait использовать типизированные `ActionParameters`, не произвольный исполняемый JSON. `ActorContext` создаётся доверенным adapter и содержит source type, UUID отправителя при наличии и проверяемые capabilities. viewerId сам по себе не даёт прав.

Статусы: `SUCCEEDED`, `REJECTED`, `FAILED`, `CANCELLED`. Пока Future не завершён, команда находится в `ACCEPTED` и имеет commandId. Brigadier возвращает 1 при приёме, 0 при синхронном отказе; это не обещание завершённой записи на диск. Последующий feedback содержит commandId и итог. Для come/dance Future завершается после действия; для scream — после отправки звука сервером, без обещания его фактического прослушивания клиентом.

Повторный commandId не запускает действие повторно: вернуть текущий/сохранённый результат в bounded cache на 5 минут, максимум 1024 entries. Дедупликация через рестарт не гарантируется. Обычные повторные create с новым commandId отвергаются по viewerId.

### 6.3 События

`ParrotCreated`, `ParrotRemoved`, `ParrotBound`, `ParrotUnloaded`, `ParrotMissing`, `ParrotDied`, `ParrotRespawned`, `BehaviorChanged`, `CommandAccepted`, `CommandCompleted`, `CommandRejected`.

Общие поля: eventId, commandId при наличии, viewerId, incarnationId, generation, UTC timestamp, serverTick, old/new state, reason. События публикуются на server thread после соответствующего изменения; durable создание/удаление — после commit. Listener не должен блокировать тик. Исключение одного listener логируется и изолируется. Это внутрипроцессные уведомления без гарантии доставки через рестарт.

## 7. Cooldowns и ограничение нагрузки

| Действие | Cooldown по viewerId + action |
|---|---:|
| come | 100 ticks / 5 секунд |
| follow | 20 ticks / 1 секунда |
| sit | 0; повтор в том же состоянии — успешный no-op |
| dance | 200 ticks / 10 секунд |
| scream | 300 ticks / 15 секунд |
| respawn | 200 ticks / 10 секунд |

Порядок: права → аргументы → существование → storage readiness/pending → состояние и target → лимит → cooldown → резерв операции → выполнение. Cooldown расходуется при принятии валидного действия, включая LAZY-отсрочку. Ошибка валидации не расходует его. После принятия отмена или runtime failure не возвращает cooldown. Права оператора не обходят лимиты.

Глобально: до 20 принимаемых игровых действий за 20 ticks, token bucket capacity 20; до 4 звуков за 20 ticks с capacity 4; не больше одного mutation operation и одного LAZY-задания на viewer. Диагностика ограничивается отдельно до 5 запросов на source за 20 ticks, чтобы `status` оставался доступен при нагрузке. `sit` также учитывается в общем лимите.

Очередь изменяющих задач — максимум 256. При переполнении вернуть `SERVER_BUSY`, не создавать неограниченные futures. Административные storage-операции последовательны, одновременно не более одной на viewer. Не поддерживать массовые selectors для игровых действий в MVP.

## 8. Persistence

### 8.1 Где и что хранится

Данные относятся к корню конкретного world save, общему для измерений:

```text
<world-root>/data/viewerparrots/
    registry.json
    registry.json.bak
    journal.jsonl
```

Не хранить registry в общей папке `config`. Настройка target player — часть registry этого мира. Физические entity и их координаты также сохраняются стандартной системой Minecraft; registry хранит последнюю известную позицию как recovery hint.

Пример snapshot schema v1:

```json
{
  "schemaVersion": 1,
  "checkpointSequence": 42,
  "streamerPlayerUuid": "61a2aab0-f5e4-49de-a68b-27b8d524c501",
  "parrots": {
    "viewer1": {
      "viewer": {
        "viewerId": "viewer1",
        "displayName": "Дмитрий",
        "createdAt": "2026-09-12T12:00:00Z"
      },
      "incarnationId": "a1205d81-cad7-4fad-94ca-3b3b147827df",
      "generation": 1,
      "entityUuid": "c5df0bc8-627d-48e1-a14f-a91c75a84565",
      "variant": "BLUE",
      "trait": "LAZY",
      "desiredBehavior": "SITTING",
      "physicalStatus": "PRESENT",
      "lastKnownLocation": {
        "dimension": "minecraft:overworld",
        "x": 10.5,
        "y": 65.0,
        "z": -20.5
      },
      "lastSpawnedAt": "2026-09-12T12:00:00Z",
      "updatedAt": "2026-09-12T12:01:00Z",
      "revision": 3
    }
  },
  "tombstones": []
}
```

### 8.2 Надёжная запись

JSON snapshot сам по себе недостаточен для согласования entity и registry при аварии. Добавить небольшой append-only журнал lifecycle-транзакций; SQLite в MVP не требуется.

- Записи журнала содержат schemaVersion, монотонный sequence, operationId, phase, viewerId, incarnation/generation, полный новый record либо tombstone и checksum. Формат checksum — SHA-256 от канонизированного JSON payload без поля checksum; правила сортировки ключей и UTF-8 фиксируются тестом.
- `PREPARE` durably записывает намерение с новым заранее выбранным entityUuid; `COMMIT` разрешает применение. Каждая подтверждаемая запись проходит flush и `FileChannel.force(true)` до callback на server thread.
- Spawn/замена разрешены только после durable PREPARE. До COMMIT новая entity не выполняет AI и не принимает команды. При провале spawn записать ABORT; при провале COMMIT заморозить/удалить подготовленную entity и перевести мод в storage-degraded mode.
- Remove сначала фиксирует COMMIT tombstone, затем удаляет загруженную entity. Если сервер упал между этими шагами, load validator отвергнет её по tombstone.
- Rename, trait, target и desiredBehavior фиксируются журналом перед финальным `SUCCEEDED`. Короткие анимации и sounds не являются durable операциями.
- Последнюю позицию обновлять в памяти раз в 100 ticks и при unload, coalesce в snapshot. Допустимая потеря этого hint при crash — 5 секунд обычной работы, при задержке I/O больше; корректность generation от него не зависит.
- Checkpoint каждые 30 секунд при dirty, по `save` и при штатной остановке: immutable snapshot → temp в той же директории → flush/force → атомарная замена. Предыдущий корректный snapshot сохранить как `.bak`.
- Если filesystem не поддерживает atomic move, использовать последовательность с валидной backup и сообщением WARN; нельзя сообщать о полной атомарности fallback.
- При compaction сохранять журнал, необходимый для replay от `.bak`, а не только от нового snapshot. Journal sequence ≤ checkpoint пропускается; более новые записи replay в порядке. Нельзя потерять подтверждённые операции при fallback на backup.
- Штатная остановка прекращает приём команд, отменяет transient jobs и ждёт writer до 10 секунд. Неуспех — ERROR с путями восстановления; не утверждать, что save завершился.

### 8.3 Загрузка, crash recovery и повреждения

1. Прочитать и проверить snapshot, затем воспроизвести журнал. До готовности registry custom entities находятся без AI, без изменений identity и без автоматического удаления.
2. Неполная последняя строка журнала может быть отброшена как незафиксированный хвост с WARN. Повреждение середины, разрыв sequence или неверная checksum — storage fault, а не молчаливое пропускание.
3. PREPARE без COMMIT/ABORT после crash откатывается: новая generation не авторизована; загруженная подготовленная entity удаляется. Предыдущая committed запись остаётся действующей.
4. COMMIT со ссылкой на entity, ещё не попавшую в world-save, сохраняет регистрацию, но переводит её через recovery в MISSING; автоматический повторный spawn не выполняется. Это допустимое аварийное рассогласование, исправляемое respawn.
5. При повреждении основного snapshot использовать проверенную backup + достаточный журнал. Если полного восстановления нет, не создавать пустой registry: запретить изменения, остановить AI custom entities, выдать ERROR и инструкцию восстановления из согласованной копии мира и registry.
6. Более новая schemaVersion запрещает запись. Миграция старой схемы — последовательные чистые преобразования на копии с резервным сохранением; никаких destructive downgrade.
7. JSON parsing отвергает дублирующиеся ключи, некорректные UUID, нечисловые/неfinite координаты и неизвестные enum. Нельзя молча сменить trait или viewerId.

Tombstone содержит viewerId, incarnationId, последнюю generation и removedAt. Tombstones не очищать автоматически в MVP: старые чанки могут оставаться выгруженными долго. Повторное create получает новую incarnation, поэтому старый экземпляр не привяжется к новой регистрации.

## 9. Entity lifecycle и recovery

### 9.1 Create

Проверить target, отсутствие viewerId, свободный reserved slot и безопасную позицию. Назначить incarnation, generation = 1, UUID, variant и trait один раз. Зарезервировать слот, выполнить PREPARE, создать entity с маркером, завершить COMMIT, затем включить AI и сообщить успех. Перед фактическим spawn после I/O ещё раз проверить target/позицию. При отказе освободить резерв и записать ABORT; незавершённая регистрация не считается успешной.

### 9.2 Загрузка и выгрузка

На server entity load валидировать маркер до активного поведения, затем bind и восстановить имя, вариант, desiredBehavior. Обновить индекс viewerId → UUID и UUID → viewerId. На chunk unload удалить runtime-ссылку, сохранить hint и перейти в UNLOADED; слот остаётся занятым. Не вызывать death handler на unload, shutdown или переносе измерения.

При первом запуске обработать уже загруженные custom entities один раз, затем работать по событиям. Полное сканирование мира или всех чанков каждый тик запрещено.

### 9.3 Ограниченный поиск

`reconcile` и `respawn` сначала проверяют loaded index во всех измерениях. Если текущая entity найдена, bind; respawn возвращает `ENTITY_ALREADY_LOADED` без замены.

При недоступном expected chunk разрешена временная загрузка только существующего чанка последней известной позиции, без генерации территории. Максимум 2 recovery jobs глобально, 1 чанк на job, deadline 100 ticks; ticket обязательно освободить при любом исходе. Считать поиск завершённым только после готовности entity-loading для чанка, не просто наличия блоков.

Не искать бесконечно в соседних регионах и не читать/редактировать `.mca` вручную. По deadline вернуть `RECOVERY_TIMEOUT`, состояние UNRESOLVED, без spawn. Если проверка закончена, но entity не найдена, установить MISSING с причиной `NOT_FOUND_IN_KNOWN_LOCATION`. Это локальное свидетельство, не доказательство отсутствия во всём мире.

### 9.4 Respawn и поздняя старая entity

Respawn допускается для DEAD в режиме MANUAL_RESPAWN/IMMORTAL и для MISSING. Для неизвестного viewerId — отказ; для UNRESOLVED/UNLOADED сначала завершить поиск. В VANILLA_FINAL смерть запрещает respawn.

Повысить generation, выделить новый UUID и выполнить lifecycle-транзакцию. Сохранить viewerId, incarnationId, createdAt, имя, trait и variant; desiredBehavior сбросить в IDLE. После COMMIT предыдущий UUID/generation отозван навсегда, даже если его чанк не был проверен. При поздней загрузке старой entity удалить её до AI. Так отсутствие глобального сканирования не приводит к двум действующим попугаям.

Если после commit world-save потерял новую entity, запись остаётся MISSING с новой generation. Следующая явная попытка respawn создаёт следующую generation, не переиспользует отозванную.

### 9.5 Remove и ёмкость

Remove поддерживается в любом lifecycle. После durable tombstone загруженный экземпляр удаляется без loot; выгруженный будет отвергнут при загрузке. Отложенные команды отменяются. Tombstone хранится отдельно, `info` сообщает REMOVED; повторный remove успешен как no-op.

`maxActiveParrots=50` ограничивает **reserved instances**: committed PRESENT плюс незавершённые spawn reservations. UNLOADED/UNRESOLVED и MISSING с ожидаемым UUID продолжают занимать слот, поскольку старая entity может существовать. DEAD и REMOVED слот освобождают. Замена MISSING использует его существующий слот; respawn DEAD требует свободного. Уменьшение лимита ниже текущего числа ничего не удаляет, но запрещает новый spawn до освобождения ёмкости.

Модель допускает много логических записей, однако массовая активация/деактивация и ротация 10 000 зрителей не входят в MVP. Число 10 000 в нагрузочном тесте относится к registry, а не к одновременному спавну.

### 9.6 Смерть

| `deathMode` | Поведение |
|---|---|
| `IMMORTAL` — default | Блокировать обычный урон до уменьшения здоровья; не гарантировать защиту от `/kill`, void/removal и других модов; при подтверждённой смерти сохранить DEAD |
| `MANUAL_RESPAWN` | Ванильный урон, DEAD и entityUuid = null, восстановление только командой |
| `VANILLA_FINAL` | Ванильный урон, DEAD, запрет respawn; администратор может явно remove/create новую регистрацию |

Смерть записывается через journal как факт, который нельзя отменить из-за I/O. При сбое storage заморозить прочих custom entities, запретить мутации и сообщить fault; при следующей загрузке старый registry допускает MISSING. Автоматического бесконечного respawn нет ни в одном режиме.

## 10. Personality traits и idle behavior

Trait выбирается один раз из enabled weighted distribution и сохраняется. Default weights: NORMAL 40, COWARD 20, LAZY 20, LOUD 20, HERO 0, THIEF 0. Изменение weights не перераспределяет существующих попугаев. Для тестов генератор случайности внедряется как зависимость.

| Trait | MVP-поведение |
|---|---|
| NORMAL | Только базовый idle |
| COWARD | Раз в 20 ticks искать ближайшую hostile entity в радиусе 8; при IDLE пытаться отлететь от неё к безопасной точке максимум на 4 блока, действие ≤ 40 ticks |
| LAZY | Come и follow задерживаются на случайные 20–100 ticks включительно; не игнорируются; sit/remove/respawn никогда не задерживаются |
| LOUD | На idle opportunity шанс имитации 50%, у остальных 10%; общий sound cooldown и global limit обязательны |
| HERO | Сохраняемый trait без эффекта; приближение к hostile отложено |
| THIEF | Сохраняемый trait без эффекта; предметы не подбираются |

Для COWARD hostile predicate задаётся adapter по классу/тегу подходящих враждебных мобов базы 1.21.1; игроки не считаются угрозами. Проверять только локальный AABB, максимум 16 кандидатов на проверку. При отсутствии безопасного направления остаться на месте. Возобновление idle после окончания манёвра; в FOLLOWING манёвр MVP не запускается.

LAZY: одна pending action на viewer. После задержки повторить state/target/generation validation, но не списывать cooldown второй раз. Новый успешно принятый come/follow заменяет pending action, sit отменяет её. Изменение trait, unload, death, remove и рестарт отменяют pending action. Отправитель получает сообщение о задержке и её итоговый результат.

Idle opportunity каждые 600–2400 ticks с независимым jitter на попугая. Только в IDLE: имитация с указанной вероятностью; иначе равновероятно посмотреть на ближайшего игрока или сделать короткий перелёт ≤ 3 блоков и ≤ 40 ticks. Не менять desiredBehavior. Ванильные ambient/imitation вызовы custom entity должны быть отключены либо направлены через общий limiter, иначе LOUD и лимиты не будут контролируемыми.

Scream и автономные имитации используют один per-viewer sound deadline. Если global sound budget занят, command scream отвергается с retryAfter, idle sound пропускается без очереди. Звук отправляется сервером один раз в координатах попугая; клиент не дублирует его локально. Громкость default 0.8, pitch 0.9–1.1. Для короткого звука не требуется непрерывное перемещение источника за летящей entity.

## 11. Конфигурация

Файл: `<game-dir>/config/viewerparrots.json`. Отсутствующий файл создаётся из defaults. Ошибка reload сохраняет прежний конфиг целиком; частичное применение запрещено. При некорректном initial config запуск функциональности мода блокируется с понятной диагностикой; файл не перезаписывается молча.

```json
{
  "schemaVersion": 1,
  "maxActiveParrots": 50,
  "deathMode": "IMMORTAL",
  "nameFormat": "{viewer_name}",
  "nameVisible": true,
  "permissions": { "controlLevel": 2, "adminLevel": 3 },
  "come": {
    "maxPathfindDistance": 64,
    "arrivalRadius": 3,
    "timeoutTicks": 200,
    "teleportIfFar": true,
    "teleportOnPathFailure": true,
    "crossDimensionTeleport": true
  },
  "follow": { "startDistance": 6, "stopDistance": 3, "repathTicks": 20 },
  "dance": { "durationTicks": 100 },
  "cooldowns": { "come": 100, "follow": 20, "sit": 0, "dance": 200, "scream": 300, "respawn": 200 },
  "sound": {
    "volume": 0.8,
    "minPitch": 0.9,
    "maxPitch": 1.1,
    "allowedSounds": ["minecraft:entity.zombie.ambient", "minecraft:entity.skeleton.ambient", "minecraft:entity.creeper.primed"]
  },
  "behavior": {
    "enabled": true,
    "idleMinTicks": 600,
    "idleMaxTicks": 2400,
    "dangerCheckTicks": 20,
    "dangerRadius": 8,
    "lazyMinTicks": 20,
    "lazyMaxTicks": 100,
    "normalSoundChance": 0.1,
    "loudSoundChance": 0.5,
    "traitWeights": { "NORMAL": 40, "COWARD": 20, "LAZY": 20, "LOUD": 20, "HERO": 0, "THIEF": 0 }
  },
  "limits": { "actionsPer20Ticks": 20, "soundsPer20Ticks": 4, "queueCapacity": 256 },
  "recovery": { "maxConcurrentJobs": 2, "timeoutTicks": 100 },
  "storage": { "checkpointIntervalTicks": 600, "shutdownTimeoutSeconds": 10 }
}
```

Валидация: maxActiveParrots 1–200; permission levels 0–4 с adminLevel ≥ controlLevel; distances положительные и ≤ 128, stop < start; интервалы ≥ 1 кроме cooldown ≥ 0; min ≤ max; вероятности [0,1]; volume [0,1], pitch [0.5,2]; сумма weights > 0, HERO/THIEF weights = 0 в MVP; sound IDs существуют в registry выбранной игры; sound list непустой и ≤ 32; очередь 1–1024; recovery jobs 1–2. Неизвестные поля — ошибка с JSON path, чтобы опечатки не игнорировались.

Reload применяется к новым действиям. Текущие deadlines танца, cooldown и LAZY остаются прежними. `behavior.enabled=false` отменяет автономные действия и LAZY-отсрочки, но команды продолжают работать без trait effects. Изменение permissions/deathMode применяется сразу; nameFormat обновляется у loaded entities порциями ≤ 10 за tick, у остальных при bind. Capacity очереди, recovery jobs и storage-параметры требуют рестарта: reload с их изменением отвергается целиком с `CONFIG_RESTART_REQUIRED`.

## 12. Permissions и граница доверия

По умолчанию controlLevel = 2: help, streamer, info, list, come, follow, sit, dance, scream. AdminLevel = 3: create, remove, respawn, rename, trait, setstreamer, reconcile, save, reload, status. Это уточнение исходного требования «operator level ≥ 2». Консоль использует свои vanilla-права; RCON/command blocks подчиняются permission source, отдельный обход отсутствует.

Root и дочерние Brigadier nodes фильтруются по правам; сервис повторно проверяет capabilities. Level 0 не имеет доступа по умолчанию. UUID стримера не предоставляет operator permissions автоматически. Административный уровень remove является достаточным подтверждением, отдельный интерактивный диалог не нужен.

Не принимать команды от произвольных клиентских custom packets. Используются стандартные серверные команды Minecraft. Сервер не доверяет присланному клиентом trait, entity UUID или флагу танца. Права сторонних Java-модов внутри того же процесса не являются security sandbox; PermissionService предотвращает случайный обход в официальных adapters.

## 13. Logging и error model

Использовать SLF4J, logger `viewerparrots`. Поля: `event`, `commandId`, `viewerId`, `incarnationId`, `generation`, `entityUuid`, `action`, `status`, `reason`, `durationMs`. Имена и прочие пользовательские строки экранировать от переноса строк и ограничивать по длине.

| Уровень | События |
|---|---|
| INFO | Startup/config summary, create/remove/respawn, смена стримера, save/recovery итог |
| DEBUG | Обычные команды, cooldown rejection, behavior transitions, bind/unload |
| WARN | Missing/stale entity, восстановление backup, recovery timeout, изолированная ошибка behavior |
| ERROR | Storage fault, невозможность миграции, неожиданная ошибка сервиса |

Повторяющиеся WARN объединять по viewerId + reason, максимум одна запись за 60 секунд с количеством повторов. Не писать состояние каждого попугая каждый tick. `status` показывает loaded/reserved/registered, queue depth, recovery jobs, dirty age, checkpoint sequence, storage health, command rejected counts и время scheduler.

Ожидаемые ошибки возвращать как ErrorCode + локализуемый messageKey + details, без exception в бизнес-ветке:

| Код | Значение |
|---|---|
| `PERMISSION_DENIED`, `INVALID_ARGUMENT` | Нет прав / недопустимые аргументы |
| `VIEWER_NOT_FOUND`, `PARROT_ALREADY_EXISTS` | Неизвестный viewer / повторная регистрация |
| `TARGET_NOT_CONFIGURED`, `TARGET_UNAVAILABLE` | Нет назначенного/живого online target |
| `PARROT_ENTITY_MISSING`, `ENTITY_UNRESOLVED`, `ENTITY_ALREADY_LOADED` | Состояние физической entity не подходит |
| `INVALID_STATE`, `RESPAWN_DISABLED` | Недопустимое действие или death policy |
| `COMMAND_ON_COOLDOWN`, `GLOBAL_RATE_LIMITED` | Возвратить retryAfterTicks |
| `MAX_ACTIVE_PARROTS_REACHED`, `SERVER_BUSY`, `OPERATION_PENDING` | Capacity / bounded queue / ongoing mutation |
| `NO_SAFE_POSITION`, `PATH_NOT_FOUND`, `DIMENSION_UNAVAILABLE`, `RECOVERY_TIMEOUT` | Ошибки движения и поиска |
| `STORAGE_UNAVAILABLE`, `CONFIG_INVALID`, `CONFIG_RESTART_REQUIRED` | Данные или конфигурация недоступны |
| `INTERNAL_ERROR` | Неожиданная ошибка; пользователю только commandId, stack trace в log |

Пример feedback: `COMMAND_ON_COOLDOWN: scream для viewer1 доступен через 12 с.` Секунды округляются вверх из ticks. Локализации сообщений — `ru_ru` и `en_us`; консоли доступен читаемый fallback.

Ошибка trait не должна останавливать других попугаев. После трёх последовательных ошибок отключить автономное поведение конкретного viewer до reload/restart, оставить IDLE или явные команды и показать fault в info. Ошибки persistence не маскируются этим механизмом: они переводят мод в общий режим с запретом изменений и остановкой AI custom entities.

## 14. Требования к производительности

Показатели ниже — **цели приёмки**, а не измеренные свойства ещё не написанного мода.

| Метрика | Целевое значение |
|---|---|
| Нагрузка | 50 loaded custom parrots возле стримера, simulation distance 8 |
| TPS | ≥ 19.5 на контрольном сервере при baseline ≥ 19.8 |
| Добавка p95 времени server tick к baseline | ≤ 5 ms в сопоставимом сценарии |
| Собственный scheduler без ванильной навигации | p95 ≤ 1 ms/tick |
| Начало простого принятого действия | ≤ 2 ticks после необходимых I/O callback, кроме LAZY и задач в пределах заявленного бюджета |
| Durable CRUD | p95 ≤ 1 секунды на локальном SSD в штатной нагрузке |
| Registry 10 000 записей без loaded entities | Загрузка ≤ 3 секунд, добавочная heap ≤ 64 MiB |
| Recovery | ≤ 100 ticks на job либо явный timeout |

Контрольная среда: dedicated server, Java 21, 4 GiB heap, локальный SSD, выделенные 4 CPU cores без фоновой нагрузки; фактические CPU/OS/JDK фиксируются в отчёте. Сравнить идентичный мир без попугаев, с 50 ванильными попугаями и с 50 custom parrots. После 5 минут прогрева измерять по 10 минут, три прогона каждого сценария; записывать TPS, tick p50/p95/p99, GC, heap и таймеры scheduler. Если baseline не выдерживает условия, стенд не даёт результата приёмки и должен быть приведён к условиям.

Обязательные ограничения реализации:

- Индексы по viewerId и UUID; O(1) lookup в обычной команде.
- Не обходить 10 000 dormant записей каждый tick.
- Разнести danger checks по фазам; не более 5 проверок за tick.
- Не более 2 новых path calculations за tick глобально и одной на viewer за 20 ticks. Очередь fair round-robin; запоздалые и неактуальные задания отменять.
- Не более 2 teleport/spawn за tick; recovery tickets только с deadline.
- Snapshot строится из immutable/copy-on-write записей; большой snapshot собирать порциями, не удерживая игровой поток на весь JSON encode.
- Клиенту синхронизировать только данные нужных tracked entities; не отправлять весь registry. Имя/variant/dance — по изменению, движение — штатный tracking Minecraft.
- После 100 циклов load/unload число runtime references и tickets возвращается к исходному уровню; heap не показывает монотонного роста после GC.

## 15. Тестовая стратегия

### 15.1 Unit tests — JUnit 5

- ViewerId/name validation, одинаковые имена разных viewers, регистрация и incarnation после remove/create.
- Табличные переходы обеих state machines; dance restore; прерывание sit; target disconnect.
- Cooldown границы tick = deadline − 1 / deadline, global buckets, rejected action без списания.
- LAZY с fake clock и seeded RNG: delay bounds, cancellation, повторная валидация, отсутствие двойного cooldown.
- Traits: веса, disabled behavior, COWARD bounds, LOUD probability через контролируемый RNG без flaky Monte Carlo assertion.
- JSON round-trip, duplicate keys, schema validation, migration, journal replay, generation/tombstone arbitration.
- Capacity учитывает UNLOADED/MISSING и освобождается только по правилам; конкурирующие create не превышают лимит.

### 15.2 Integration / Fabric GameTest

На зафиксированном Fabric GameTest окружении: create/bind/remove, NBT round-trip, отсутствие влияния на vanilla parrots, loaded index, движение по подготовленной открытой площадке, teleport collision checks, death modes, damage, shoulder/feeding/portal restrictions. Проверки визуального renderer не подменять GameTest assertion серверного boolean.

Отдельный process harness запускает настоящий dedicated server и проверяет restart, остановку между PREPARE/spawn/COMMIT/world-save, chunk unload/load, journal truncation, read-only storage и disk-write failure. GameTest в одном процессе недостаточен для доказательства crash recovery.

Матрица аварий обязательна: до PREPARE; после PREPARE; после spawn до COMMIT; после COMMIT до world-save; после remove COMMIT до удаления; во время checkpoint; при повреждённом snapshot с backup; при повреждённом journal middle. Для каждого сценария проверить committed identity, отсутствие двух активных экземпляров и понятный статус MISSING/degraded вместо молчаливой потери реестра.

### 15.3 Ручная клиентская приёмка

Два Fabric-клиента у dedicated server и отдельный integrated server: имя/вариант, танец без jukebox, вход второго клиента во время танца, завершение анимации, positional scream без двойного звука, движение, смена измерения, выход/вход target. Проверить клиента без мода: несовместимое подключение отклоняется, не приводит к падению сервера.

### 15.4 CI и артефакты

CI выполняет compile/build, unit tests, GameTests и dedicated server startup smoke test. Release gate дополнительно требует process crash tests, ручной клиентский чек-лист и нагрузочный отчёт. Поставляются JAR, исходники с Wrapper, README по установке/backup/recovery, пример config и результаты проверки. Отчёт не должен называть ручные сценарии пройденными без фактического выполнения.

## 16. Acceptance criteria

| ID | Сценарий | Проверяемый результат |
|---|---|---|
| AC-01 | Сборка и запуск базовой матрицы | JAR запускается на dedicated и integrated server, нет client-class loading crash |
| AC-02 | setstreamer + create viewer1 Дмитрий | Одна entity в безопасной позиции, видимое имя, сохранённые UUID/trait/variant |
| AC-03 | Повторный create viewer1 | PARROT_ALREADY_EXISTS, число entities и запись не меняются |
| AC-04 | Два viewerId с одинаковым именем | Две независимые регистрации, адресуются по ID |
| AC-05 | Come с 16 блоков на открытой площадке | Достижение радиуса 3 за ≤ 200 ticks после LAZY; UUID сохраняется |
| AC-06 | Come с > 64 блоков / без безопасной позиции | Teleport при разрешении; иначе явная ошибка без спавна копии |
| AC-07 | Follow → sit → движение стримера | Попугай следует, после sit прекращает следование; LAZY не задерживает sit |
| AC-08 | Dance из SITTING и FOLLOWING | Оба клиента видят 100 ticks танца без jukebox; затем восстановлено исходное намерение |
| AC-09 | Второй клиент начинает tracking во время танца | Видит текущую анимацию и её своевременное завершение |
| AC-10 | Scream и повтор до deadline | Один разрешённый звук из позиции entity; повтор отвергнут, без двойного клиентского playback |
| AC-11 | Штатный restart | Те же viewerId/incarnation/generation/entityUuid, trait, имя и variant; bind без respawn |
| AC-12 | Chunk unload/load | UNLOADED → LOADED, UUID прежний, reserved capacity не менялась |
| AC-13 | Missing → respawn → загрузка старого чанка | Новая generation и UUID, traits сохранены, старая entity отвергнута до AI |
| AC-14 | Remove в UNLOADED, затем загрузка | Попугай не возвращается; tombstone действует |
| AC-15 | Remove/create того же viewerId | Новая incarnation; старая entity не может привязаться |
| AC-16 | Три death modes | Обычный урон блокируется в IMMORTAL; DEAD сохраняется в других; VANILLA_FINAL запрещает respawn |
| AC-17 | Неоператор и control-level operator | Первый не управляет; второй не выполняет admin-команды; сервис не обходит проверку |
| AC-18 | 51-й reserved instance при default limit | MAX_ACTIVE_PARROTS_REACHED, без частичной записи/spawn |
| AC-19 | LAZY, COWARD, LOUD | Поведение соответствует таблице, воспроизводимо в тестах; HERO/THIEF отмечены как неактивные |
| AC-20 | Повреждение/ошибка storage и crash matrix | Нет пустого registry вместо данных, подтверждённые операции replay; нет двух авторизованных entities |
| AC-21 | Ошибка reload | Старый config продолжает действовать целиком, показан путь ошибки |
| AC-22 | Смерть/выход/смена измерения target | Нет NPE и случайного другого target; follow/come соблюдают state и transfer policy |
| AC-23 | Нагрузочный стенд раздела 14 | Достигнуты цели TPS/tick/memory, приложены фактические параметры и результаты |
| AC-24 | 100 циклов chunk lifecycle | Нет утечки ссылок, recovery tickets или неконтролируемого роста очередей |
| AC-25 | Scope | Нет внешних сервисов, TTS, произвольных аудиофайлов, экономики и подбора предметов |

MVP принимается после выполнения всех AC. При замене проектного решения необходимо обновить связанные контракты, тесты и criteria; нельзя просто исключить неудобный сценарий из отчёта.

## 17. Roadmap реализации

| Этап | Работы | Выходной результат / gate |
|---|---|---|
| M0 — проверка Fabric-базы | Зафиксировать версии; создать мод, custom parrot, renderer и tracked dance; проверить server/client split, tracking позднего клиента и UUID при переносе | Воспроизводимая сборка и запуск; доказан танец без jukebox; уточнены API adapters |
| M1 — domain и storage | Модели, registry, permissions, config, JSON schema/journal, deterministic clocks, state machines | Unit tests identity/state/replay; работающий checkpoint и fault mode |
| M2 — lifecycle core | Create/remove/info/list/setstreamer/rename/respawn/reconcile; NBT marker, generations, tombstones, unload/recovery/death | AC-02–04, AC-11–18 и crash matrix; отсутствие duplicate active entity |
| M3 — управление | Come/follow/sit/dance/scream, cooldowns, safe positions, target lifecycle, API/events/feedback | Два клиента проходят AC-05–10 и AC-22; права и лимиты проверены |
| M4 — характер | NORMAL/COWARD/LAZY/LOUD, idle scheduler, административный trait; HERO/THIEF только данные | AC-19, отмена pending actions, изоляция ошибок поведения |
| M5 — завершение MVP | Нагрузка, совместимость dedicated/integrated, локализация, README, backup/recovery instructions, CI и release build | Все AC, отчёт тестов и готовый JAR |

Зависимости: M1 после M0; M2 после M1; M3 после lifecycle core; M4 после scheduler/command contracts; M5 после всех функциональных этапов. Оценка календарных сроков выполняется после M0, когда проверены версия API, renderer и восстановление entity.

После MVP отдельным решением можно рассматривать HERO, THIEF, активацию/деактивацию большого реестра и внешние adapters. Эти возможности не входят в приёмку и не требуют сейчас HTTP-сервера или пустых интеграционных модулей.

## 18. Источники и порядок принятия решений

- Продуктовые требования: предоставленное обсуждение «Идеи программ для стримера», последнее функциональное ТЗ без TTS и текущий запрос на Fabric.
- [Fabric для Minecraft 1.21/1.21.1](https://www.fabricmc.net/2024/05/31/121.html) — сведения о выбранной ветке.
- [Fabric: настройка среды разработки](https://wiki.fabricmc.net/tutorial:setup) — Java и базовая сборочная среда.
- [Yarn 1.21.1: ParrotEntity](https://maven.fabricmc.net/docs/yarn-1.21.1%2Bbuild.3/net/minecraft/entity/passive/ParrotEntity.html) — именование и поверхность класса в целевых mappings.

Платформенные источники проверены 12 сентября 2026 года. Остальная архитектура, схемы, defaults и performance gates — проектные решения этого ТЗ, а не гарантии Fabric. При расхождении документации новой версии с API 1.21.1 приоритет имеют зафиксированные зависимости и их исходники. Перед переходом к другой версии Minecraft пересматриваются adapters, renderer, persistence hooks и compatibility matrix.
