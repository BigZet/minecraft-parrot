# Viewer Parrots

Minecraft-мод (Fabric): персональный попугай-зритель с постоянной идентичностью, характером
и управляемым поведением. Полное ТЗ — [viewer_parrots_technical_spec.md](viewer_parrots_technical_spec.md).

## Зафиксированные версии (M0)

| Компонент | Версия |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 (Temurin 21.0.12) |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.116.17+1.21.1 |
| Yarn mappings | 1.21.1+build.3 |
| Fabric Loom | 1.17.20 |
| Gradle | 9.5.1 (wrapper) |

Все версии закреплены точными значениями в `gradle.properties`; динамические `+`/`latest` не используются.

## Сборка

```bash
./gradlew build
```

Готовый JAR — `build/libs/viewerparrots-<version>.jar`.

## Запуск в разработке

```bash
./gradlew runClient   # клиент с модом
./gradlew runServer   # dedicated server
```

Клиент работает в `run/`, dedicated server — в `run/server/` (разные каталоги, иначе они дерутся
за мир и `logs/latest.log`). Mojang EULA в `run/server/eula.txt` уже принят.

### Временные команды приёмки M0

Уровень прав 3. Будут заменены полным набором `/viewerparrot …` из раздела 6 ТЗ на этапах M2–M3.

```
/viewerparrots-m0 spawn <RED_BLUE|BLUE|GREEN|YELLOW_BLUE|GRAY>
/viewerparrots-m0 dance on|off
/viewerparrots-m0 info
/viewerparrots-m0 tp <измерение>
```

## Результаты M0

Проверено на dedicated server 1.21.1.

| Проверка | Результат |
|---|---|
| Сборка и запуск клиента | проходит |
| Запуск dedicated server | проходит; серверная инициализация не тянет клиентские классы |
| Регистрация `viewerparrots:viewer_parrot` | проходит на обоих окружениях |
| Танец без jukebox | работает |
| Удержание места во время танца | работает |
| UUID при переносе между измерениями | сохраняется |

Выводы, влияющие на дальнейшие этапы:

1. **Танец не требует mixin и access widener.** `ParrotEntityModel.getPose()` вызывает
   `isSongPlaying()` виртуально, поэтому переопределения этого метода поверх tracked-флага
   `FORCED_DANCING` достаточно. Ванильное поле `songPlaying` не используется: оно сбрасывается
   в `ParrotEntity.tick()`, когда рядом нет проигрывающего jukebox.
2. **`Entity.teleportTo` создаёт НОВЫЙ Java-объект.** UUID при этом сохраняется, но ссылка на
   прежний объект становится недействительной — `LoadedEntityIndex` обязан обновляться после
   переноса (раздел 5.3 ТЗ).
3. **Tracked-состояние не переживает перенос между измерениями.** Флаг танца сбрасывается в
   `false`, потому что новый объект восстанавливается из NBT, а tracked data в NBT не входит.
   Контроллер должен либо переносить runtime-состояние явно, либо трактовать перенос как отмену
   временных действий — решение принимается на M3.
4. **Ванильные goals уводят попугая в полёт.** `FlyOntoTreeGoal` (приоритет 2) и `FollowMobGoal`
   (приоритет 3) вытесняются собственным goal с приоритетом 0 на время танца. Полный собственный
   набор goals остаётся задачей M3–M4.
5. Ошибка `No data fixer registered for viewer_parrot` в логе — диагностика только дев-окружения
   (`Util.getChoiceType` закрыт проверкой `SharedConstants.useChoiceTypeRegistrations`). В обычной
   игре её нет, её выдаёт любая модовая сущность.

Не закрыто: приёмка с двумя клиентами одновременно (AC-09, tracking позднего клиента).

## Ручное тестирование собранного JAR

Инстанс Prism Launcher `1.21.1-fabric-dev` уже создан (MC 1.21.1 + Fabric 0.19.5, Fabric API в `mods/`).
Скопируйте туда собранный JAR:

```bash
cp build/libs/viewerparrots-*.jar \
  "$APPDATA/PrismLauncher/instances/1.21.1-fabric-dev/minecraft/mods/"
```

Мод обязателен и на сервере, и на всех клиентах.

## Структура

- `src/main` — common/server код; не должен ссылаться на клиентские классы.
- `src/client` — клиентский код (рендер, презентация), отдельный source set через `splitEnvironmentSourceSets()`.
