# Viewer Parrots — лицензии и источники ресурсов

Код мода и оригинальные модели восьми шляп распространяются по
**GPL-3.0-only**. Полный текст находится в `LICENSE`.

Записи настоящих попугаев имеют отдельные лицензии, указанные ниже.
Та же информация включена в JAR в
`assets/viewerparrots/sounds/credits.md`.

## Записи попугаев

В мод входят пять выбранных коротких записей: №01, №12, №14, №15 и №27.
В поставляемом JAR используются выбранные подготовленные файлы Ogg.

| Файл | Запись | Автор | Исходная страница | Исходное аудио | Лицензия | Фрагмент | Длительность |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `parrot/call_01.ogg` | Cockatiel parakeet 1 | Joseph SARDIN | [BigSoundBank 2769](https://bigsoundbank.com/cockatiel-parakeet-1-s2769.html) | [2769.wav](https://bigsoundbank.com/UPLOAD/bwf-en/2769.wav) | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) | 0,17–1,11 с | 0,94 с |
| `parrot/call_12.ogg` | Cockatiel parakeet 12 | Joseph SARDIN | [BigSoundBank 2780](https://bigsoundbank.com/cockatiel-parakeet-12-s2780.html) | [2780.wav](https://bigsoundbank.com/UPLOAD/bwf-en/2780.wav) | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) | 0,08–0,52 с | 0,44 с |
| `parrot/call_14.ogg` | Cockatiel parakeet 14 | Joseph SARDIN | [BigSoundBank 2782](https://bigsoundbank.com/cockatiel-parakeet-14-s2782.html) | [2782.wav](https://bigsoundbank.com/UPLOAD/bwf-en/2782.wav) | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) | 0,04–0,69 с | 0,65 с |
| `parrot/call_15.ogg` | Cockatiel parakeet 15 | Joseph SARDIN | [BigSoundBank 2783](https://bigsoundbank.com/cockatiel-parakeet-15-s2783.html) | [2783.wav](https://bigsoundbank.com/UPLOAD/bwf-en/2783.wav) | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) | 0,00–0,39 с | 0,39 с |
| `parrot/call_27.ogg` | Budgerigar chirping (PDSounds recording 506) | mary905 | [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Budgerigar_chirping.ogg) | [Budgerigar_chirping.ogg](https://upload.wikimedia.org/wikipedia/commons/8/83/Budgerigar_chirping.ogg) | [Public domain](https://commons.wikimedia.org/wiki/File:Budgerigar_chirping.ogg#Licensing) | 0,61–3,01 с | 2,40 с |

Каждая запись обрезана до указанного фрагмента, преобразована в mono 48 кГц,
нормализована до пика −6 dB, получила плавные края по 5 мс и закодирована в
Ogg Vorbis. Высота голоса и скорость воспроизведения не менялись. Это записи
настоящих птиц; звуки мобов Minecraft и синтез речи для `!scream` не используются.

## Внешние зависимости

Minecraft, Fabric Loader и Fabric API устанавливаются отдельно по инструкции.
В пакет для стримера они не включены. Голоса команды `!say` генерируются через
OpenRouter/ElevenLabs во время работы; записи речи и пользовательские ключи
в распространяемый пакет не входят.
