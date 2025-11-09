# Java Export Server

Spring Boot сервис, выполняющий конвертацию файлов из Figma (SVG/PNG/PDF) в типографские форматы PDF и TIFF с поддержкой CMYK, PDF/X и пресетов TIFF.

## Требования

- Java 17+
- Maven Wrapper (скачивается автоматически при первом запуске)

## Запуск

```powershell
# из директории server-java
./mvnw.cmd spring-boot:run
```

Для Unix-подобных систем используется `./mvnw spring-boot:run`.

Приложение доступно по адресу `http://localhost:8080`. Основной REST-эндпоинт — `/convert` (метод POST, `multipart/form-data`).

## Поддерживаемые возможности

- **PDF экспорт**
  - Векторный рендеринг SVG при помощи Apache Batik + PdfBoxGraphics2D.
  - Автоматический fallback в растровый режим с конвертацией в CMYK.
  - Выбор ICC-профиля: `coated_fogra39`, `iso_coated_v2`, `us_web_coated_swop`.
  - Поддержка стандартов PDF/X-1a, PDF/X-3, PDF/X-4: формируются XMP-метаданные и выполняется Preflight-проверка (Apache PDFBox Preflight) с детализированным отчётом об ошибках.

- **TIFF экспорт**
  - Любой входящий SVG/PNG/PDF переводится в CMYK с заданным профилем.
  - Настраиваемые DPI и метод сжатия (без сжатия, LZW).
  - Режимы антиалиасинга (`none`, `fast`, `balanced`, `best`) реализованы на стороне сервера.

- **Figma плагин**
  - UI (`ui.html`) позволяет выбрать формат экспорта, версию/стандарт PDF, ICC-профиль, параметры TIFF.
  - Скрипт (`code.js`) выгружает выбранные объекты из Figma и отправляет их на сервер.

## API `/convert`

| Поле              | Тип     | Описание |
|-------------------|---------|----------|
| `image`           | файл    | Исходный SVG/PNG/PDF из Figma |
| `format`          | строка  | `pdf` или `tiff` |
| `name`            | строка  | Имя файла без расширения |
| `dpi`             | число   | Базовый DPI (используется для PDF fallback и TIFF) |
| `pdfVersion`      | строка  | Версия PDF (например, `1.4`, `1.6`) |
| `pdfStandard`     | строка  | `none`, `PDF/X-1a:2001`, `PDF/X-3:2002`, `PDF/X-3:2003`, `PDF/X-4:2008` |
| `pdfColorProfile` | строка  | `coated_fogra39`, `iso_coated_v2`, `us_web_coated_swop` |
| `tiffCompression` | строка  | `none` или `lzw` |
| `tiffAntialias`   | строка  | `none`, `fast`, `balanced`, `best` |
| `tiffDpi`         | число   | DPI итогового TIFF |
| `widthPx` / `heightPx` | число | Необязательное переопределение размера (px) |

В ответ сервер возвращает бинарный PDF/TIFF с корректно выставленными заголовками.

## Тесты

```
./mvnw.cmd clean test
```

Интеграционные тесты проверяют успешную конвертацию SVG→PDF (включая альтернативные ICC-профили) и корректную обработку невалидных запросов.

## Основные модули

- `ExportController` — REST API.
- `ExportService` — бизнес-логика экспорта, управление профилями, PDF/X preflight.
- `ColorProfileManager` — загрузка ICC-профилей.
- `ImageProcessingService` — масштабирование, антиалиасинг, конвертация в CMYK, запись TIFF/JPEG.
- `SvgRenderer` — векторный рендеринг SVG через Batik.

