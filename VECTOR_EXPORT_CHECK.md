# Проверка векторного экспорта PDF в CMYK

## Текущая реализация

Векторный экспорт PDF в CMYK **УЖЕ РЕАЛИЗОВАН** в проекте. Вот как это работает:

### 1. Клиентская часть (code.js)

**Определение векторных элементов:**
```javascript
function isVectorNode(node) {
  // Векторные типы
  const vectorTypes = ['VECTOR', 'LINE', 'ELLIPSE', 'POLYGON', 'STAR', 
                       'RECTANGLE', 'TEXT', 'FRAME', 'GROUP', 'COMPONENT', 
                       'INSTANCE', 'BOOLEAN_OPERATION'];
  return vectorTypes.includes(node.type) && node.type !== 'IMAGE';
}

function hasRasterEffects(node) {
  // Проверка эффектов, требующих растеризации
  const rasterEffects = ['DROP_SHADOW', 'INNER_SHADOW', 'LAYER_BLUR', 'BACKGROUND_BLUR'];
  return node.effects?.some(e => e.visible && rasterEffects.includes(e.type));
}
```

**Экспорт:**
- Векторные элементы → SVG
- Растровые элементы → PNG

### 2. Серверная часть (Java)

**Путь обработки SVG:**
1. `ExportController` → принимает SVG файл
2. `ExportService.createSourcePdfDocument()` → определяет тип как SVG
3. `ExportService.createPdfFromSvg()` → создаёт векторный PDF
4. `SvgRenderer.renderSvg()` → рендерит SVG в PDF с помощью Apache Batik
5. `CmykPdfColorMapper` → конвертирует RGB → CMYK
6. `applyPdfDefaults()` → добавляет ICC-профиль Coated FOGRA39

**Ключевые компоненты:**

#### CmykPdfColorMapper
```java
public PDColor mapColor(Color color, IColorMapperEnv env) {
    float[] rgb = new float[]{
        color.getRed() / 255f,
        color.getGreen() / 255f,
        color.getBlue() / 255f
    };
    float[] cmyk = colorSpace.fromRGB(rgb);
    return new PDColor(cmyk, PDDeviceCMYK.INSTANCE);
}
```

#### SvgRenderer
```java
PdfBoxGraphics2D graphics2D = new PdfBoxGraphics2D(document, widthPt, heightPt);
graphics2D.setColorMapper(colorMapper); // CMYK mapper
graphicsNode.paint(graphics2D); // Векторный рендеринг
PDFormXObject form = graphics2D.getXFormObject();
contentStream.drawForm(form); // Вставка векторного объекта
```

## Как проверить, что векторный экспорт работает

### Шаг 1: Запустите сервер с логированием

```bash
cd server-java
.\mvnw.cmd spring-boot:run
```

### Шаг 2: Экспортируйте векторный элемент

1. Создайте простой прямоугольник в Figma
2. Выберите его
3. Откройте плагин
4. Выберите формат PDF
5. Нажмите "Экспортировать"

### Шаг 3: Проверьте логи сервера

Вы должны увидеть:
```
INFO: Конвертация в PDF: baseName=Rectangle, uploadType=SVG, dpi=72
INFO: Создание векторного PDF из SVG: size=XXX bytes, width=XXXpx, height=XXXpx
INFO: Размеры в points: width=XXXpt, height=XXXpt
INFO: Используется CMYK color mapper с профилем: Coated FOGRA39
INFO: SVG успешно отрендерен как векторный PDF
INFO: Документ ВЕКТОРНЫЙ - сохраняется как есть с CMYK color mapping
INFO: PDF создан: size=XXX bytes
```

**Если видите "Документ НЕ векторный - будет растеризован"** → проблема в определении типа на клиенте или сервере.

### Шаг 4: Проверьте PDF в Illustrator

1. Откройте экспортированный PDF в Adobe Illustrator
2. Выберите инструмент "Direct Selection" (A)
3. Кликните на элемент

**Ожидаемый результат:**
- ✅ Видны опорные точки (anchor points)
- ✅ Можно изменять форму
- ✅ Можно изменять цвет заливки и обводки
- ✅ В панели Color показывается CMYK

**Если элемент растеризован:**
- ❌ Нет опорных точек
- ❌ Показывается как "Linked Image" или "Embedded Image"
- ❌ Нельзя изменить форму

### Шаг 5: Проверьте цветовое пространство

В Illustrator:
1. File → Document Color Mode → должно быть **CMYK Color**
2. Window → Separations Preview
3. Включите просмотр каналов C, M, Y, K

## Возможные проблемы и решения

### Проблема 1: Элемент растеризуется, хотя должен быть векторным

**Причины:**
- Элемент имеет эффекты (тени, размытие)
- Элемент содержит растровые изображения внутри
- SVG из Figma содержит `<image>` теги

**Решение:**
- Удалите эффекты с элемента
- Проверьте, что элемент не содержит растровых изображений
- Проверьте SVG в текстовом редакторе на наличие `<image>` тегов

### Проблема 2: Цвета в RGB, а не CMYK

**Причины:**
- `CmykPdfColorMapper` не используется
- ICC-профиль не применяется

**Решение:**
- Проверьте логи: должно быть "Используется CMYK color mapper"
- Убедитесь, что `applyPdfDefaults()` вызывается
- Проверьте, что ICC-профиль Coated FOGRA39 загружается

### Проблема 3: PDF битый или с артефактами

**Причины:**
- Неправильное объединение документов
- `applyPdfDefaults()` вызывается после объединения

**Решение:**
- Для множественных файлов: каждый PDF формируется полностью, затем объединяется
- `applyPdfDefaults()` вызывается ДО объединения

## Технические детали

### Векторный рендеринг

**Apache Batik** → парсит SVG в GraphicsNode
**PdfBoxGraphics2D** → конвертирует Graphics2D команды в PDF операторы
**CmykPdfColorMapper** → перехватывает все цвета и конвертирует RGB → CMYK

### CMYK конвертация

**ICC-профиль:** Coated FOGRA39 (ISO 12647-2:2004)
**Метод:** `ICC_ColorSpace.fromRGB(rgb)` → CMYK
**Оптимизация:** Чистый чёрный (0,0,0) → (0,0,0,100)

### PDF структура

```
PDF 1.4
├── OutputIntent (Coated FOGRA39)
├── Page 1
│   ├── Resources
│   │   ├── ColorSpace (DeviceCMYK)
│   │   └── XObject (векторный Form)
│   └── Content Stream
│       └── Do /FormXObject (отрисовка векторного объекта)
```

## Вывод

Векторный экспорт PDF в CMYK **ПОЛНОСТЬЮ РЕАЛИЗОВАН**. Если вы видите проблемы:

1. Проверьте логи сервера
2. Убедитесь, что элемент действительно векторный (нет эффектов)
3. Проверьте, что SVG не содержит растровых данных
4. Откройте PDF в Illustrator и проверьте редактируемость

Если после всех проверок векторность не работает, предоставьте:
- Логи сервера
- Скриншот элемента в Figma
- Экспортированный PDF файл
