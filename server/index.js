const express = require('express');
const cors = require('cors');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn } = require('child_process');
const { PNG } = require('pngjs');
const sharp = require('sharp');

const app = express();
app.use(cors());

if (!process.env.CASC_ADMIN_NOTICE_SHOWN) {
  console.log('Для конвертации PDF в CMYK требуется установленный Ghostscript (пакет ghostscript в Docker).');
}

const PROFILE_PATH = path.join(__dirname, '..', 'CoatedFOGRA39.icc');
let coatedFograProfileBytes = null;
const DEFAULT_DPI = 96;

function loadIccProfile() {
  if (!coatedFograProfileBytes) {
    coatedFograProfileBytes = fs.readFileSync(PROFILE_PATH);
  }
  return coatedFograProfileBytes;
}

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 200 * 1024 * 1024 }
});

const PDF_STANDARD_CONFIG = {
  'PDF/X-1a:2001': { version: '1.3', pdfxVersion: 'PDF/X-1:2001', conformance: 'PDF/X-1a:2001' },
  'PDF/X-3:2002': { version: '1.3', pdfxVersion: 'PDF/X-3:2002', conformance: 'PDF/X-3:2002' },
  'PDF/X-3:2003': { version: '1.3', pdfxVersion: 'PDF/X-3:2003', conformance: 'PDF/X-3:2003' },
  'PDF/X-4:2008': { version: '1.6', pdfxVersion: 'PDF/X-4', conformance: 'PDF/X-4:2008' }
};

function ensurePdfVersion(requested, minimum) {
  if (!minimum) {
    return requested;
  }
  const requestedValue = parseFloat(requested);
  const minimumValue = parseFloat(minimum);
  if (Number.isNaN(requestedValue) || requestedValue < minimumValue) {
    return minimum;
  }
  return requested;
}

function pdfString(value) {
  const text = String(value == null ? '' : value);
  return `(${text.replace(/[\\()]/g, '\\$&')})`;
}

function pad10(num) {
  return num.toString().padStart(10, '0');
}

function bufferFromChunks(chunks) {
  return Buffer.concat(chunks);
}

async function convertSvgToPdf(svgBuffer, { widthPx, heightPx }) {
  const fsAsync = fs.promises;
  const tempDir = await fsAsync.mkdtemp(path.join(os.tmpdir(), 'svg-to-pdf-'));
  const inputPath = path.join(tempDir, 'input.svg');
  const outputPath = path.join(tempDir, 'output.pdf');

  try {
    await fsAsync.writeFile(inputPath, svgBuffer);

    const args = ['-f', 'pdf', '-o', outputPath, inputPath];
    const widthValue = Number.parseInt(widthPx, 10);
    const heightValue = Number.parseInt(heightPx, 10);
    if (Number.isFinite(widthValue) && widthValue > 0) {
      args.unshift(widthValue.toString(), '-w');
    }
    if (Number.isFinite(heightValue) && heightValue > 0) {
      args.unshift(heightValue.toString(), '-h');
    }

    await new Promise((resolve, reject) => {
      const rsvgProcess = spawn('rsvg-convert', args, { stdio: ['ignore', 'ignore', 'pipe'] });
      let stderr = '';

      rsvgProcess.stderr.on('data', (data) => {
        stderr += data.toString();
      });

      rsvgProcess.on('error', (error) => {
        if (error.code === 'ENOENT') {
          reject(new Error('rsvg-convert не установлен или недоступен. Установите пакет librsvg2-bin.'));
        } else {
          reject(error);
        }
      });

      rsvgProcess.on('close', (code) => {
        if (code !== 0) {
          reject(new Error(`rsvg-convert завершился с кодом ${code}.${stderr ? ` Детали: ${stderr.trim()}` : ''}`));
        } else {
          resolve();
        }
      });
    });

    const pdfBuffer = await fsAsync.readFile(outputPath);
    return pdfBuffer;
  } finally {
    try {
      await fsAsync.rm(tempDir, { recursive: true, force: true });
    } catch (cleanupError) {
      console.warn('Не удалось удалить временную директорию преобразования SVG:', cleanupError);
    }
  }
}

async function convertPdfToCmyk(pdfBuffer, { pdfVersion, pdfStandard }) {
  const fsAsync = fs.promises;
  const tempDir = await fsAsync.mkdtemp(path.join(os.tmpdir(), 'cmyk-pdf-'));
  const inputPath = path.join(tempDir, 'input.pdf');
  const outputPath = path.join(tempDir, 'output.pdf');

  try {
    await fsAsync.writeFile(inputPath, pdfBuffer);

    const selectedStandard = pdfStandard && pdfStandard !== 'none' ? pdfStandard : 'none';
    const standardConfig = PDF_STANDARD_CONFIG[selectedStandard] || null;
    const requestedVersion = pdfVersion || '1.4';
    const effectiveVersion = standardConfig
      ? ensurePdfVersion(requestedVersion, standardConfig.version)
      : requestedVersion;

    const profilePath = PROFILE_PATH.replace(/\\/g, '/');
    const normalizedOutputPath = outputPath.replace(/\\/g, '/');
    const normalizedInputPath = inputPath.replace(/\\/g, '/');

    const args = [
      '-dSAFER',
      '-dBATCH',
      '-dNOPAUSE',
      '-dNOPROMPT',
      '-sDEVICE=pdfwrite',
      `-dCompatibilityLevel=${effectiveVersion}`,
      '-dProcessColorModel=/DeviceCMYK',
      '-sColorConversionStrategy=CMYK',
      '-sColorConversionStrategyForImages=CMYK',
      '-dOverrideICC',
      '-dUseCIEColor',
      `-sOutputICCProfile=${profilePath}`,
      `-sOutputFile=${normalizedOutputPath}`,
      normalizedInputPath
    ];

    await new Promise((resolve, reject) => {
      const gsProcess = spawn('gs', args, { stdio: ['ignore', 'ignore', 'pipe'] });
      let stderr = '';

      gsProcess.stderr.on('data', (data) => {
        stderr += data.toString();
      });

      gsProcess.on('error', (error) => {
        if (error.code === 'ENOENT') {
          reject(new Error('Ghostscript не установлен или недоступен в PATH. Установите Ghostscript и перезапустите сервер.'));
        } else {
          reject(error);
        }
      });

      gsProcess.on('close', (code) => {
        if (code !== 0) {
          reject(new Error(`Ghostscript завершился с кодом ${code}.${stderr ? ` Детали: ${stderr.trim()}` : ''}`));
        } else {
          resolve();
        }
      });
    });

    const result = await fsAsync.readFile(outputPath);
    return result;
  } finally {
    try {
      await fsAsync.rm(tempDir, { recursive: true, force: true });
    } catch (cleanupError) {
      console.warn('Не удалось удалить временную директорию преобразования PDF:', cleanupError);
    }
  }
}

function lzwEncodeBuffer(buffer) {
  if (!buffer || buffer.length === 0) {
    return Buffer.from([128]);
  }

  const CLEAR_CODE = 256;
  const EOI_CODE = 257;
  const MAX_BITS = 12;
  const MAX_CODE = (1 << MAX_BITS) - 1;

  let dictionary = new Map();
  function resetDictionary() {
    dictionary.clear();
    for (let i = 0; i < 256; i += 1) {
      dictionary.set(String.fromCharCode(i), i);
    }
  }

  resetDictionary();

  let codeSize = 9;
  let nextCode = 258;
  let bitBuffer = 0;
  let bitsInBuffer = 0;
  const output = [];

  function writeCode(code) {
    bitBuffer = (bitBuffer << codeSize) | code;
    bitsInBuffer += codeSize;
    while (bitsInBuffer >= 8) {
      bitsInBuffer -= 8;
      output.push((bitBuffer >> bitsInBuffer) & 0xff);
    }
    bitBuffer &= (1 << bitsInBuffer) - 1;
  }

  writeCode(CLEAR_CODE);

  let current = String.fromCharCode(buffer[0]);
  for (let i = 1; i < buffer.length; i += 1) {
    const char = String.fromCharCode(buffer[i]);
    const combined = current + char;
    if (dictionary.has(combined)) {
      current = combined;
    } else {
      writeCode(dictionary.get(current));
      if (nextCode <= MAX_CODE) {
        dictionary.set(combined, nextCode);
        nextCode += 1;
        if (nextCode === (1 << codeSize) && codeSize < MAX_BITS) {
          codeSize += 1;
        }
      } else {
        writeCode(CLEAR_CODE);
        resetDictionary();
        codeSize = 9;
        nextCode = 258;
      }
      current = char;
    }
  }

  writeCode(dictionary.get(current));
  writeCode(EOI_CODE);

  if (bitsInBuffer > 0) {
    output.push((bitBuffer << (8 - bitsInBuffer)) & 0xff);
  }

  return Buffer.from(output);
}

function runLengthEncode(data) {
  if (!data || data.length === 0) {
    return Buffer.from([128]);
  }
  const output = [];
  const literals = [];

  function flushLiterals() {
    if (literals.length === 0) {
      return;
    }
    output.push(literals.length - 1);
    for (let i = 0; i < literals.length; i += 1) {
      output.push(literals[i]);
    }
    literals.length = 0;
  }

  let index = 0;
  const length = data.length;
  while (index < length) {
    let runLength = 1;
    while (runLength < 128 && index + runLength < length && data[index] === data[index + runLength]) {
      runLength += 1;
    }
    if (runLength > 1) {
      flushLiterals();
      output.push(257 - runLength);
      output.push(data[index]);
      index += runLength;
    } else {
      literals.push(data[index]);
      if (literals.length === 128) {
        flushLiterals();
      }
      index += 1;
    }
  }

  flushLiterals();
  output.push(128);
  return Buffer.from(output);
}

function lzwEncode(data) {
  const CLEAR_CODE = 256;
  const EOI_CODE = 257;
  const MAX_BITS = 12;
  const MAX_CODE = (1 << MAX_BITS) - 1;

  let dictionary = new Map();

  function resetDictionary() {
    dictionary = new Map();
    for (let i = 0; i < 256; i += 1) {
      dictionary.set(String.fromCharCode(i), i);
    }
  }

  resetDictionary();

  let codeSize = 9;
  let nextCode = 258;
  const buffer = [];
  let bitBuffer = 0;
  let bitsInBuffer = 0;

  function writeCode(code) {
    bitBuffer |= code << bitsInBuffer;
    bitsInBuffer += codeSize;
    while (bitsInBuffer >= 8) {
      buffer.push(bitBuffer & 0xff);
      bitBuffer >>= 8;
      bitsInBuffer -= 8;
    }
  }

  writeCode(CLEAR_CODE);

  let current = String.fromCharCode(data[0]);
  for (let i = 1; i < data.length; i += 1) {
    const char = String.fromCharCode(data[i]);
    const combined = current + char;
    if (dictionary.has(combined)) {
      current = combined;
    } else {
      writeCode(dictionary.get(current));
      if (nextCode <= MAX_CODE) {
        dictionary.set(combined, nextCode);
        nextCode += 1;
        if (nextCode === (1 << codeSize) && codeSize < MAX_BITS) {
          codeSize += 1;
        }
      } else {
        writeCode(CLEAR_CODE);
        resetDictionary();
        codeSize = 9;
        nextCode = 258;
      }
      current = char;
    }
  }

  writeCode(dictionary.get(current));
  writeCode(EOI_CODE);

  if (bitsInBuffer > 0) {
    buffer.push(bitBuffer & 0xff);
  }

  return Buffer.from(buffer);
}

function buildPDF({
  cmykData,
  alphaData,
  hasTransparency,
  widthPx,
  heightPx,
  dpi,
  pdfVersion,
  pdfStandard,
  pdfCompression
}) {
  const profileBytes = loadIccProfile();
  const requestedVersion = pdfVersion || '1.4';
  const selectedStandard = pdfStandard && pdfStandard !== 'none' ? pdfStandard : 'none';
  const standardConfig = PDF_STANDARD_CONFIG[selectedStandard] || null;
  let effectiveVersion = standardConfig ? ensurePdfVersion(requestedVersion, standardConfig.version) : requestedVersion;
  const transparencyRequested = hasTransparency && alphaData && alphaData.length === widthPx * heightPx;
  if (transparencyRequested && parseFloat(effectiveVersion) < 1.4) {
    effectiveVersion = '1.4';
  }
  const transparencyAllowed = transparencyRequested && (selectedStandard === 'none' || selectedStandard === 'PDF/X-4:2008');
  if (transparencyRequested && !transparencyAllowed) {
    console.warn('Transparency requested but not supported for selected PDF standard; output will be flattened.');
  }
  const header = `%PDF-${effectiveVersion}\n%âãÏÓ\n`;

  const effectiveDpi = Math.max(parseFloat(dpi) || DEFAULT_DPI, 1);
  const widthPt = (widthPx / effectiveDpi) * 72;
  const heightPt = (heightPx / effectiveDpi) * 72;

  const hasStandard = !!standardConfig;
  if (hasStandard && (!profileBytes || profileBytes.length === 0)) {
    throw new Error('ICC профиль Coated FOGRA39 недоступен на сервере.');
  }

  const objects = [];

  function addPlainObject(id, body) {
    objects.push({ id, parts: [Buffer.from(`${id} 0 obj\n${body}\nendobj\n`, 'utf8')] });
  }

  function addStreamObject(id, dict, streamBuffer) {
    objects.push({
      id,
      parts: [
        Buffer.from(`${id} 0 obj\n${dict}\nstream\n`, 'utf8'),
        Buffer.isBuffer(streamBuffer) ? streamBuffer : Buffer.from(streamBuffer),
        Buffer.from('\nendstream\nendobj\n', 'utf8')
      ]
    });
  }

  const CATALOG_ID = 1;
  const PAGES_ID = 2;
  const PAGE_ID = 3;
  const CONTENT_ID = 4;
  const IMAGE_ID = 5;
  let nextObjectId = 6;

  let profileObjectId = null;
  if (hasStandard) {
    profileObjectId = nextObjectId;
    nextObjectId += 1;
  }

  let outputIntentObjectId = null;
  if (hasStandard) {
    outputIntentObjectId = nextObjectId;
    nextObjectId += 1;
  }

  let infoObjectId = null;
  if (hasStandard) {
    infoObjectId = nextObjectId;
    nextObjectId += 1;
  }

  let smaskObjectId = null;
  if (transparencyAllowed) {
    smaskObjectId = nextObjectId;
    nextObjectId += 1;
  }

  const catalogFields = ['/Type /Catalog', '/Pages 2 0 R'];
  if (hasStandard) {
    catalogFields.push('/Trapped false');
    if (outputIntentObjectId) {
      catalogFields.push(`/OutputIntents [${outputIntentObjectId} 0 R]`);
    }
  }
  addPlainObject(CATALOG_ID, `<< ${catalogFields.join(' ')} >>`);

  addPlainObject(PAGES_ID, '<< /Type /Pages /Count 1 /Kids [3 0 R] >>');

  addPlainObject(
    PAGE_ID,
    `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${widthPt.toFixed(2)} ${heightPt.toFixed(2)}] /Contents 4 0 R /Resources << /ProcSet [/PDF /ImageC] /XObject << /Im0 5 0 R >> >> >>`
  );

  const contentStream = `q\n${widthPt.toFixed(2)} 0 0 ${heightPt.toFixed(2)} 0 0 cm\n/Im0 Do\nQ\n`;
  const contentBytes = Buffer.from(contentStream, 'utf8');
  addStreamObject(CONTENT_ID, `<< /Length ${contentBytes.length} >>`, contentBytes);

  const compression = pdfCompression || 'none';
  let imageStream = Buffer.from(cmykData);
  const imageDictParts = [
    '/Type /XObject',
    '/Subtype /Image',
    `/Width ${widthPx}`,
    `/Height ${heightPx}`,
    '/BitsPerComponent 8'
  ];

  if (hasStandard) {
    imageDictParts.push(`/ColorSpace [ /ICCBased ${profileObjectId} 0 R ]`);
  } else {
    imageDictParts.push('/ColorSpace /DeviceCMYK');
  }

  if (smaskObjectId !== null) {
    imageDictParts.push(`/SMask ${smaskObjectId} 0 R`);
  }

  if (compression === 'runLength') {
    imageStream = runLengthEncode(imageStream);
    imageDictParts.push('/Filter /RunLengthDecode');
  } else if (compression === 'lzw') {
    imageStream = lzwEncodeBuffer(imageStream);
    imageDictParts.push('/Filter /LZWDecode');
    imageDictParts.push('/DecodeParms << /EarlyChange 1 >>');
  }

  if (!hasStandard) {
    // Add ICC profile even without PDF/X to preserve color intent
    profileObjectId = nextObjectId;
    nextObjectId += 1;
    addStreamObject(profileObjectId, `<< /N 4 /Alternate /DeviceCMYK /Length ${profileBytes.length} >>`, profileBytes);
    imageDictParts.push(`/ColorSpace [ /ICCBased ${profileObjectId} 0 R ]`);
  }

  imageDictParts.push(`/Length ${imageStream.length}`);
  addStreamObject(IMAGE_ID, `<< ${imageDictParts.join(' ')} >>`, imageStream);

  if (smaskObjectId !== null) {
    let smaskStream = Buffer.from(alphaData);
    const smaskDictParts = [
      '/Type /XObject',
      '/Subtype /Image',
      `/Width ${widthPx}`,
      `/Height ${heightPx}`,
      '/ColorSpace /DeviceGray',
      '/BitsPerComponent 8',
      '/Decode [0 1]'
    ];
    if (compression === 'runLength') {
      smaskStream = runLengthEncode(smaskStream);
      smaskDictParts.push('/Filter /RunLengthDecode');
    } else if (compression === 'lzw') {
      smaskStream = lzwEncodeBuffer(smaskStream);
      smaskDictParts.push('/Filter /LZWDecode');
      smaskDictParts.push('/DecodeParms << /EarlyChange 1 >>');
    }
    smaskDictParts.push(`/Length ${smaskStream.length}`);
    addStreamObject(smaskObjectId, `<< ${smaskDictParts.join(' ')} >>`, smaskStream);
  }

  if (hasStandard) {
    addStreamObject(profileObjectId, `<< /N 4 /Alternate /DeviceCMYK /Length ${profileBytes.length} >>`, profileBytes);

    const outputIntentFields = [
      '/Type /OutputIntent',
      '/S /GTS_PDFX',
      `/OutputConditionIdentifier ${pdfString('Coated FOGRA39')}`,
      `/Info ${pdfString('Coated FOGRA39')}`,
      '/RegistryName (http://www.color.org)',
      `/DestOutputProfile ${profileObjectId} 0 R`
    ];
    addPlainObject(outputIntentObjectId, `<< ${outputIntentFields.join(' ')} >>`);

    const infoFields = [
      `/Producer ${pdfString('CMYK Export Server')}`,
      `/Creator ${pdfString('CMYK Export Server')}`
    ];
    if (standardConfig) {
      infoFields.push(`/GTS_PDFXVersion ${pdfString(standardConfig.pdfxVersion)}`);
      infoFields.push(`/GTS_PDFXConformance ${pdfString(standardConfig.conformance)}`);
    }
    addPlainObject(infoObjectId, `<< ${infoFields.join(' ')} >>`);
  }

  const headerBytes = Buffer.from(header, 'utf8');
  const chunks = [headerBytes];
  const xrefOffsets = [0];
  let offset = headerBytes.length;

  const sortedObjects = objects.sort((a, b) => a.id - b.id);
  for (const object of sortedObjects) {
    xrefOffsets.push(offset);
    for (const part of object.parts) {
      chunks.push(part);
      offset += part.length;
    }
  }

  const xrefStart = offset;
  const xrefHeader = Buffer.from(`xref\n0 ${xrefOffsets.length}\n`, 'utf8');
  chunks.push(xrefHeader);
  offset += xrefHeader.length;

  for (let i = 0; i < xrefOffsets.length; i += 1) {
    const entryBytes = Buffer.from(`${pad10(xrefOffsets[i])} 00000 ${i === 0 ? 'f' : 'n'} \n`, 'utf8');
    chunks.push(entryBytes);
    offset += entryBytes.length;
  }

  const trailerFields = [`/Size ${xrefOffsets.length}`, '/Root 1 0 R'];
  if (hasStandard && infoObjectId) {
    trailerFields.push(`/Info ${infoObjectId} 0 R`);
  }
  const trailerBytes = Buffer.from(`trailer\n<< ${trailerFields.join(' ')} >>\nstartxref\n${xrefStart}\n%%EOF`, 'utf8');
  chunks.push(trailerBytes);

  return bufferFromChunks(chunks);
}

async function applyTiffAntialias(buffer, mode) {
  const antialiasMode = typeof mode === 'string' ? mode : 'none';
  if (antialiasMode === 'none') {
    return buffer;
  }

  try {
    const metadata = await sharp(buffer).metadata();
    const width = metadata.width || 0;
    const height = metadata.height || 0;
    if (width <= 0 || height <= 0) {
      return buffer;
    }

    if (antialiasMode === 'best-objects') {
      const upscaleFactor = 2;
      return sharp(buffer)
        .resize(Math.round(width * upscaleFactor), Math.round(height * upscaleFactor), { kernel: sharp.kernel.lanczos3 })
        .resize(width, height, { kernel: sharp.kernel.lanczos3 })
        .toBuffer();
    }

    if (antialiasMode === 'best-type') {
      return sharp(buffer)
        .sharpen({ sigma: 1 })
        .toBuffer();
    }
  } catch (error) {
    console.warn('Антиалиасинг TIFF не выполнен:', error);
  }

  return buffer;
}

async function convertToCmykRaw(buffer) {
  return new Promise((resolve, reject) => {
    const png = new PNG();
    png.parse(buffer, (error, data) => {
      if (error) {
        reject(error);
        return;
      }
      const { width, height, data: rgba } = data;
      const total = width * height;
      const cmykData = Buffer.alloc(total * 4);
      const alphaData = Buffer.alloc(total);
      let hasTransparency = false;
      for (let i = 0, j = 0, p = 0; i < rgba.length; i += 4, j += 4, p += 1) {
        const r = rgba[i] / 255;
        const g = rgba[i + 1] / 255;
        const b = rgba[i + 2] / 255;
        const alphaValue = rgba[i + 3];
        alphaData[p] = alphaValue;
        if (!hasTransparency && alphaValue < 255) {
          hasTransparency = true;
        }
        let k = 1 - Math.max(r, g, b);
        if (k < 0) {
          k = 0;
        }
        const divisor = 1 - k || 1;
        const c = (1 - r - k) / divisor;
        const m = (1 - g - k) / divisor;
        const y = (1 - b - k) / divisor;
        cmykData[j] = Math.round((Number.isNaN(c) ? 0 : c) * 255);
        cmykData[j + 1] = Math.round((Number.isNaN(m) ? 0 : m) * 255);
        cmykData[j + 2] = Math.round((Number.isNaN(y) ? 0 : y) * 255);
        cmykData[j + 3] = Math.round(k * 255);
      }
      resolve({ cmykData, alphaData, hasTransparency, width, height });
    });
  });
}

async function buildTiff(buffer, { compression, dpi, antialias }) {
  const profilePath = PROFILE_PATH;
  const compressionMap = {
    none: 'none',
    lzw: 'lzw'
  };
  const targetCompression = compressionMap[compression] || 'none';
  const targetDpi = Math.max(parseFloat(dpi) || DEFAULT_DPI, 1);
  const targetPixelsPerMm = targetDpi / 25.4;
  const processedBuffer = await applyTiffAntialias(buffer, antialias);
  return sharp(processedBuffer)
    .withMetadata({ icc: profilePath })
    .toColorspace('cmyk')
    .tiff({
      compression: targetCompression,
      xres: targetPixelsPerMm,
      yres: targetPixelsPerMm,
      resolutionUnit: 'inch'
    })
    .toBuffer();
}

app.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

app.post('/convert', upload.single('image'), async (req, res) => {
  if (!req.file) {
    res.status(400).json({ error: 'Файл изображения не получен.' });
    return;
  }

  const format = (req.body.format || 'pdf').toLowerCase();
  const baseName = (req.body.name || 'export').replace(/[^a-zA-Z0-9_\-]+/g, '_') || 'export';
  const dpi = parseFloat(req.body.dpi) || DEFAULT_DPI;
  const pdfVersion = req.body.pdfVersion || '1.4';
  const pdfStandard = req.body.pdfStandard || 'none';
  const tiffCompression = req.body.tiffCompression || 'none';
  const tiffAntialias = req.body.tiffAntialias || 'none';
  const tiffDpi = parseFloat(req.body.tiffDpi) || dpi || DEFAULT_DPI;

  try {
    if (format === 'pdf') {
      const fileName = req.file.originalname || '';
      const mimetype = (req.file.mimetype || '').toLowerCase();
      const ext = path.extname(fileName).toLowerCase();
      const isSvgUpload = mimetype.includes('svg') || ext === '.svg';
      const isPdfUpload = mimetype === 'application/pdf' || ext === '.pdf';

      if (isSvgUpload) {
        const widthPx = Number.parseInt(req.body.widthPx, 10);
        const heightPx = Number.parseInt(req.body.heightPx, 10);
        const intermediatePdf = await convertSvgToPdf(req.file.buffer, { widthPx, heightPx });
        const pdfBuffer = await convertPdfToCmyk(intermediatePdf, { pdfVersion, pdfStandard });
        res.setHeader('Content-Type', 'application/pdf');
        res.setHeader('Content-Disposition', `attachment; filename="${baseName}.pdf"`);
        res.send(pdfBuffer);
        return;
      }

      if (isPdfUpload) {
        const pdfBuffer = await convertPdfToCmyk(req.file.buffer, { pdfVersion, pdfStandard });
        res.setHeader('Content-Type', 'application/pdf');
        res.setHeader('Content-Disposition', `attachment; filename="${baseName}.pdf"`);
        res.send(pdfBuffer);
        return;
      }

      console.warn('Получен формат, отличный от PDF и SVG, выполняется растровое преобразование.');
      const {
        cmykData,
        alphaData,
        hasTransparency,
        width,
        height
      } = await convertToCmykRaw(req.file.buffer);
      const pdfBuffer = buildPDF({
        cmykData,
        alphaData,
        hasTransparency,
        widthPx: width,
        heightPx: height,
        dpi,
        pdfVersion,
        pdfStandard
      });
      res.setHeader('Content-Type', 'application/pdf');
      res.setHeader('Content-Disposition', `attachment; filename="${baseName}.pdf"`);
      res.send(pdfBuffer);
    } else if (format === 'tiff') {
      const tiffBuffer = await buildTiff(req.file.buffer, {
        compression: tiffCompression,
        dpi: tiffDpi,
        antialias: tiffAntialias
      });
      res.setHeader('Content-Type', 'image/tiff');
      res.setHeader('Content-Disposition', `attachment; filename="${baseName}.tiff"`);
      res.send(tiffBuffer);
    } else {
      res.status(400).json({ error: `Формат "${format}" не поддерживается.` });
    }
  } catch (error) {
    console.error('Ошибка конвертации', error);
    res.status(500).json({ error: error instanceof Error ? error.message : 'Не удалось выполнить конвертацию.' });
  }
});

const PORT = process.env.PORT || 3001;
app.listen(PORT, () => {
  console.log(`CMYK export server listening on port ${PORT}`);
});
