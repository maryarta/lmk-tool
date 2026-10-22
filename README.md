## Запуск

```sh
./gradlew fatJar
java -jar build/libs/lmk-tool.jar
```


## read
Обязательные параметры для `read`:

| Параметр | Значение |
|---|---|
| `--config` | Путь к файлу конфигурации |
| `--hsm-id` | ID HSM из поля `hsmConfigs[].id`|
| `--lmk-id` | ID набора LMK в `liveLmks`|
| `--pair-id` | Номер существующей пары в `pairs`|

Формат:

```text
read --config "PATH_TO_CONFIG.json" --hsm-id HSM_ID --lmk-id LMK_ID --pair-id PAIR_NUMBER
```

Пример:

```text
read --config "/Iso8583studio/composeApp/Iso8583Studio_simulators.json" --hsm-id cb49039a-1842-4b88-97e9-505479907396 --lmk-id 00 --pair-id 0
```

## write
Обязательные параметры для `write`:

| Параметр | Значение |
|---|---|
| `--config` | Путь к файлу конфигурации |
| `--hsm-id` | ID HSM из поля `hsmConfigs[].id`|
| `--lmk-id` | ID набора LMK в `liveLmks`|
| `--pair-id` | Номер существующей пары в `pairs`|
| `--left` | Значение `leftKey` в HEX: 8 байт (16 HEX-цифр) |
| `--right` | Значение `rightKey` в HEX: 8 байт (16 HEX-цифр) |
Допустимые форматы:
`409C3712F9E399B1` или `"40 9C 37 12 F9 E3 99 B1"`.

Формат:

```text
write --config "PATH_TO_CONFIG.json" --hsm-id HSM_ID --lmk-id LMK_ID --pair-id PAIR_NUMBER --left "LEFT_HEX" --right "RIGHT_HEX"
```

Пример:

```text
write --config "/Iso8583studio/composeApp/Iso8583Studio_simulators.json" --hsm-id cb49039a-1842-4b88-97e9-505479907396 --lmk-id 00 --pair-id 0 --left "40 9C 37 12 F9 E3 99 B1" --right 1666CE4C88B53A58
```

