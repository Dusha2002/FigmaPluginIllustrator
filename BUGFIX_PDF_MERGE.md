# Исправление: Битый PDF при объединении элементов

## Проблема

При экспорте нескольких элементов в один PDF файл:
- PDF получался битым с артефактами
- Векторная графика не редактировалась в Illustrator
- Элементы отображались некорректно

## Причина

Неправильное объединение PDF документов. Попытка напрямую добавлять страницы из одного `PDDocument` в другой через `combinedDocument.addPage(page)` приводит к:
- Потере ссылок на ресурсы (шрифты, изображения, графику)
- Конфликтам объектных идентификаторов
- Повреждению структуры PDF

## Решение

Использование `PDFMergerUtility` из Apache PDFBox для корректного слияния:

### Было (неправильно):
```java
PDDocument combinedDocument = new PDDocument();
for (PDDocument sourceDocument : documents) {
    for (PDPage page : sourceDocument.getPages()) {
        combinedDocument.addPage(page); // ❌ Неправильно!
    }
}
```

### Стало (правильно):
```java
// 1. Создаём и полностью подготавливаем каждый PDF документ
for (int i = 0; i < files.size(); i++) {
    PDDocument sourceDocument = createSourcePdfDocument(...);
    
    // Применяем настройки ДО объединения
    applyPdfDefaults(sourceDocument, colorProfile);
    
    // Сохраняем готовый документ
    ByteArrayOutputStream tempStream = new ByteArrayOutputStream();
    sourceDocument.save(tempStream);
    pdfBytes.add(tempStream.toByteArray());
    sourceDocument.close();
}

// 2. Используем PDFMergerUtility для объединения готовых PDF
PDFMergerUtility merger = new PDFMergerUtility();
ByteArrayOutputStream mergedStream = new ByteArrayOutputStream();

for (byte[] pdfData : pdfBytes) {
    merger.addSource(new RandomAccessReadBuffer(pdfData));
}

merger.setDestinationStream(mergedStream);
merger.mergeDocuments(null); // ✅ Правильно!
```

### Ключевые изменения:
1. **Применяем `applyPdfDefaults()` к каждому документу ДО объединения** - это предотвращает искажения
2. **Сохраняем каждый документ как готовый PDF** - полностью сформированная структура
3. **Используем `RandomAccessReadBuffer`** для передачи байтов в merger
4. **Используем `mergeDocuments()`** вместо `appendDocument()` - более надёжный метод

## Что делает PDFMergerUtility

1. **Копирует все ресурсы**: Шрифты, изображения, графические состояния
2. **Переназначает идентификаторы**: Избегает конфликтов объектных ID
3. **Сохраняет структуру**: Векторная графика остаётся редактируемой
4. **Корректно обрабатывает метаданные**: ICC-профили, аннотации, закладки

## Результат

После исправления:
- ✅ PDF файл корректный, без артефактов
- ✅ Векторная графика полностью редактируется в Illustrator
- ✅ Все элементы отображаются правильно
- ✅ Сохраняется цветовое пространство CMYK

## Файлы изменены

- `ExportService.java` - метод `convertMultipleToPdf()`

## Дополнительная информация

PDFBox документация по слиянию PDF:
https://pdfbox.apache.org/2.0/cookbook/merge.html
