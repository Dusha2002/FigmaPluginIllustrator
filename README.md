# Figma Plugin Illustrator

Плагин для Figma с серверной частью на Java (Spring Boot), предназначенный для экспорта графики в печатные форматы с поддержкой CMYK, TIFF с расширенными настройками и автоматическим объединением элементов в PDF.

## Возможности

- **Экспорт в PDF (CMYK)**: Автоматическое определение векторных и растровых элементов, экспорт SVG для векторов и PNG для растровых, объединение в один PDF с ICC-профилем Coated FOGRA39.
- **Экспорт в TIFF (CMYK)**: Конвертация PNG в TIFF с настройками качества (standard, supersample, texthint), PPI, LZW-сжатием.
- **Настройки текста в SVG**: Выбор между встраиванием текста или его векторизацией.
- **Сохранение настроек UI**: Размеры окна плагина, тема, формат экспорта по умолчанию сохраняются между сессиями.
- **Множественный экспорт**: Экспорт нескольких выделенных элементов с объединением в один файл.
- **Инструменты анализа**: Скрипты для проверки метаданных TIFF и анализа PDF.

## Архитектура

- **Клиентская часть (Figma Plugin)**:
  - `code.js`: Логика плагина, взаимодействие с Figma API, сохранение настроек в clientStorage.
  - `ui.html`: Интерфейс пользователя, управление размерами окна, настройками экспорта.
  - `manifest.json`: Манифест плагина для Figma.

- **Серверная часть (server-java)**:
  - Spring Boot приложение на порту 8080.
  - `ExportController.java`: REST API для обработки экспорта.
  - `ExportService.java`: Основная логика конвертации, используя iText 7 для PDF, Apache Batik для SVG, TwelveMonkeys для изображений.
  - `PdfAnalysisService.java`: Анализ готовых PDF на шрифты, цветовые пространства.
  - Ресурсы: ICC-профили CMYK, коллекция шрифтов в `src/main/resources`.

## Структура проекта

```
.
├── code.js                # Основной код плагина
├── ui.html                # HTML интерфейс
├── manifest.json          # Манифест Figma плагина
├── README.md              # Этот файл
├── Dockerfile             # Контейнеризация сервера
├── docs/
│   └── batch-export-plan.md  # План пакетного экспорта
├── server-java/           # Серверная часть
│   ├── pom.xml
│   ├── Procfile
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/figma/export/
│   │   │   └── resources/
│   │   └── test/
│   └── target/
├── tools/
│   ├── analyze_tiff.ps1   # Анализ TIFF (PowerShell)
│   └── inspect_tiff.py    # Инспекция TIFF (Python)
├── SvgTextUtil.txt        # Утилиты для SVG текста
└── Парфюм.pdf             # Тестовый PDF файл
```

## Установка и запуск

### Требования

- Java 17
- Maven (или Maven Wrapper в проекте)
- Figma Desktop для тестирования плагина

### Запуск сервера

1. Перейдите в папку `server-java`:
   ```bash
   cd server-java
   ```

2. Запустите сервер:
   ```bash
   ./mvnw.cmd spring-boot:run
   ```

Сервер будет доступен на `http://localhost:8080`.

### Сборка и тестирование

```bash
cd server-java
./mvnw.cmd clean package
./mvnw.cmd test
```

### Docker

```bash
docker build -t figma-plugin-server .
docker run -p 8080:8080 figma-plugin-server
```

## Установка плагина в Figma

1. Откройте Figma Desktop.
2. Перейдите в **Plugins → Development → Import plugin from manifest…**.
3. Выберите файл `manifest.json` из корня проекта.
4. Плагин будет установлен и готов к использованию.

По умолчанию плагин подключается к локальному серверу на `localhost:8080`. Для продакшена измените `DEFAULT_SERVER_URL` в `code.js` и домены в `manifest.json`.

## Использование

1. Выделите один или несколько элементов в Figma.
2. Запустите плагин через меню Plugins.
3. Выберите формат экспорта (PDF или TIFF).
4. Настройте параметры (PPI, качество TIFF, режим текста и т.д.).
5. Нажмите "Экспорт" – файлы будут отправлены на сервер и обработаны.

## Диагностика

- Логи сервера показывают детали обработки: размеры, цветовые модели, шрифты.
- Интеграционные тесты проверяют корректность экспорта.
- Используйте скрипты в `tools/` для анализа выходных файлов.

## Полезные ссылки

- [Figma Plugin API](https://www.figma.com/plugin-docs/)
- [iText 7 Documentation](https://itextpdf.com/en/resources/documentation)
- [Spring Boot](https://spring.io/projects/spring-boot)

## Лицензия

Проект не имеет лицензии. Для публичного распространения добавьте файл LICENSE.
