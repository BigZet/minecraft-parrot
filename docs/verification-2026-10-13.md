> ИСТОРИЧЕСКИЙ ДОКУМЕНТ. С 14 октября 2026 года действует ТЗ 2.0: Twitch-чат и одиночная игра. Серверные и нагрузочные gates ниже больше не являются требованиями; незавершённые пункты старого плана не продолжаются автоматически.

# Проверка Viewer Parrots 1.0.0 — 13–14 октября 2026

Состояние доказательств на **00:49 MSK 14 октября (UTC+03:00)**. Проверенная сборка с SHA-256 `9810CD0F…FC303` прошла clean build, 328 unit tests, 19 Minecraft GameTests, 11 сценариев аварии/восстановления, точные disk lifecycle AC-13/14, 10 000 записей и официальный integrated-client прогон. Все девять полных нагрузочных окон этой сборки завершились успешно: [verdict](../artifacts/final-benchmark/20261013-2220/full/benchmark-verdict.json), [штатное сохранение и остановка](../artifacts/final-benchmark/20261013-2220/full/shutdown-receipt.json). Заключительная сверка выявила недостающие обязательства §6/7/8/11/13: структурированные логи, единый путь CLI/API, ограничения диагностики, запрет позднего SAVE и применение deathMode к отложенному respawn. Их доработка выполняется сейчас; результаты предыдущей сборки ниже не являются доказательством новых production bytes. Обновлённая сборка, её проверка и упаковка ещё **PENDING**. Трассировка отдельных требований: [прежняя матрица приёмки](acceptance-matrix-legacy-2026-10-13.md).

Результаты ниже прочитаны из логов, XML, JSON и фактических JAR в рабочем стенде. Предварительные прогоны отделены от проверок конечной сборки; файл с названием `final` сам по себе не определяет актуальность результата.

## Конечная сборка и среда

| Параметр | Зафиксированное значение |
|---|---|
| Мод / Minecraft / Yarn | `1.0.0` / `1.21.1` / `1.21.1+build.3` |
| Fabric Loader / API | `0.19.5` / `0.116.17+1.21.1` |
| JDK серверных проверок | Eclipse Temurin `21.0.12.1+1-LTS` |
| Loom / Gradle Wrapper | `1.17.20` / `9.5.1` |
| ОС / CPU | Windows 11 Pro `10.0.26200`; Intel Core i5-11400F, 6 физических ядер / 12 логических процессоров |
| Диск | Локальный NVMe ADATA SX8200PNP |
| Формальный стенд §14 / 10k | `-Xmx4G`, 4 физических ядра, affinity `0xFF`; 8 SMT-потоков |
| Simulation / view distance нагрузочного сервера | `8` / `8` |
| Адрес свежего нагрузочного сервера | `127.0.0.1:25591`, отдельный QA-мир, offline mode для QA-игроков |
| Release JAR | `build/libs/viewerparrots-1.0.0.jar`, 374 270 bytes |
| SHA-256 release JAR | `9810CD0FDA61BA11C89CF1A5CAC57493FEF3A938331C1D0BF089EA3BB6FFC303` |
| SHA-256 дерева main `.class` замороженного QA runtime | `108600CF6A7ACC310B5EAF0DAD649D92C602964DAC4731D60B9E693B10338441` |
| Момент фиксации QA runtime | `2026-10-13T20:45:12+03:00`, 166 main class files |

Идентичность production classes записана в [manifest.json](../artifacts/qa-runtime/20261013-204512/manifest.json); [свежий QA runtime manifest](../artifacts/final-benchmark/20261013-2220/runtime/manifest.json) подтверждает совпадение всех 166 main classes после QA-only изменения арены. Параметры машины сохранены в [hardware-final-planned.json](../artifacts/verification/20261013/hardware-final-planned.json), фактическая affinity нового server PID 69200 — в [full/hardware-affinity.json](../artifacts/final-benchmark/20261013-2220/full/hardware-affinity.json). Четыре физических ядра определены через SMT-пары `0x3`, `0xC`, `0x30`, `0xC0`, а не через количество, возвращаемое `availableProcessors`. Остальные выделенные QA-процессы используют маску `0xF00`. Это разделение CPU не устраняет работу ОС и общий доступ процессов к диску и памяти.

## Clean build и функциональные тесты

| Проверка | Конечный результат | Доказательство |
|---|---|---|
| Clean build | `BUILD SUCCESSFUL in 39s`, завершение 20:44 MSK | [final-clean-build.log](../artifacts/verification/20261013/final/final-clean-build.log) |
| Unit tests | 328 tests в 28 suites; 0 failures, 0 errors | [JUnit XML](../build/test-results/test/), [HTML report](../build/reports/tests/test/index.html) |
| Minecraft GameTests | 19 cases; 0 failures, 0 errors | [final/gametest-results.xml](../artifacts/verification/20261013/final/gametest-results.xml), timestamp `2026-10-13T17:44:21.851396600Z`; лог сообщает `All 19 required tests passed` |
| Содержимое release JAR | 203 ZIP entries; 0 QA/GameTest entries | Фактическое чтение `build/libs/viewerparrots-1.0.0.jar`; тестовый source set остаётся вне release JAR |

GameTests проверяют живой Minecraft lifecycle, конкурентные CRUD и предел 50 резервов, permissions/полный command ID/events, реентерабельные listeners, trait scheduler, movement/target/death modes, ванильные ограничения и безопасные позиции. Управляемые writer gates воспроизводят гонки spawn → COMMIT, позднюю привязку старой generation, смерть во время изменения атрибутов и переход в `DEGRADED`. Отдельные bootstrap/reload/shutdown случаи проверяют продолжение server ticks во время I/O, отложенную проверку сохранённой identity, атомарную публикацию конфигурации, отсутствие публикации после остановки и запрет нового ingress во время shutdown callbacks.

Асинхронные тесты проверяют рабочие потоки отдельно от тестовых helpers, которые ограниченно прокачивают очередь server callbacks ради подготовки fixture. В production bootstrap/config/reload I/O выполняется на `viewerparrots-storage`; установка registry/config и работа с entity остаются на server thread. Реальный dedicated-server профиль ниже дополнительно подтверждает потоки загрузки и установки registry.

## Реальная выгрузка чанков и recovery: 100 циклов

[recovery-100-cycles.json](../build/reports/verification/recovery-100-cycles.json), созданный в **20:44:32 MSK**, содержит `passed=true`, **100 завершённых recovery cycles** и **202 reclaimed entity objects**. Сценарий сохраняет entities, реально выгружает чанки и загружает их через публичные recovery jobs с дисковым entity-loading. Проверяются исходные UUID, отсутствие подмены generation, bounded jobs/deadline и освобождение tickets. Отдельный live case проверяет, что отмена одного из двух jobs одного чанка сохраняет ticket другого.

После очистки значения совпадают с нулевым baseline: runtime/index/UUID references, recovery jobs и tickets, fixture tickets, writer pending/queued, операции, spawn continuations, pending/active commands, name refresh, reservations, listeners и fixture registry records — **0**. Weak references подтверждают освобождение всех 202 старых Java-объектов после GC.

Heap измеряется после завершённого explicit GC в одинаковой фазе выгруженных зарегистрированных records. Первые 10 циклов прогревают кэши; затем используются 3 calibration GC и 10 измерений до цикла 100. Удержанный рост **204 496 bytes** ниже fixture-предела **262 144 bytes**; `postGcTrend.passed=true`, calibration stable. Допуск шума — 16 384 bytes. Это проверка lifecycle leak и роста whole-JVM live heap; GameTest JVM имеет max heap около 16 GiB, поэтому её результаты не являются нагрузочным измерением на 4 GiB.

Чувствительность проверена отдельным намеренно отрицательным прогоном: **19 cases, ровно 1 ожидаемый failure** — удержание одного старого entity object, `retained=1`. [Negative XML](../artifacts/acceptance-storage/20261013-async-io/gametest-leak-negative.xml). Финальный clean build выполнен с отключённым leak probe.

## 11 сценариев аварии и восстановления целого Minecraft process

[process-matrix-results.json](../artifacts/process-crash-final-20261013-2048/process-matrix-results.json) сообщает **11/11 passed**, **24 dedicated JVM launches** и **6 abrupt exits с кодом 77**. Прогон: **20:48:06–21:00:17 MSK**. Матрица использует конечное дерево main classes с hash выше, изолированные Minecraft worlds и реальный restart; QA-only gates вызывают остановку процесса в выбранной точке.

| Сценарий | Подтверждённый результат после restart |
|---|---|
| `BEFORE_PREPARE` | Незарегистрированная операция не создаёт логическую или физическую копию |
| `AFTER_PREPARE` | PREPARE восстанавливается без самовольной замены identity |
| `AFTER_SPAWN_BEFORE_COMMIT` | Сохранённая prepared entity проверяется по durable identity |
| `AFTER_COMMIT_BEFORE_WORLD_SAVE` | COMMIT сохраняет ожидаемый UUID; несохранённая entity не подменяется новой копией |
| `AFTER_REMOVE_COMMIT_BEFORE_DISCARD` | Tombstone отвергает ещё сохранённую физическую entity |
| `DURING_CHECKPOINT` | Halt после force временного snapshot, до backup/atomic rename; после restart сохранённый ожидаемый UUID привязан ровно к одной entity |
| `CORRUPT_SNAPSHOT_WITH_BACKUP` | Malformed UTF-8 primary вызывает fallback на валидный backup + journal; backup сохранён |
| `CORRUPT_JOURNAL_MIDDLE` | Полная строка с повреждённой checksum вызывает `DEGRADED`; файлы не меняются до ремонта, после ремонта restart успешен |
| `JOURNAL_TRUNCATION` | Синтаксически незавершённый хвост удалён физически; подтверждённые записи и backup сохранены |
| `READ_ONLY_STORAGE` | Фактический read-only атрибут journal приводит к `DEGRADED`; хэши файлов сохранены; после снятия ограничения restart успешен |
| `DISK_WRITE_FAILURE` | Файловая система отвергает WRITE временного snapshot; storage деградирует, AI заморожен; restart восстанавливает согласованную identity |

Индивидуальные JSON и process logs находятся в подкаталогах матрицы. Повреждение/repair и read-only тесты используют реальные файлы; abrupt cases подтверждены отдельным кодом завершения crash JVM и новым server process. Это дополняет unit process tests и проверки гонок внутри GameTest. Матрица воспроизводит указанные точки и локальную файловую систему; она не измеряет поведение дискового контроллера при потере питания или исчерпании физической ёмкости диска.

Отдельный [disk-lifecycle-results.json](../artifacts/disk-lifecycle-20261013-2142/disk-lifecycle-results.json), `passed=true`, закрывает точные AC-13/14 с **3 настоящими dedicated JVM**: seed завершается через halt(77), оба verify/run — с кодом 0. AC-13 сохраняет прежнюю entity в чанке B при durable hint чанка A, перезапускает процесс с B выгруженным, выполняет public recovery → `MISSING` и public respawn generation 2. Последующая дисковая загрузка B отвергает точный прежний UUID generation 1 до AI; остаётся одна авторизованная новая entity. AC-14 вызывает public remove после фактической выгрузки B при lifecycle `UNLOADED` и нулевых references; tombstone отвергает сохранённый UUID при дисковой загрузке, итоговые references/jobs/tickets/queues/reservations — 0. Старое тело происходит из реального create/move/world-save; синтетический clone NBT не используется. Все 166 main class files совпадают с конечным hash.

## 10 000 dormant records на формальном 4-core / 4-GiB стенде

[registry-bootstrap-results.json](../artifacts/registry-bootstrap-formal-4core-20261013-2101/registry-bootstrap-results.json): `passed=true`, `completePayloadPreserved=true`. Две настоящие dedicated JVM загружают 10 000 `DEAD` records: представлены все варианты, traits и desired behaviors, различные incarnation/generation/revision, времена и location hints. Registry, index и runtime проверяются в живом Minecraft после startup и после checkpoint/restart.

| Метрика | Первый startup | Restart после checkpoint |
|---|---:|---:|
| Repository load → registry install | **0.3533725 s** | **0.3636582 s** |
| Добавочная whole-JVM retained heap | **4.038406 MiB** | **5.009407 MiB** |
| Registry / inactive records | 10 000 / 10 000 | 10 000 / 10 000 |
| Loaded entity refs / runtime / entities / occupied slots | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| Storage / max heap | `READY` / 4096 MiB | `READY` / 4096 MiB |
| Load / apply thread | `viewerparrots-storage` / `Server thread` | `viewerparrots-storage` / `Server thread` |

Оба measured load slices укладываются в **3 s / 64 MiB**. Таймер охватывает фактический repository load, replay и установку registry; полный запуск JVM/Minecraft занимает около 22 s при первом старте и не включён в 0.353 s. Heap — разность whole-JVM live heap после GC до загрузки и после освобождения временных DTO, до checkpoint; это не размер сериализованного JSON и не allocation profile.

Checkpoint захватывает ровно 10 000 records за **40 server ticks**, максимум **256 records за tick/call**. Durable checkpoint завершён за **1.9591924 s**, snapshot прочитан/проверен на writer, `atomicReplace=true`. Полные payload hashes исходного, checkpoint и replay snapshots совпадают. Отдельные unit tests проверяют завершение immutable capture при непрерывных изменениях registry. Время первой профилированной загрузки — **21:02:36 MSK**, повторной — **21:02:57 MSK**; server JVM affinity в обоих случаях реально проверена как `0xFF`.

## Durable CRUD на конечном runtime

[crud-latency-results.json](../build/acceptance-server/crud-latency-results.json) и [server stdout](../build/acceptance-server/final-stdout.log), **20:53:51 MSK**: `passed=true`. По 50 операций каждого вида измерены до публичного service completion после durable journal и server-thread callback.

| Операция | p50, ms | p95, ms | max, ms |
|---|---:|---:|---:|
| Create | 13.3677 | 51.6297 | 54.9367 |
| Rename | 1.0473 | 1.9193 | 2.7086 |
| Trait | 1.0383 | 2.1417 | 3.6120 |
| Remove | 1.8504 | 6.5620 | 12.9594 |

Все p95 ниже 1 s на этом локальном NVMe; reservations до/после и pending operations — 0. Проба выполнялась до длительного benchmark и характеризует данную последовательную нагрузку, без обещания той же latency при перегруженной очереди или другом диске.

## Release JAR startup и установка

Отдельный сервер запущен с **обычным remapped release JAR**, Fabric Loader `0.19.5` и Fabric API, без QA testmod. [proof.json](../artifacts/production-server-smoke/proof.json) содержит `passed=true` и точный JAR SHA-256; [status.txt](../artifacts/production-server-smoke/status.txt) подтверждает `READY`, пустые registry/runtime/очередь. [production-stdout.log](../artifacts/production-server-smoke/production-stdout.log) фиксирует остановку и сохранение всех dimensions в **20:51:58 MSK**. Поле `exitCode` в receipt равно `null`; этот receipt не используется как доказательство exit code 0.

[`scripts/install-prism.ps1`](../scripts/install-prism.ps1) установил конечный JAR в Prism instance **`1.21.1-fabric-dev`**. Фактический SHA-256 установленного файла совпадает с release JAR. Предыдущий JAR сохранён в `minecraft/viewerparrots-backups/20261013-204813/`; в `minecraft/mods` находятся release JAR и Fabric API.

Официальный Prism-клиент проверен с этим установленным JAR в отдельном мире **`Viewer Parrots QA`**: [integrated receipt](../artifacts/verification/20261013/final-integrated/receipt.json), `passed=true`, captured **21:37:21 MSK**. Сохранённая identity `qa_demo` привязана; видны имя **Дмитрий** и variant **YELLOW_BLUE**. COME завершён в 21:31:05, SIT — в 21:32:22; DANCE из SITTING принят в 21:34:16 без jukebox, активный танец запечатлён в 21:34:25, завершение — в 21:34:26, возврат seated model — в 21:34:34. [Integrated log](../artifacts/verification/20261013/final-integrated/integrated-final.log) подтверждает штатный Save and Quit с сохранением всех dimensions в **21:35:50 MSK**. Три screenshots перечислены в receipt. Ванильный `/tp` использовался для установки камеры и модели; он не считается доказательством выполнения COME или FOLLOW.

## Клиентские доказательства и их актуальность

| Период / источник | Подтверждённая граница |
|---|---|
| 18:37–18:47 MSK: [network-smoke-transcript.txt](../artifacts/verification/20261013/network-smoke-transcript.txt), [screenshot 18:41](../artifacts/verification/20261013/2026-10-13_18.41.42.png) | Предварительные двухклиентские команды/наблюдения до конечных изменений async bootstrap/shutdown |
| 19:09–19:16 MSK: [архивированный network transcript](../artifacts/verification/20261013/pre-final-async/build_network-final-transcript.txt); vanilla-client screenshot 19:31 в том же архиве | Дополнительные предварительные movement/network/compatibility проверки; не receipt конечной сборки |
| 20:49:31 и 20:49:38 MSK: [конечный server stdout](../build/acceptance-server/final-stdout.log), [observer](../build/acceptance-observer/final-stdout.log), [streamer](../build/acceptance-client/final-stdout.log) | Оба настоящих клиента вошли на сервер с замороженным конечным runtime; это startup/login evidence |
| 21:31–21:35 MSK: [официальный integrated receipt](../artifacts/verification/20261013/final-integrated/receipt.json) | Установленный release JAR, видимое имя/variant, COME/SIT, DANCE 200 ticks из SITTING и graceful Save and Quit подтверждены |
| 21:48–22:54 MSK: [selected dedicated visual receipt](../artifacts/visual-acceptance/20261013-214101/functional-proof/receipt.json) | `FUNCTIONAL_SCOPE_PASSED`: реальные два клиента — DANCE из SIT/FOLLOW, late tracking, единичный spatial SCREAM, movement и target/dimension события; после restart проверены network permissions level 2. Полный benchmark остаётся отдельным gate |
| 23:08–23:25 MSK: [vanilla compatibility receipt](../artifacts/visual-acceptance/20261013-214101/vanilla-final/acceptance/release-receipt.json) | `PASSED_FOR_PROTOCOL_COMPATIBILITY_GUI_UNAVAILABLE`: original Mojang Main получил понятный LOGIN отказ, без PLAY/join; server остался READY. Native GUI screenshot не заявляется |

Selected receipt содержит неизменяемые snapshots исходных и OP2 логов, command transcript, выбранные PNG и SHA-256 каждого файла. Оба исходных клиента получают DANCE true→false в **21:48:41–51** из SITTING. Непосредственно просмотрены пары противоположных наклонов головы [streamer](../artifacts/visual-acceptance/20261013-214101/functional-proof/sit-dance-active-right-streamer.png) t3369/t3429 и [observer](../artifacts/visual-acceptance/20261013-214101/functional-proof/sit-dance-active-left-observer.png) t3313/t3353, а также upright ended frames t3549/t3474: именованный синий попугай виден у каждого клиента. Это renderer evidence отдельно от tracking flags.

После restart OP2-клиент отправляет DANCE по сети в **22:54:43**, получает terminal completion в **22:54:53** и tracking false; [active frame](../artifacts/visual-acceptance/20261013-214101/functional-proof/qa-dance-398c20dc-ba5c-454e-ac02-64f5690437cb-t12911.png), [ended frame](../artifacts/visual-acceptance/20261013-214101/functional-proof/qa-ended-398c20dc-ba5c-454e-ac02-64f5690437cb-t13072.png) и [видимый seated frame 22:54:56](../artifacts/visual-acceptance/20261013-214101/functional-proof/qa-op2-dance-restored-seated.png) непосредственно просмотрены и показывают именованного попугая. Ранее выбранные captures **22:24:44/45** показывают пустой вид и исключены из визуальных доказательств; INFO SITTING в 22:24:44 остаётся только серверным подтверждением.

Из FOLLOWING оба клиента получают DANCE **21:50:40–50**; observer выходит из tracking на 1000 блоков и возвращается в **21:50:43** с `first=true, dancing=true`. Различимые позы видны на observer PNG t5745/t5785 и streamer t5800; upright ended frames t5865/t5940 просмотрены у обоих. После танца server info восстанавливает FOLLOWING. QA screenshot API рисует реальную tracked entity до HUD; это тестовая клиентская instrumentation, не изменение production renderer.

В **21:51:25** каждый клиент записывает ровно **1 QA_SOUND + 1 QA_PLAY** для `minecraft:entity.skeleton.ambient`: `(0.5, -60, 4.5)`, `relative=false`, `volume=0.8`, `active=true`. Немедленный повтор отвергнут cooldown. Overworld→Nether→Overworld сохраняет entity UUID `398c20dc-ba5c-454e-ac02-64f5690437cb`, incarnation `3e2d73e6-93ed-4938-9f83-7cd130b57703`, generation 1; dimension-scoped selectors с `distance=0..` показывают 0/1 в **22:00:45** и 1/0 в **22:03:12**. [Nether frame](../artifacts/visual-acceptance/20261013-214101/functional-proof/nether-named-parrot-streamer.png) непосредственно показывает имя и front YELLOW_BLUE model. Ранние selectors без ограничения distance не используются как доказательство числа entities в измерении.

После kick стримера в **22:03:39** COME отвергнут, behavior становится IDLE при сохранённом desired FOLLOWING; повторный клиент привязан в **22:04:49** к прежней entity. `/kill` игрока в **22:13:09** вызывает один QA respawn request; Health восстановлен до 20 в **22:13:12**, UUID попугая сохранён. Эта быстрая автоматическая player-respawn последовательность не доказывает отдельный интервал отказа COME для мёртвого target; такой случай проверяется серверными GameTests.

COME с 16 блоков принят при gametime **42622**; к **42802** (в пределах 180 ticks) Pos `(0.5, -60, 3.244718879...)` и server info SITTING подтверждают прибытие. Дальний COME с 100 блоков в **22:18:52–53** переносит прежний UUID к target, без копии. Повторный дальний COME **22:53:54–56** завершается при Pos `(0.274276487..., -60, -1.813407929...)` с прежними UUID/incarnation/generation; [правильно направленная камера 22:53:57](../artifacts/visual-acceptance/20261013-214101/functional-proof/qa-far-come-faced-op2.png) показывает именованную птицу рядом. Camera TP после COME используется только для кадра.

Actual level-0 client отправляет SIT и CREATE по сети и получает Brigadier rejection. После настоящего restart server PID 7156 устанавливает один record из checkpoint 7; OP2 client PID 54160 получает network SIT success в **22:48:16** с полным command ID `2d1117eb-a4eb-42b6-a4e1-f29faa6b82e9`, а network CREATE в **22:51:03** отвергнут. DANCE также принят и завершён с полным ID `63dbfdee-f5a6-4be5-a714-bb68d8d34f82`.

Финальный немодифицированный client **PID 70772** реально запущен через `net.minecraft.client.main.Main`: original Minecraft 1.21.1 JAR проверен по official SHA-1 `30c73b1c5da787909b2f73340419fdf13b9def88`, 54 official libraries — по metadata; classpath содержит 55 JAR, **0 Fabric/QA entries**, без injected agents. Client log фиксирует connection в **23:08:28**; server log — двуязычный LOGIN отказ в **23:08:29**, до PLAY/player join. В **23:25:09** server PID 7156 жив, storage READY, online list содержит только QAVisualStreamer. Scoped compatibility receipt и его 10 публикуемых proof inputs проверены по SHA-256. Полный native forensic log и manifest с локальным RCON credential исключены из release evidence; argument text сохранён неизменённым с synthetic token `0`, без account files.

§15.3 требует фактического отказа несовместимому клиенту без падения сервера и не задаёт screenshot этого отказа. Native GUI capture не получен и не используется как доказательство. Последующий raw-client native `glfw.dll` access violation в **23:17:35**, спустя около 9 минут после отказа, сохранён отдельно как platform failure; связь с попыткой активации окна не установлена. Серверная compatibility-проверка подтверждается независимыми логами и continuity receipt.

После фиксации receipts visual-only server штатно остановлен в **23:35:07**, все три dimensions сохранены: [visual-shutdown.json](../artifacts/visual-acceptance/20261013-214101/visual-shutdown.json). Перед stop: READY, один PRESENT/LOADED record с прежними UUID/incarnation/generation, NORMAL/YELLOW_BLUE/SITTING, без pending writes/recovery/reservations. OP2 client получил `Server closed` и завершён; protected benchmark server/client остались живы. `exitCode=null` в этом receipt не используется как доказательство exit code 0. Исходные functional log prefixes остаются неизменными.

[Read-only saved NBT audit](../artifacts/visual-acceptance/20261013-214101/visual-saved-nbt-audit.json) после этой остановки разобрал все **12 существующих entity-region files / 7 referenced entity chunks**: в мире ровно **одна** custom entity с прежними viewerId/incarnation/generation/UUID, именем Дмитрий, Variant 3 и enabled AI. Проверены region headers/extents/compression и полный NBT; root/passenger entities включены, неиспользованные zero-byte region files явно перечислены. Это срез после конечного graceful save, а не snapshot в момент startup 22:41; обычный restart bind подтверждается отдельными replay/tracking/renderer данными. World/region bytes в evidence ZIP не включаются.

## Длительный benchmark §14 — PENDING

Исходный прогон **PID 56288**, начатый в 21:05:50, **не принят**: первое vanilla50 окно удержало минимум **21 вместо 50** loaded/ticking birds, поскольку открытый мир позволял им улетать и выгружаться. Все **4 частичные строки**, включая три baseline, архивированы и исключены из приёмки: [reason.json](../artifacts/verification/20261013/discarded-unconfined-benchmark/reason.json), `accepted=false`. Сервер штатно остановлен в **22:11:02**, все dimensions сохранены. Эти строки не смешиваются со свежим стендом.

Свежий изолированный FULL запущен в **22:27:51 MSK**: [full-launch-proof.json](../artifacts/final-benchmark/20261013-2220/full-launch-proof.json), [server-stdout.log](../artifacts/final-benchmark/20261013-2220/full/server-stdout.log). Server **PID 69200 / affinity 0xFF**, inactive client **PID 48212 / 0xF00**, heap 4 GiB, simulation/view distance 8. Production classes и release JAR не менялись; только QA harness получил физическое ограждение и abort guard.

Перед первым baseline подготовлена общая **33×33×12** arena: grass floor, barrier walls/roof, центр `(0,-60,0)`. Geometry одинакова для всех девяти окон. Каждый tick warmup и measurement требует **0/50 исходных world-bound/ticking workload objects** внутри арены и enabled AI; target должен оставаться в этой арене. Телепортация или замена потерянной птицы не используется. Строгая неподвижность target не инструментирована: физическое столкновение с мобами может немного сдвинуть idle игрока внутри ограждения; Runtime-команды и пользовательский ввод на FULL стенде не выполняются.

[negative-count-proof.json](../artifacts/final-benchmark/20261013-2220/negative-count-proof.json) подтверждает чувствительность guard: намеренное удаление одной vanilla bird оставляет **49**, вызывает abort и `accepted=false`. [quick-fixture-proof.json](../artifacts/final-benchmark/20261013-2220/quick-fixture-proof.json) относится к короткому предпрогону; он проверяет fixture, но не является результатом §14. Его durations не проходят full acceptance checker.

Полные окна: **baseline / 50 vanilla / 50 custom**, по **3 прогона** каждого; **6000 ticks warmup + 12 000 ticks measurement**, около **135 минут** при 20 TPS. Custom traits: NORMAL 13, COWARD 13, LAZY 12, LOUD 12. Текущий путь результата — `artifacts/final-benchmark/20261013-2220/full/benchmark-results.json`, на 23:44 содержит **5 из 9** окон: три baseline и два vanilla50. Все около 20 TPS; оба vanilla50 сохраняют минимум 50 warmup/measurement bodies и 0 disabled AI. Выполняется vanilla50 run 3. До **9 результатов**, маркера `VIEWERPARROTS_QA_COMPLETE` и успешного acceptance checker статус остаётся **PENDING**. Ожидаемое завершение при 20 TPS — около **00:42:51 MSK 14 октября**; это расчёт, не receipt завершения.

Для закрытия gate нужны baseline ≥19.8 TPS, workload ≥19.5 TPS, добавка server tick p95 к сопоставимому baseline ≤5 ms, scheduler p95 ≤1 ms, tick p50/p95/p99, GC, heap, `storageStayedReady` и сохранение всех 50 loaded/ticking entities. Фактический baseline определяет пригодность стенда. Пакетирование Final проверяет именно выбранный новый путь через `scripts/qa/check-benchmark.py`; старый `build/acceptance-server/benchmark-results.json` не заменяет свежие данные. Production и замороженный compiled runtime во время FULL окна не меняются.

## CI и воспроизведение

[`.github/workflows/build.yml`](../.github/workflows/build.yml) запускает Java 21 build/unit/GameTests, затем отдельный dedicated-server startup smoke и сохраняет artifacts. Локально конечная clean build и отдельный dedicated startup выполнены; это подтверждает соответствующие локальные gates. Опубликованного remote GitHub Actions run в этом отчёте нет. Выполнение workflow на Ubuntu и его remote status не объявляются проверенными по Windows-логам.

После заморозки production исправлено только определение исходного classpath для standalone QA disk-lifecycle overlay. Три filesystem regressions для обычного Gradle export, уже frozen export и запрета изменения production bytes успешно выполнены в **23:35:10** командой `python -m unittest discover -s scripts/tests -p test_disk_lifecycle_paths.py -v`. Этот test не запускает Java/Minecraft и не меняет main class files; исходники helper и tests включаются в source ZIP.

```powershell
.\scripts\verify.ps1 -Clean
# Отдельные QA-процессы и отчёты:
.\gradlew.bat runAcceptanceServer --console=plain
.\gradlew.bat runAcceptanceClient --console=plain
.\gradlew.bat runAcceptanceObserver --console=plain
```

Перезапуск этих команд создаёт новый прогон. Генерируемые XML, recovery/CRUD JSON и runtime logs в `build/` могут быть перезаписаны; конечные receipts следует сохранять вместе с release JAR и его hash. Процессная матрица, формальный 10k профиль, frozen-runtime manifest и clean-build log уже находятся в отдельных `artifacts/` каталогах.

Открыт свежий полный benchmark; functional и scoped vanilla compatibility receipts завершены. Недоступный дополнительный native GUI capture не считается отдельным release gate. Тесты и отчёты выше подтверждают указанные сценарии, потоки и инварианты на данном стенде; они не объявляют совместимость со всеми версиями Minecraft, JVM, ОС или сторонними модами. Принятые ранее изменения к исходному ТЗ — default DANCE **200 ticks** и void rescue в `IMMORTAL` — учитываются в реализации и GameTests.
