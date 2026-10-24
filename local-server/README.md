# Champion · локальный тестовый контур

Сервер работает на компьютере и имитирует методы, используемые Android-приложением. Он не обращается к YCLIENTS, не отправляет SMS и не создаёт записи в клубе. Это наш симулятор, а не официальный стенд YCLIENTS. Python 3.9+, внешние пакеты не нужны.

## Как пользоваться

1. Подключите Android-телефон к компьютеру по USB; отладка по USB должна оставаться включённой.
2. Запустите `start.command` двойным щелчком. Он настроит USB-проброс и запустит сервер. Если сервер уже работает, повторный экземпляр не запускается.
3. На телефоне откройте отдельное приложение **Champion Тест**. Обычный Champion Tennis работает отдельно со своими данными.
4. Войдите с номером **70000000000**, нажмите «Получить код» и введите **1234**. Настоящий Partner Token не нужен; настоящая SMS не отправляется.
5. Выберите доступное занятие, подтвердите тестовую запись. Откроется экран с деталями и кнопкой отмены. Этот же экран доступен из списка «Предстоящие». Отмена требует подтверждения, сохраняет историю и освобождает место.

Второй независимый аккаунт: **70000000001**, тот же код. Код выдаётся после запроса, действует пять минут, используется один раз. Пауза между запросами — 60 секунд. Другие номера сервер отклоняет.

Панель на компьютере: **http://127.0.0.1:18765/**. В ней можно увидеть записи и запросы, выбрать сценарий ошибки или восстановить исходные тестовые записи. У каждого аккаунта есть расширенная история за три года и несколько будущих записей. Расписание доступно на выбранную дату; стартовый экран открывает сегодняшний день по времени клуба.

Сервер должен работать, компьютер — быть включённым, телефон — оставаться подключённым по USB. После переподключения телефона запустите `start.command` ещё раз для восстановления проброса порта. Для остановки сервера запустите `stop.command`; если запускали сервер в Terminal, также работает Ctrl+C в этом окне. Для другого Android-устройства передайте его serial аргументом `start.command SERIAL`.

## Сценарии

- Обычная работа: расписание, вход, прошлая/будущая история, создание записи, уменьшение мест.
- Все места заняты: открытие и обновление занятия покажут отсутствие мест.
- Отказ записи: сервер возвращает 422, запись не создаётся.
- Создать запись и оборвать ответ: запись сохраняется, подтверждение не доходит. Приложение блокирует повтор; результат виден в «Моих записях». Сценарий срабатывает один раз.
- Истёкший сеанс: запрос истории возвращает 401. Верните обычный режим в панели.
- Клуб запрещает отмену: DELETE возвращает 403, запись и место сохраняются.
- Отменить и оборвать ответ: запись отменяется, ответ теряется один раз. Приложение проверяет состояние GET-запросом, без повторной отправки DELETE.

После изменения режима обновите экран приложения. Сброс сервера восстанавливает исходные записи и очищает выданные SMS-коды, сохраняя сеансы. Он не удаляет локальную историю отправок на телефоне. При необходимости очистите данные **только Champion Тест** через настройки Android и войдите снова.

## Ручной запуск

Из этого каталога:

```sh
/usr/bin/python3 server.py
```

В другом окне Terminal:

```sh
adb -s SERIAL reverse tcp:18765 tcp:18765
```

Локальная база: `data/sandbox.sqlite3`. Она сохраняет только тестовые аккаунты, сеансы и записи между перезапусками. Журнал HTTP в памяти содержит время, метод, путь и статус, без токенов и тел запросов. Сервер слушает только `127.0.0.1`, не доступен другим компьютерам в сети. Сборка приложения допускает запросы только к этому локальному адресу и не переключается на реальный API при ошибках.

## Проверки

При работающем сервере выполнить `/usr/bin/python3 verify.py`. Проверка меняет и затем сбрасывает тестовые записи; не запускайте её одновременно с ручной проверкой приложения.

Она проверяет реальный HTTP, неверный ключ/номер/код, одноразовый код и паузу, разделение аккаунтов, запись и дубли, одновременную запись на последнее место, сценарии ошибок и потерю ответа после сохранения. Android-тесты нативного сценария находятся в `LocalServerTest.java` исходников приложения. Результаты сохранены рядом с APK.

## Ограничения

Реализованы только методы, нужные этому приложению, без оплаты, настоящей SMS-доставки и полной бизнес-логики YCLIENTS. Успешный локальный тест не подтверждает реальные права Partner Token или совместимость с сервером YCLIENTS. Для этого отдельно нужен доступ к их тестовому филиалу/среде или согласованная интеграционная проверка.

24 октября 2026: проверки сервера и два Android-теста прошли; ADB сообщил об успешной установке тестового APK 1.2.0-local на Pixel 4a. USB-проброс 18765 был настроен, но после установки телефон отключился: проверить установленную версию через менеджер пакетов не удалось. После подключения нужно снова запустить start.command. Сервер продолжает работать.

## Statistics update: 1.3

The simulator now seeds more than 240 records per account across three calendar years, including attended, cancelled, unmarked and future records. Startup enriches existing data without removing bookings or sessions. The older initial-data description above is superseded by this update.

On 24 October 2026, the first account has 245 records and the second has 244. All six pre-existing records and 11 sessions were preserved. Champion Test 1.3.0-local (versionCode 4) was installed and verified on Pixel 4a.

The statistics tab shows an all-time total over loaded history, plus vertical charts for the last seven calendar days and the months of a selected year. It counts non-deleted records marked attendance=1 only after the class has ended in the club timezone. Other statuses and duplicate IDs do not increase the total.

Tests used a separate database and server on port 18766, while the phone remained on 18765. To run isolated checks: python3 server.py --port 18766 --database /path/to/test.sqlite3, then python3 verify.py --base http://127.0.0.1:18766. Verification resets the specified test database.

Update 1.3.1: both charts are vertical columns. The recent chart always covers the last seven calendar dates including today in the club timezone; year selection affects only the monthly chart. This update does not change the simulator data or sessions.

Update 1.3.2: year selection is removed. The annual chart always shows the last twelve calendar months, including the current month. The recent chart remains the last seven dates including today. No simulator data or sessions were changed.
