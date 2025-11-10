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
// 1. Сохраняем каждый документ во временный поток
ByteArrayOutputStream tempStream = new ByteArrayOutputStream();
sourceDocument.save(tempStream);
sourceDocument.close();

// 2. Загружаем обратно для корректного слияния
PDDocument tempDoc = Loader.loadPDF(tempStream.toByteArray());
tempDocuments.add(tempDoc);

// 3. Используем PDFMergerUtility
PDFMergerUtility merger = new PDFMergerUtility();
PDDocument combinedDocument = new PDDocument();
for (PDDocument doc : tempDocuments) {
    merger.appendDocument(combinedDocument, doc); // ✅ Правильно!
}
```

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
