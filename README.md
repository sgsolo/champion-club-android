# Champion Club · Android

Нативное Android-приложение для записи на групповые занятия по теннису в Champion Club, Ижевск. Текущая версия: **1.7.0**. Android 8.0+, русский интерфейс, без WebView и сторонних SDK.

## Возможности

- Расписание клуба: календарь, группы, покрытие корта, цены и свободные места.
- Вход по телефону и SMS-коду на отдельном экране с автоматическим фокусом. Маска номера `+7 (999) 123-45-67`.
- Нативная запись с сохранением контактов и проверкой доступности перед отправкой.
- Детали записи, отмена с подтверждением, предстоящие и прошедшие занятия.
- Баннер ближайшей записи; при отсутствии записей — предложение свободного занятия.
- Статистика: общее число посещённых занятий, последние семь дней и последние двенадцать месяцев.
- Зашифрованное хранение контактов и клиентского сеанса через Android Keystore.

Время расписания и статистики — время клуба, `Europe/Samara` (UTC+4).

## Сборка

Требуются JDK 17 и Android SDK Platform 36. Gradle 8.13, Android Gradle Plugin 8.13.2. Откройте корень проекта в Android Studio или выполните:

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Android Studio создаст `local.properties` с адресом SDK; при сборке из терминала настройте SDK в своём окружении. APK: `app/build/outputs/apk/debug/app-debug.apk`.

Обычные `debug` и `release` работают с реальным API YCLIENTS. `debug` подписывается отладочным ключом и предназначен для установки и проверки. Для публикации в RuStore потребуются постоянная релизная подпись и материалы издателя.

## Интеграция: настройка разработчиком

Для клиентского входа, личной истории, записи и отмены нужен действующий Partner Token с соответствующими правами. Пользователь приложения его не вводит и не видит настройки API.

Перед сборкой задайте `YCLIENTS_PARTNER_TOKEN` в окружении либо добавьте в локальный `local.properties` строку `yclients.partnerToken=...`. Пример: [local.properties.example](local.properties.example). Переменная окружения имеет приоритет. Не передавайте ключ в аргументах командной строки и не добавляйте его в исходники или `gradle.properties`.

Ключ включается в APK через `BuildConfig.PARTNER_TOKEN`, поэтому его можно извлечь из приложения. Локальный файл с ключом исключён из Git. При отсутствии ключа или отказе сервера пользователь видит обычную ошибку подключения. Публичное расписание загружается отдельно.

**Защищённые операции с реальным ключом пока не проверены: ключ не предоставлен.** Проверки выполняются на собственном локальном имитаторе API. Интеграция может потребовать уточнений после получения доступа. Документация: [developers.yclients.com](https://developers.yclients.com/).

## Тестовый контур

Сервер, панель и подробная инструкция: [local-server/](local-server/README.md). Python 3.9+, без внешних пакетов. Сервер не обращается к YCLIENTS, не отправляет SMS и не создаёт реальные записи.

```sh
python3 local-server/server.py
./gradlew :app:assembleSandbox :app:lintSandbox
adb reverse tcp:18765 tcp:18765
adb install -r app/build/outputs/apk/sandbox/app-sandbox.apk
```

При нескольких устройствах добавьте `-s SERIAL` в команды `adb`. На macOS можно использовать `local-server/start.command [SERIAL]`: скрипт настраивает USB-проброс и запускает сервер, если он ещё не работает.

Тестовое приложение — **Champion Тест**, ID `club.champion.tennis.sandbox`. Оно установлено отдельно и обращается только к `http://127.0.0.1:18765`. При недоступном сервере перехода к реальному YCLIENTS нет.

- Панель: [127.0.0.1:18765](http://127.0.0.1:18765/).
- Номера: `70000000000`, `70000000001`.
- Код: `1234`, после запроса; настоящая SMS не отправляется.
- История и сеансы создаются в `local-server/data/` и не входят в репозиторий.

Панель позволяет имитировать отсутствие мест, отказ в записи или отмене, истёкший сеанс и потерю ответа. Изменяющие запросы автоматически не повторяются; при неподтверждённом бронировании повтор блокируется для защиты от дублей.

## Проверки

Сборки `debug`/`sandbox` и Android Lint успешны, **17 инструментальных тестов** прошли на отдельном эмуляторе. Проверены маска и курсор, вход, сеанс, баннер, запись, отмена, история, статистика и ошибки без технических настроек в интерфейсе.

Тесты изменяют данные сервера. **Не запускайте их против используемой пользователем базы.** Запустите отдельный сервер:

```sh
python3 local-server/server.py --port 18766 --database /tmp/champion-tests.sqlite3
```

В другом терминале, после запуска отдельного эмулятора:

```sh
./gradlew -PtestBuildType=sandbox :app:assembleSandbox :app:assembleSandboxAndroidTest
adb -s emulator-5554 reverse tcp:18765 tcp:18766
adb -s emulator-5554 install -r app/build/outputs/apk/sandbox/app-sandbox.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/sandbox/app-sandbox-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  -e class club.champion.tennis.CustomerFlowTest,club.champion.tennis.PhoneMaskTest,club.champion.tennis.LocalServerTest,club.champion.tennis.StatisticsTest \
  club.champion.tennis.sandbox.test/android.test.InstrumentationTestRunner
python3 local-server/verify.py --base http://127.0.0.1:18766
```

Замените `emulator-5554` идентификатором своего эмулятора. `AppTest` содержит ранние проверки прототипа и не входит в текущий набор из 17 тестов.

История изменений и проверок: [docs/HISTORY.md](docs/HISTORY.md).
