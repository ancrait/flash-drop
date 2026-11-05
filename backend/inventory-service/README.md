# Inventory Service

Inventory Service зберігає каталог товарів, ціни, залишки та резерви. Сервіс
написаний на Java 21 і Spring Boot, використовує PostgreSQL, Spring Data JPA та
міграції Flyway.

HTTP API надає каталог і операції з товарами та запасом. Резервування,
підтвердження та звільнення резерву реалізовані у внутрішньому сервісі
`ReservationService`. HTTP- або Kafka-інтерфейсу для цих операцій поки немає.
Адміністративні endpoint-и наразі не захищені авторизацією.

## Локальний запуск

Потрібні JDK 21, Docker Desktop із Compose та доступ до Maven-репозиторіїв.
Команди нижче наведені для PowerShell у Windows. У Unix використовуйте
`./mvnw` замість `.\mvnw.cmd` і синтаксис змінних середовища своєї оболонки.

1. У корені репозиторію задайте пароль для локальної бази й запустіть PostgreSQL.

   ```powershell
   $env:INVENTORY_POSTGRES_PASSWORD = "choose-a-local-password"
   docker compose -f docker/docker-compose.yml up -d
   ```

   За замовчуванням Compose створює базу `inventory`, користувача
   `inventory_user` і відкриває порт `127.0.0.1:5432`. Їх можна змінити через
   `INVENTORY_POSTGRES_DB`, `INVENTORY_POSTGRES_USER` та
   `INVENTORY_POSTGRES_PORT`. Дані зберігаються у Docker volume.

2. У тому самому PowerShell задайте параметри підключення та запустіть сервіс.

   ```powershell
   $env:INVENTORY_POSTGRES_URL = "jdbc:postgresql://localhost:5432/inventory"
   $env:INVENTORY_POSTGRES_USER = "inventory_user"
   cd backend/inventory-service
   .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
   ```

   Якщо значення Compose змінено, відповідно змініть URL і користувача. Пароль
   `INVENTORY_POSTGRES_PASSWORD` має збігатися з паролем PostgreSQL. Змінні,
   задані в PowerShell, доступні лише цій сесії та її дочірнім процесам. Для
   запуску з IntelliJ додайте їх у **Run → Edit Configurations → Environment
   variables**. Не додавайте справжні паролі в Git.

Flyway застосовує схему під час запуску. Профіль `local` додатково завантажує
сім демонстраційних товарів. Повторний запуск не скидає існуючі товари та
залишки, а дати розпродажів визначаються під час першого завантаження.
Використовуйте окрему локальну базу.

## HTTP API

Сервіс за замовчуванням доступний на `http://localhost:8080`. Ціни задані в
копійках, підтримується валюта `UAH`. Часові поля приймають ISO 8601 з часовим
поясом, наприклад `2026-12-01T10:00:00Z`.

| Метод і шлях | Дія |
| --- | --- |
| `GET /api/v1/products` | Список активних товарів із пагінацією. За замовчуванням 20 записів, сортування за назвою. Підтримуються параметри `page`, `size`, `sort`. |
| `GET /api/v1/products/{id}` | Активний товар і поточні залишки. |
| `POST /api/v1/admin/products` | Створення активного товару з нульовим запасом. |
| `PATCH /api/v1/admin/products/{id}` | Зміна назви, ціни, активності або меж розпродажу. |
| `POST /api/v1/admin/products/{id}/stock-adjustments` | Додавання додатної кількості до запасу. |

Наприклад, після запуску з профілем `local` каталог можна перевірити командою
`curl.exe http://localhost:8080/api/v1/products`.

Приклад тіла запиту на створення товару:

```json
{
  "sku": "DEMO-001",
  "name": "Клавіатура",
  "description": "Демонстраційний товар",
  "priceMinor": 249900,
  "currency": "UAH"
}
```

Необов’язкові поля `saleStartsAt` і `saleEndsAt` задають період продажу.
Початок включається, кінець виключається. Якщо задані обидві межі, початок має
передувати кінцю. Публічний каталог фільтрує товари за активністю, а період
продажу перевіряється під час резервування.

У PATCH можна передати `name`, `priceMinor`, `active`, `saleStartsAt`
та `saleEndsAt`. Відсутні або `null` поля залишаються без змін, тому
очистити вже встановлену дату через цей PATCH наразі неможливо. Поповнення
запасу приймає тіло `{"quantity": 10}`.

Помилки повертаються як Problem Details. Зокрема, некоректні дані дають
`400`, відсутній товар — `404`, повторний SKU або конфлікт одночасного
оновлення — `409`.

## Резерви та залишки

`available = onHand - reserved`. Під час резервування перевіряються активність,
період продажу, валюта, очікувана ціна та наявність усіх позицій. Товари одного
замовлення резервуються разом або запит відхиляється без часткового резерву.
Підтвердження зменшує `onHand` і `reserved`, звільнення — лише `reserved`.
Обмеження PostgreSQL і блокування рядків захищають залишки від некоректних
значень та конкурентного оновлення.

Один `orderId` відповідає одному резерву. Повтор резервування з тим самим
вмістом повертає збережений результат, а інший вміст спричиняє конфлікт.
Повторне підтвердження або звільнення не застосовує зміну вдруге. Звільнення,
отримане до резервування, залишає запис, який блокує запізнілий резерв.

## Тести

Команди Maven виконуються з `backend/inventory-service`. Для unit- і MVC-тестів
PostgreSQL не потрібен:

```powershell
.\mvnw.cmd '-Dtest=*Test' test
```

Повний набір включає інтеграційні класи `*Tests` і потребує **окремої тестової
бази**. Наприклад, створіть тимчасовий контейнер у PowerShell:

```powershell
docker run --rm -d --name flash-drop-test-db -p 127.0.0.1:55432:5432 -e POSTGRES_DB=inventory_test -e POSTGRES_USER=inventory_test -e POSTGRES_PASSWORD=local_test_password postgres:16-alpine
$env:INVENTORY_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:55432/inventory_test"
$env:INVENTORY_POSTGRES_USER = "inventory_test"
$env:INVENTORY_POSTGRES_PASSWORD = "local_test_password"
.\mvnw.cmd test
```

Для запуску інтеграційного класу кнопкою в IntelliJ задайте ці три змінні в
його Run Configuration. Не використовуйте для інтеграційних тестів спільну
чи production-базу. Після роботи `docker stop flash-drop-test-db` зупинить і
видалить тимчасовий контейнер разом із його даними.

PostgreSQL-тести перевіряють обмеження схеми, конкурентне резервування,
повторні команди, порядок блокувань, звільнення до резервування та відкат
транзакції при помилці запису.
