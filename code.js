const DEFAULT_PPI = 96;
const MM_PER_PX = 25.4 / DEFAULT_PPI;
const PX_PER_MM = DEFAULT_PPI / 25.4;
const DEFAULT_SERVER_URL = 'http://localhost:8080';

const UI_SIZE_PRESETS = {
  small: { width: 320, height: 440 },
  medium: { width: 360, height: 520 },
  large: { width: 420, height: 620 }
};

const DEFAULT_UI_SIZE_KEY = 'medium';
const DEFAULT_UI_SIZE = UI_SIZE_PRESETS[DEFAULT_UI_SIZE_KEY];
const MIN_UI_WIDTH = 280;
const MAX_UI_WIDTH = 720;
const MIN_UI_HEIGHT = 360;
const MAX_UI_HEIGHT = 900;
const SETTINGS_STORAGE_KEY = 'cmyk-tools-ui-settings';

const defaultPreferences = {
  uiScale: 'medium',
  sizePreset: DEFAULT_UI_SIZE_KEY,
  width: DEFAULT_UI_SIZE.width,
  height: DEFAULT_UI_SIZE.height,
  themeOverride: null,
  exportFormat: 'pdf',
  svgTextMode: 'embed',
  tiffPpi: 300,
  tiffQuality: 'standard',
  tiffLzw: true
};

const currentUiSize = {
  width: DEFAULT_UI_SIZE.width,
  height: DEFAULT_UI_SIZE.height
};

const uiPreferences = Object.assign({}, defaultPreferences);

figma.showUI(__html__, {
  width: DEFAULT_UI_SIZE.width,
  height: DEFAULT_UI_SIZE.height,
  themeColors: true
});

function clampUiWidth(value) {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return currentUiSize.width;
  }
  return Math.round(Math.max(MIN_UI_WIDTH, Math.min(MAX_UI_WIDTH, value)));
}

function clampUiHeight(value) {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return currentUiSize.height;
  }
  return Math.round(Math.max(MIN_UI_HEIGHT, Math.min(MAX_UI_HEIGHT, value)));
}

function sendUiDimensions() {
  figma.ui.postMessage({
    type: 'ui-dimensions',
    width: currentUiSize.width,
    height: currentUiSize.height,
    minWidth: MIN_UI_WIDTH,
    maxWidth: MAX_UI_WIDTH,
    minHeight: MIN_UI_HEIGHT,
    maxHeight: MAX_UI_HEIGHT
  });
}

function sendUiPreferences() {
  figma.ui.postMessage({
    type: 'ui-preferences',
    uiScale: uiPreferences.uiScale,
    sizePreset: uiPreferences.sizePreset,
    themeOverride: uiPreferences.themeOverride,
    exportFormat: uiPreferences.exportFormat,
    svgTextMode: uiPreferences.svgTextMode,
    tiffPpi: uiPreferences.tiffPpi,
    tiffQuality: uiPreferences.tiffQuality,
    tiffLzw: uiPreferences.tiffLzw
  });
}

async function updatePreferences(update) {
  const sanitizedUpdate = Object.assign({}, update);
  if (typeof sanitizedUpdate.svgTextMode === 'string') {
    sanitizedUpdate.svgTextMode = sanitizedUpdate.svgTextMode.toLowerCase() === 'outline' ? 'outline' : 'embed';
  }
  Object.assign(uiPreferences, sanitizedUpdate);
  try {
    await figma.clientStorage.setAsync(SETTINGS_STORAGE_KEY, Object.assign({}, uiPreferences));
  } catch (error) {
    // Игнорируем ошибки сохранения настроек.
  }
  sendUiPreferences();
}

function sanitizeName(name, fallback) {
  const trimmed = (name || '').trim();
  const safe = trimmed.replace(/[\\/:*?"<>|]+/g, '_').replace(/\s+/g, '_');
  if (safe.length === 0) {
    return fallback;
  }
  return safe;
}

function nodeSupportsResize(node) {
  return 'resizeWithoutConstraints' in node || 'resize' in node;
}

async function prepareTextNode(node) {
  if (node.type !== 'TEXT') {
    return;
  }
  const length = node.characters.length;
  if (length === 0) {
    return;
  }
  const uniqueFonts = new Map();
  for (const range of node.getRanges('fontName', 0, length)) {
    const font = range.fontName;
    if (font !== figma.mixed && font !== null) {
      const key = `${font.family}__${font.style}`;
      if (!uniqueFonts.has(key)) {
        uniqueFonts.set(key, font);
      }
    }
  }
  for (const font of uniqueFonts.values()) {
    await figma.loadFontAsync(font);
  }
  node.textAutoResize = 'NONE';
}

function getSafeNodeName(node, fallback) {
  const name = typeof node.name === 'string' && node.name.trim().length > 0 ? node.name.trim() : fallback;
  return name;
}

function getNodeBounds(node) {
  const renderBounds = node.absoluteRenderBounds;
  if (renderBounds) {
    return {
      x: renderBounds.x,
      y: renderBounds.y,
      width: renderBounds.width,
      height: renderBounds.height
    };
  }
  const transform = node.absoluteTransform;
  const x = transform[0][2];
  const y = transform[1][2];
  const width = 'width' in node ? node.width : 0;
  const height = 'height' in node ? node.height : 0;
  return { x, y, width, height };
}

function getParentAbsoluteTransform(node) {
  const parent = node.parent;
  if (parent && 'absoluteTransform' in parent) {
    return parent.absoluteTransform;
  }
  return [
    [1, 0, 0],
    [0, 1, 0]
  ];
}

function setNodeAbsolutePosition(node, targetX, targetY) {
  if (!node || typeof node.relativeTransform === 'undefined') {
    throw new Error('Объект нельзя перемещать напрямую.');
  }
  const parentTransform = getParentAbsoluteTransform(node);
  const relative = node.relativeTransform;
  const a = parentTransform[0][0];
  const b = parentTransform[0][1];
  const c = parentTransform[0][2];
  const d = parentTransform[1][0];
  const e = parentTransform[1][1];
  const f = parentTransform[1][2];
  const det = a * e - b * d;
  const translatedX = targetX - c;
  const translatedY = targetY - f;
  let r02;
  let r12;
  if (Math.abs(det) < 1e-8) {
    r02 = translatedX;
    r12 = translatedY;
  } else {
    r02 = (translatedX * e - b * translatedY) / det;
    r12 = (a * translatedY - d * translatedX) / det;
  }
  node.relativeTransform = [
    [relative[0][0], relative[0][1], r02],
    [relative[1][0], relative[1][1], r12]
  ];
}

function axisGap(aStart, aSize, bStart, bSize) {
  const aEnd = aStart + aSize;
  const bEnd = bStart + bSize;
  if (aEnd <= bStart) {
    return bStart - aEnd;
  }
  if (bEnd <= aStart) {
    return aStart - bEnd;
  }
  return 0;
}

function signedAxisDistance(aStart, aSize, bStart, bSize) {
  const aEnd = aStart + aSize;
  const bEnd = bStart + bSize;
  if (aEnd <= bStart) {
    return bStart - aEnd;
  }
  if (bEnd <= aStart) {
    return -(aStart - bEnd);
  }
  return 0;
}

function pxToMm(value) {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return null;
  }
  return value * MM_PER_PX;
}

function collectAutoLayoutInfo(node) {
  if (!node || typeof node.layoutMode !== 'string' || node.layoutMode === 'NONE') {
    return null;
  }
  const layoutWrap = 'layoutWrap' in node ? node.layoutWrap : null;
  const counterAxisSpacing = 'counterAxisSpacing' in node ? node.counterAxisSpacing : undefined;
  const primaryAxisSizingMode = 'primaryAxisSizingMode' in node ? node.primaryAxisSizingMode : null;
  const counterAxisSizingMode = 'counterAxisSizingMode' in node ? node.counterAxisSizingMode : null;
  const primaryAxisAlignItems = 'primaryAxisAlignItems' in node ? node.primaryAxisAlignItems : null;
  const counterAxisAlignItems = 'counterAxisAlignItems' in node ? node.counterAxisAlignItems : null;
  const primaryAxisAlignContent = 'primaryAxisAlignContent' in node ? node.primaryAxisAlignContent : null;
  const counterAxisAlignContent = 'counterAxisAlignContent' in node ? node.counterAxisAlignContent : null;

  const info = {
    layoutMode: node.layoutMode,
    layoutWrap: layoutWrap || 'NO_WRAP',
    primaryAxisSizingMode: primaryAxisSizingMode || null,
    counterAxisSizingMode: counterAxisSizingMode || null,
    primaryAxisAlignItems: primaryAxisAlignItems || null,
    counterAxisAlignItems: counterAxisAlignItems || null,
    primaryAxisAlignContent: primaryAxisAlignContent || null,
    counterAxisAlignContent: counterAxisAlignContent || null,
    itemSpacingMm: pxToMm(node.itemSpacing),
    counterAxisSpacingMm: pxToMm(counterAxisSpacing),
    paddingTopMm: pxToMm(node.paddingTop),
    paddingRightMm: pxToMm(node.paddingRight),
    paddingBottomMm: pxToMm(node.paddingBottom),
    paddingLeftMm: pxToMm(node.paddingLeft),
    itemSpacingAuto: primaryAxisAlignItems === 'SPACE_BETWEEN'
  };

  if (info.itemSpacingAuto) {
    info.itemSpacingMm = null;
  }

  return info;
}

function computeSelectionDistances(selection) {
  if (selection.length < 2) {
    return [];
  }
  const result = [];
  for (let i = 0; i < selection.length; i += 1) {
    const source = selection[i];
    const sourceBounds = getNodeBounds(source);
    const sourceName = getSafeNodeName(source, `Объект ${i + 1}`);
    const sourceCenterX = sourceBounds.x + sourceBounds.width / 2;
    const sourceCenterY = sourceBounds.y + sourceBounds.height / 2;
    for (let j = i + 1; j < selection.length; j += 1) {
      const target = selection[j];
      const targetBounds = getNodeBounds(target);
      const horizontalGapPx = axisGap(sourceBounds.x, sourceBounds.width, targetBounds.x, targetBounds.width);
      const verticalGapPx = axisGap(sourceBounds.y, sourceBounds.height, targetBounds.y, targetBounds.height);
      const deltaX = signedAxisDistance(sourceBounds.x, sourceBounds.width, targetBounds.x, targetBounds.width) * MM_PER_PX;
      const deltaY = signedAxisDistance(sourceBounds.y, sourceBounds.height, targetBounds.y, targetBounds.height) * MM_PER_PX;
      const horizontalGap = horizontalGapPx * MM_PER_PX;
      const verticalGap = verticalGapPx * MM_PER_PX;
      const distance = Math.hypot(horizontalGapPx, verticalGapPx) * MM_PER_PX;
      result.push({
        fromName: sourceName,
        toName: getSafeNodeName(target, `Объект ${j + 1}`),
        deltaX,
        deltaY,
        horizontalGap,
        verticalGap,
        distance
      });
    }
  }
  return result;
}

function sendSelectionInfo() {
  const selection = figma.currentPage.selection;
  const response = {
    type: 'selection-change',
    width: null,
    height: null,
    x: null,
    y: null,
    resizable: false,
    selectionCount: selection.length,
    distances: computeSelectionDistances(selection),
    ratio: null,
    autoLayout: null
  };
  if (selection.length === 1) {
    const node = selection[0];
    if ('width' in node && 'height' in node) {
      const bounds = getNodeBounds(node);
      response.width = bounds.width * MM_PER_PX;
      response.height = bounds.height * MM_PER_PX;
      response.x = bounds.x * MM_PER_PX;
      response.y = bounds.y * MM_PER_PX;
      response.resizable = nodeSupportsResize(node);
      response.ratio = node.height !== 0 ? node.width / node.height : null;
    }
    response.autoLayout = collectAutoLayoutInfo(node);
  }
  figma.ui.postMessage(response);
}

function sendThemeInfo() {
  const theme = figma.currentTheme;
  const colorScheme = theme && typeof theme.colorScheme === 'string'
    ? theme.colorScheme.toLowerCase()
    : 'light';
  figma.ui.postMessage({ type: 'theme-change', colorScheme });
}

figma.on('selectionchange', () => {
  sendSelectionInfo();
});

function handleDocumentChange(event) {
  const selection = figma.currentPage.selection;
  if (selection.length === 0) {
    return;
  }
  const selectionIds = new Set(selection.map((node) => node.id));
  for (const change of event.documentChanges) {
    if (change.type === 'PROPERTY_CHANGE' && selectionIds.has(change.id)) {
      sendSelectionInfo();
      break;
    }
  }
}

(async () => {
  try {
    if ('loadAllPagesAsync' in figma && typeof figma.loadAllPagesAsync === 'function') {
      await figma.loadAllPagesAsync();
    }
    figma.on('documentchange', handleDocumentChange);
  } catch (error) {
    // Если API не поддерживает загрузку всех страниц или подписку, пропускаем доп. обновление.
  }
})();

if ('on' in figma && typeof figma.on === 'function') {
  try {
    figma.on('currentthemechange', () => {
      sendThemeInfo();
    });
  } catch (error) {
    // Игнорируем, если текущая версия API не поддерживает событие смены темы.
  }
}

sendThemeInfo();
sendSelectionInfo();
sendUiDimensions();

(async () => {
  try {
    const stored = await figma.clientStorage.getAsync(SETTINGS_STORAGE_KEY);
    if (stored && typeof stored === 'object') {
      if (typeof stored.uiScale === 'string') {
        uiPreferences.uiScale = stored.uiScale;
      }
      if (typeof stored.sizePreset === 'string') {
        uiPreferences.sizePreset = stored.sizePreset;
      }
      if (typeof stored.width === 'number') {
        uiPreferences.width = clampUiWidth(stored.width);
      }
      if (typeof stored.height === 'number') {
        uiPreferences.height = clampUiHeight(stored.height);
      }
      if (typeof stored.themeOverride === 'string' || stored.themeOverride === null) {
        uiPreferences.themeOverride = stored.themeOverride;
      }
      if (typeof stored.svgTextMode === 'string') {
        uiPreferences.svgTextMode = stored.svgTextMode.toLowerCase() === 'outline' ? 'outline' : 'embed';
      }
      if (typeof stored.exportFormat === 'string') {
        uiPreferences.exportFormat = stored.exportFormat;
      }
      if (typeof stored.tiffPpi === 'number') {
        uiPreferences.tiffPpi = stored.tiffPpi;
      }
      if (typeof stored.tiffQuality === 'string') {
        uiPreferences.tiffQuality = stored.tiffQuality;
      }
      if (typeof stored.tiffLzw === 'boolean') {
        uiPreferences.tiffLzw = stored.tiffLzw;
      }
    }
  } catch (error) {
    // Игнорируем ошибки загрузки настроек.
  }
  currentUiSize.width = clampUiWidth(uiPreferences.width);
  currentUiSize.height = clampUiHeight(uiPreferences.height);
  figma.ui.resize(currentUiSize.width, currentUiSize.height);
  sendUiDimensions();
  sendUiPreferences();
})();

async function handleSizeUpdate(widthMm, heightMm) {
  const selection = figma.currentPage.selection;
  if (selection.length !== 1) {
    throw new Error('Выберите один объект для изменения размеров.');
  }
  const node = selection[0];
  if (!('width' in node && 'height' in node)) {
    throw new Error('Текущий объект не поддерживает изменение размеров.');
  }
  if (!nodeSupportsResize(node)) {
    throw new Error('Объект нельзя масштабировать напрямую.');
  }
  const targetWidth = Math.max(widthMm * PX_PER_MM, 0.1);
  const targetHeight = Math.max(heightMm * PX_PER_MM, 0.1);
  if (node.type === 'TEXT') {
    await prepareTextNode(node);
  }
  if ('resizeWithoutConstraints' in node) {
    node.resizeWithoutConstraints(targetWidth, targetHeight);
  } else if ('resize' in node) {
    node.resize(targetWidth, targetHeight);
  }
  sendSelectionInfo();
}

async function handlePositionUpdate(positionMm) {
  const selection = figma.currentPage.selection;
  if (selection.length !== 1) {
    throw new Error('Выберите один объект для перемещения.');
  }
  const node = selection[0];
  if (!node || typeof node.relativeTransform === 'undefined') {
    throw new Error('Текущий объект нельзя перемещать напрямую.');
  }
  const bounds = getNodeBounds(node);
  let targetX = bounds.x;
  let targetY = bounds.y;
  if (positionMm && typeof positionMm.x === 'number' && !Number.isNaN(positionMm.x)) {
    targetX = positionMm.x * PX_PER_MM;
  }
  if (positionMm && typeof positionMm.y === 'number' && !Number.isNaN(positionMm.y)) {
    targetY = positionMm.y * PX_PER_MM;
  }
  setNodeAbsolutePosition(node, targetX, targetY);
  sendSelectionInfo();
}

async function handleAutoLayoutUpdate(params) {
  const selection = figma.currentPage.selection;
  if (selection.length !== 1) {
    throw new Error('Выберите один объект с автолейаутом.');
  }
  const node = selection[0];
  if (!node || typeof node.layoutMode !== 'string' || node.layoutMode === 'NONE') {
    throw new Error('Выбранный объект не имеет автолейаута.');
  }
  
  if (typeof params.itemSpacingMm === 'number' && !Number.isNaN(params.itemSpacingMm)) {
    const spacingPx = Math.max(0, params.itemSpacingMm * PX_PER_MM);
    node.itemSpacing = spacingPx;
  }
  
  if (typeof params.counterAxisSpacingMm === 'number' && !Number.isNaN(params.counterAxisSpacingMm) && 'counterAxisSpacing' in node) {
    const counterSpacingPx = Math.max(0, params.counterAxisSpacingMm * PX_PER_MM);
    node.counterAxisSpacing = counterSpacingPx;
  }
  
  if (typeof params.paddingTopMm === 'number' && !Number.isNaN(params.paddingTopMm)) {
    node.paddingTop = Math.max(0, params.paddingTopMm * PX_PER_MM);
  }
  
  if (typeof params.paddingRightMm === 'number' && !Number.isNaN(params.paddingRightMm)) {
    node.paddingRight = Math.max(0, params.paddingRightMm * PX_PER_MM);
  }
  
  if (typeof params.paddingBottomMm === 'number' && !Number.isNaN(params.paddingBottomMm)) {
    node.paddingBottom = Math.max(0, params.paddingBottomMm * PX_PER_MM);
  }
  
  if (typeof params.paddingLeftMm === 'number' && !Number.isNaN(params.paddingLeftMm)) {
    node.paddingLeft = Math.max(0, params.paddingLeftMm * PX_PER_MM);
  }
  
  sendSelectionInfo();
}

function isVectorNode(node) {
  // Растровые типы: IMAGE
  if (node.type === 'IMAGE') {
    return false;
  }
  
  // Векторные типы: VECTOR, LINE, ELLIPSE, POLYGON, STAR, RECTANGLE, TEXT, FRAME, GROUP
  const vectorTypes = ['VECTOR', 'LINE', 'ELLIPSE', 'POLYGON', 'STAR', 'RECTANGLE', 'TEXT', 'FRAME', 'GROUP', 'COMPONENT', 'INSTANCE', 'BOOLEAN_OPERATION'];
  if (vectorTypes.includes(node.type)) {
    return true;
  }
  
  // Для остальных типов считаем векторными по умолчанию
  return true;
}

function hasRasterEffects(node) {
  // Проверяем наличие эффектов, которые требуют растеризации
  if ('effects' in node && node.effects && node.effects.length > 0) {
    const rasterEffects = node.effects.filter(effect => 
      effect.visible && (effect.type === 'DROP_SHADOW' || effect.type === 'INNER_SHADOW' || effect.type === 'LAYER_BLUR' || effect.type === 'BACKGROUND_BLUR')
    );
    if (rasterEffects.length > 0) {
      return true;
    }
  }
  if ('fills' in node && node.fills && node.fills !== figma.mixed) {
    const fills = Array.isArray(node.fills) ? node.fills : [];
    const hasImageFill = fills.some((fill) => fill && fill.visible !== false && fill.type === 'IMAGE');
    if (hasImageFill) {
      return true;
    }
  }
  if ('backgrounds' in node && node.backgrounds && node.backgrounds !== figma.mixed) {
    const backgrounds = Array.isArray(node.backgrounds) ? node.backgrounds : [];
    const hasImageBackground = backgrounds.some((bg) => bg && bg.visible !== false && bg.type === 'IMAGE');
    if (hasImageBackground) {
      return true;
    }
  }
  return false;
}

async function exportSelection(settings) {
  const exportFormat = settings && typeof settings.format === 'string' ? settings.format : 'pdf';
  const basePpi = DEFAULT_PPI;
  const requestedPpi = settings && typeof settings.ppi === 'number'
    ? Math.max(settings.ppi, 1)
    : basePpi;
  const svgTextMode = settings && typeof settings.svgTextMode === 'string'
    ? (settings.svgTextMode.toLowerCase() === 'outline' ? 'outline' : 'embed')
    : 'embed';
  const tiffQuality = settings && typeof settings.tiffQuality === 'string'
    ? settings.tiffQuality
    : 'standard';
  const useTiffLzw = !!(settings && settings.tiffLzw);
  const serverUrl = settings && typeof settings.serverUrl === 'string' && settings.serverUrl.trim().length > 0
    ? settings.serverUrl
    : (typeof DEFAULT_SERVER_URL === 'undefined' ? '' : DEFAULT_SERVER_URL);
  const exportScale = exportFormat === 'tiff'
    ? Math.max(requestedPpi / DEFAULT_PPI, 0.01)
    : 1;
  const selection = figma.currentPage.selection;
  if (selection.length === 0) {
    throw new Error('Нет выделенных объектов для экспорта.');
  }
  const exported = [];
  for (let i = 0; i < selection.length; i += 1) {
    const node = selection[i];
    if (!('exportAsync' in node)) {
      continue;
    }
    const isPdfExport = exportFormat === 'pdf';
    const isVector = isPdfExport && isVectorNode(node) && !hasRasterEffects(node);
    const rasterScale = isPdfExport
      ? Math.max(requestedPpi / DEFAULT_PPI, 0.01)
      : exportScale;
    const exportSettings = (() => {
      if (isPdfExport) {
        if (isVector) {
          return {
            format: 'SVG',
            useAbsoluteBounds: true,
            svgOutlineText: svgTextMode === 'outline'
          };
        }
        const pngSettings = {
          format: 'PNG',
          useAbsoluteBounds: true
        };
        if (Math.abs(rasterScale - 1) > 0.0001) {
          pngSettings.constraint = { type: 'SCALE', value: rasterScale };
        }
        return pngSettings;
      }
      const pngSettings = {
        format: 'PNG',
        useAbsoluteBounds: true
      };
      if (Math.abs(exportScale - 1) > 0.0001) {
        pngSettings.constraint = { type: 'SCALE', value: exportScale };
      }
      return pngSettings;
    })();
    const bytes = await node.exportAsync(exportSettings);
    const baseName = sanitizeName(node.name, `export_${i + 1}`);
    const bounds = node.absoluteRenderBounds;
    const baseWidth = bounds ? bounds.width : (node.width || 0);
    const baseHeight = bounds ? bounds.height : (node.height || 0);
    const widthPx = Math.max(1, Math.round(baseWidth * (isPdfExport ? (isVector ? 1 : rasterScale) : exportScale)));
    const heightPx = Math.max(1, Math.round(baseHeight * (isPdfExport ? (isVector ? 1 : rasterScale) : exportScale)));
    exported.push({
      name: baseName,
      data: bytes,
      widthPx,
      heightPx,
      fileKind: isPdfExport ? (isVector ? 'svg' : 'png') : 'png'
    });
  }
  if (exported.length === 0) {
    throw new Error('Не удалось экспортировать выделенные объекты.');
  }
  const effectivePpi = requestedPpi;
  return {
    items: exported,
    format: exportFormat,
    ppi: effectivePpi,
    useServer: true,
    serverUrl,
    tiffLzw: exportFormat === 'tiff' && useTiffLzw,
    tiffQuality: exportFormat === 'tiff' ? tiffQuality : 'standard',
    svgTextMode
  };
}

async function exportViaServer(items, options) {
  const {
    format,
    ppi,
    serverUrl,
    tiffLzw,
    tiffQuality,
    svgTextMode
  } = options;

  const formData = new FormData();
  formData.append('format', format);
  formData.append('ppi', ppi);
  formData.append('tiffLzw', tiffLzw ? 'true' : 'false');
  formData.append('tiffQuality', tiffQuality || 'standard');
  const normalizedTextMode = typeof svgTextMode === 'string' ? svgTextMode.toLowerCase() : 'embed';
  formData.append('svgTextMode', normalizedTextMode === 'outline' ? 'outline' : 'embed');
  if (normalizedTextMode === 'outline') {
    formData.append('svgOutlineText', 'true');
  }

  for (let i = 0; i < items.length; i += 1) {
    const item = items[i];
    const file = new File([item.data], item.name, { type: `image/${item.fileKind}` });
    formData.append(`file_${i}`, file);
  }

  const response = await fetch(serverUrl, {
    method: 'POST',
    body: formData
  });

  if (!response.ok) {
    throw new Error(`Ошибка экспорта: ${response.statusText}`);
  }

  const result = await response.json();
  return result;
}

figma.ui.onmessage = async (message) => {
  if (!message || typeof message !== 'object') {
    return;
  }
  switch (message.type) {
    case 'apply-size-mm': {
      try {
        await handleSizeUpdate(message.width, message.height);
        figma.ui.postMessage({ type: 'size-update', success: true });
      } catch (error) {
        const errMsg = error instanceof Error ? error.message : 'Не удалось изменить размеры.';
        figma.ui.postMessage({ type: 'size-update', success: false, error: errMsg });
      }
      break;
    }
    case 'apply-position-mm': {
      try {
        await handlePositionUpdate({ x: message.x, y: message.y });
        figma.ui.postMessage({ type: 'position-update', success: true });
      } catch (error) {
        const errMsg = error instanceof Error ? error.message : 'Не удалось переместить объект.';
        figma.ui.postMessage({ type: 'position-update', success: false, error: errMsg });
      }
      break;
    }
    case 'export-cmyk': {
      try {
        const result = await exportSelection(message);
        figma.ui.postMessage({
          type: 'export-result',
          success: true,
          items: result.items,
          format: result.format,
          ppi: result.ppi,
          useServer: result.useServer,
          serverUrl: result.serverUrl,
          tiffLzw: result.tiffLzw,
          tiffQuality: result.tiffQuality,
          svgTextMode: result.svgTextMode
        });
      } catch (error) {
        const errMsg = error instanceof Error ? error.message : 'Ошибка экспорта.';
        figma.ui.postMessage({ type: 'export-result', success: false, error: errMsg });
      }
      break;
    }
    case 'set-ui-size': {
      const sizeKey = typeof message.size === 'string' ? message.size : DEFAULT_UI_SIZE_KEY;
      const preset = UI_SIZE_PRESETS[sizeKey] || DEFAULT_UI_SIZE;
      const targetWidth = clampUiWidth(preset.width);
      const targetHeight = clampUiHeight(preset.height);
      figma.ui.resize(targetWidth, targetHeight);
      currentUiSize.width = targetWidth;
      currentUiSize.height = targetHeight;
      await updatePreferences({ sizePreset: sizeKey, width: targetWidth, height: targetHeight });
      sendUiDimensions();
      break;
    }
    case 'set-ui-width': {
      const targetWidth = clampUiWidth(message.width);
      if (targetWidth !== currentUiSize.width) {
        figma.ui.resize(targetWidth, currentUiSize.height);
        currentUiSize.width = targetWidth;
        await updatePreferences({ width: targetWidth });
        sendUiDimensions();
      }
      break;
    }
    case 'set-ui-height': {
      const targetHeight = clampUiHeight(message.height);
      if (targetHeight !== currentUiSize.height) {
        figma.ui.resize(currentUiSize.width, targetHeight);
        currentUiSize.height = targetHeight;
        await updatePreferences({ height: targetHeight });
        sendUiDimensions();
      }
      break;
    }
    case 'set-ui-scale': {
      const scale = typeof message.scale === 'string' ? message.scale : defaultPreferences.uiScale;
      await updatePreferences({ uiScale: scale });
      break;
    }
    case 'set-theme-override': {
      const theme = typeof message.theme === 'string' ? message.theme : null;
      await updatePreferences({ themeOverride: theme });
      break;
    }
    case 'update-preferences': {
      const update = {};
      if (typeof message.exportFormat === 'string') {
        update.exportFormat = message.exportFormat;
      }
      if (typeof message.svgTextMode === 'string') {
        update.svgTextMode = message.svgTextMode;
      }
      if (typeof message.tiffPpi === 'number') {
        update.tiffPpi = message.tiffPpi;
      }
      if (typeof message.tiffQuality === 'string') {
        update.tiffQuality = message.tiffQuality;
      }
      if (typeof message.tiffLzw === 'boolean') {
        update.tiffLzw = message.tiffLzw;
      }
      if (Object.keys(update).length > 0) {
        await updatePreferences(update);
      }
      break;
    }
    case 'request-theme': {
      sendThemeInfo();
      break;
    }
    case 'request-ui-dimensions': {
      sendUiDimensions();
      break;
    }
    case 'request-ui-preferences': {
      sendUiPreferences();
      break;
    }
    case 'apply-auto-layout': {
      try {
        await handleAutoLayoutUpdate(message);
        figma.ui.postMessage({ type: 'auto-layout-update', success: true });
      } catch (error) {
        const errMsg = error instanceof Error ? error.message : 'Не удалось изменить автолейаут.';
        figma.ui.postMessage({ type: 'auto-layout-update', success: false, error: errMsg });
      }
      break;
    }
    default:
      break;
  }
};
