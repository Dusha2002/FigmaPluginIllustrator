# Java Export Server

Скелет Spring Boot-приложения для экспорта PDF/TIFF с использованием Apache PDFBox, Apache Batik и TwelveMonkeys ImageIO.

## Требования

- Java 17+
- Подключение к интернету для первой загрузки Maven Wrapper

## Установка и запуск

```powershell
# из директории server-java
./mvnw.cmd spring-boot:run
```

Для Unix-подобных систем доступен скрипт `./mvnw`.

Сервер запускается на `http://localhost:8080`. Эндпоинт `/convert` принимает `multipart/form-data` с файлами SVG/PNG/PDF, параметрами формата и возвращает готовый PDF/TIFF.

## Структура

- `ExportController` — REST-контроллер `/convert`
- `ExportService` — заглушки генерации PDF/TIFF (будут заменены реальной логикой)
- `model` — DTO запроса и ответа

## Дальнейшие шаги

1. Доработать пайплайны SVG→PDF и PDF/TIFF с учётом CMYK.
2. Подключить дополнительные ICC-профили и стандарты PDF/X, PDF/A.
3. Расширить интеграционные тесты и документацию API.
