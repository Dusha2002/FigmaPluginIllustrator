package com.figma.export.service;

import com.figma.export.color.ColorProfile;
import com.figma.export.color.ColorProfileManager;
import com.figma.export.exception.ConversionException;
import com.figma.export.model.ExportRequest;
import com.figma.export.model.ExportResponse;
import com.figma.export.model.UploadType;
import com.figma.export.pdf.CmykPdfColorMapper;
import com.figma.export.svg.SvgRenderer;
import com.figma.export.svg.SvgRenderer.SvgRenderResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

@Service
public class ExportService {

    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);

    private static final String FORMAT_PDF = "pdf";
    private static final String FORMAT_TIFF = "tiff";
    private static final int DEFAULT_PPI = 72;
    private static final int DEFAULT_TIFF_PPI = 300;
    private static final double PX_TO_POINT = 72d / DEFAULT_PPI;
    private static final int MAX_TIFF_DIMENSION = 6000;
    private static final long MAX_TIFF_TOTAL_PIXELS = 36_000_000L;
    private static final String TIFF_QUALITY_STANDARD = "standard";
    private static final String TIFF_QUALITY_SUPERSAMPLE = "supersample";
    private static final String TIFF_QUALITY_TEXT_HINT = "texthint";

    private final SvgRenderer svgRenderer;
    private final ImageProcessingService imageProcessingService;
    private final ImageInputLoader imageInputLoader;
    private final TiffWriter tiffWriter;
    private final JpegWriter jpegWriter;
    private final ColorProfileManager colorProfileManager;

    public ExportService(SvgRenderer svgRenderer,
                         ImageProcessingService imageProcessingService,
                         ImageInputLoader imageInputLoader,
                         TiffWriter tiffWriter,
                         JpegWriter jpegWriter,
                         ColorProfileManager colorProfileManager) {
        this.svgRenderer = svgRenderer;
        this.imageProcessingService = imageProcessingService;
        this.imageInputLoader = imageInputLoader;
        this.tiffWriter = tiffWriter;
        this.jpegWriter = jpegWriter;
        this.colorProfileManager = colorProfileManager;
    }

    public ExportResponse convert(MultipartFile file, ExportRequest request) {
        String format = normalize(request.getFormat());
        if (format == null || format.isEmpty()) {
            format = FORMAT_PDF;
        }
        String baseName = sanitizeName(request.getName(), "export");

        try {
            byte[] data = file.getBytes();
            UploadType uploadType = detectUploadType(file, format);

            return switch (format) {
                case FORMAT_PDF -> convertToPdf(data, uploadType, request, baseName);
                case FORMAT_TIFF -> convertToTiff(data, uploadType, request, baseName);
                default -> throw new ConversionException("Неподдерживаемый формат экспорта: " + request.getFormat());
            };
        } catch (ConversionException ex) {
            throw ex;
        } catch (IOException e) {
            throw new ConversionException("Не удалось прочитать загруженный файл.", e);
        }
    }

    public ExportResponse convertMultiple(java.util.List<org.springframework.web.multipart.MultipartFile> files, ExportRequest request) {
        String format = normalize(request.getFormat());
        if (format == null || format.isEmpty()) {
            format = FORMAT_PDF;
        }
        
        if (!FORMAT_PDF.equals(format)) {
            throw new ConversionException("Множественные файлы поддерживаются только для экспорта в PDF.");
        }
        
        String baseName = sanitizeName(request.getName(), "combined");
        
        try {
            return convertMultipleToPdf(files, request, baseName);
        } catch (ConversionException ex) {
            throw ex;
        } catch (IOException e) {
            throw new ConversionException("Не удалось прочитать загруженные файлы.", e);
        }
    }

    private ExportResponse convertMultipleToPdf(java.util.List<org.springframework.web.multipart.MultipartFile> files, ExportRequest request, String baseName) throws IOException {
        ColorProfile colorProfile = colorProfileManager.getDefaultProfile();
        int dpi = Math.max(request.getPpi(), DEFAULT_PPI);
        
        logger.info("Начало объединения {} файлов в PDF: baseName={}, dpi={}", files.size(), baseName, dpi);
        
        // Создаём список временных байтовых массивов готовых PDF
        java.util.List<byte[]> pdfBytes = new java.util.ArrayList<>();
        
        try {
            // Создаём отдельный полностью готовый PDF для каждого элемента
            for (int i = 0; i < files.size(); i++) {
                org.springframework.web.multipart.MultipartFile file = files.get(i);
                byte[] data = file.getBytes();
                UploadType uploadType = detectUploadType(file);
                
                logger.info("Обработка файла {}/{}: name={}, type={}, size={} bytes", 
                    i + 1, files.size(), file.getOriginalFilename(), uploadType, data.length);
                
                // Получаем размеры для текущего элемента
                Integer widthPx = request.getWidthPx(i);
                Integer heightPx = request.getHeightPx(i);
                
                // Создаём временный request для текущего элемента
                ExportRequest itemRequest = new ExportRequest();
                itemRequest.setFormat(request.getFormat());
                itemRequest.setName(baseName + "_" + i);
                itemRequest.setPpi(request.getPpi());
                itemRequest.setWidthPx(widthPx);
                itemRequest.setHeightPx(heightPx);
                itemRequest.setPdfVersion(request.getPdfVersion());
                
                // Создаём PDF-документ для текущего элемента
                PdfDocumentResult sourceResult = createSourcePdfDocument(data, uploadType, itemRequest, dpi, colorProfile);
                PDDocument sourceDocument = sourceResult.document();
                
                try {
                    // Применяем настройки PDF к каждому документу ДО объединения
                    String pdfVersion = request.getPdfVersion();
                    applyPdfDefaults(sourceDocument, colorProfile, pdfVersion);
                    
                    // Сохраняем готовый документ
                    ByteArrayOutputStream tempStream = new ByteArrayOutputStream();
                    sourceDocument.save(tempStream);
                    pdfBytes.add(tempStream.toByteArray());
                    tempStream.close();
                } finally {
                    sourceDocument.close();
                }
            }
            
            // Используем PDFMergerUtility для объединения готовых PDF
            org.apache.pdfbox.multipdf.PDFMergerUtility merger = new org.apache.pdfbox.multipdf.PDFMergerUtility();
            ByteArrayOutputStream mergedStream = new ByteArrayOutputStream();
            
            for (byte[] pdfData : pdfBytes) {
                merger.addSource(new org.apache.pdfbox.io.RandomAccessReadBuffer(pdfData));
            }
            
            merger.setDestinationStream(mergedStream);
            merger.mergeDocuments(null);
            
            byte[] finalPdfBytes = mergedStream.toByteArray();
            mergedStream.close();
            
            logger.info("PDF объединение завершено: итоговый размер={} bytes ({} МБ)", 
                finalPdfBytes.length, String.format("%.2f", finalPdfBytes.length / (1024d * 1024d)));
            
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(baseName + ".pdf", StandardCharsets.UTF_8)
                    .build();
            return new ExportResponse(finalPdfBytes, MediaType.APPLICATION_PDF_VALUE, disposition);
        } catch (Exception e) {
            throw new ConversionException("Не удалось объединить PDF документы.", e);
        }
    }

    private ExportResponse convertToPdf(byte[] data, UploadType uploadType, ExportRequest request, String baseName) throws IOException {
        ColorProfile colorProfile = colorProfileManager.getDefaultProfile();
        int dpi = Math.max(request.getPpi(), DEFAULT_PPI);
        
        logger.info("Конвертация в PDF: baseName={}, uploadType={}, dpi={}", baseName, uploadType, dpi);
        
        PdfDocumentResult sourceResult = createSourcePdfDocument(data, uploadType, request, dpi, colorProfile);
        PDDocument sourceDocument = sourceResult.document();
        PDDocument rasterizedDocument = null;
        try {
            PDDocument workingDocument = sourceDocument;
            
            if (!sourceResult.vector()) {
                logger.info("Документ НЕ векторный - будет растеризован в CMYK");
                rasterizedDocument = rasterizeToCmyk(workingDocument, dpi, colorProfile);
                workingDocument = rasterizedDocument;
            } else {
                logger.info("Документ ВЕКТОРНЫЙ - сохраняется как есть с CMYK color mapping");
            }

            String pdfVersion = request.getPdfVersion();
            applyPdfDefaults(workingDocument, colorProfile, pdfVersion);
            byte[] pdfBytes = saveDocument(workingDocument);
            
            logger.info("PDF создан: size={} bytes ({} МБ)", 
                pdfBytes.length, String.format("%.2f", pdfBytes.length / (1024d * 1024d)));
            
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(baseName + ".pdf", StandardCharsets.UTF_8)
                    .build();
            return new ExportResponse(pdfBytes, MediaType.APPLICATION_PDF_VALUE, disposition);
        } finally {
            sourceDocument.close();
            if (rasterizedDocument != null) {
                rasterizedDocument.close();
            }
        }
    }

    private ExportResponse convertToTiff(byte[] data, UploadType uploadType, ExportRequest request, String baseName) throws IOException {
        long startNs = System.nanoTime();
        int ppi = request.getPpi() > 0 ? request.getPpi() : DEFAULT_TIFF_PPI;
        ColorProfile colorProfile = colorProfileManager.getDefaultProfile();
        boolean useLzw = request.isTiffLzw();
        String requestedQuality = request.getTiffQuality();
        String tiffQuality = requestedQuality != null ? requestedQuality.toLowerCase(Locale.ROOT) : TIFF_QUALITY_STANDARD;
        if (!TIFF_QUALITY_STANDARD.equals(tiffQuality)
                && !TIFF_QUALITY_SUPERSAMPLE.equals(tiffQuality)
                && !TIFF_QUALITY_TEXT_HINT.equals(tiffQuality)) {
            tiffQuality = TIFF_QUALITY_STANDARD;
        }
        boolean textHint = TIFF_QUALITY_TEXT_HINT.equals(tiffQuality);
        if (uploadType != UploadType.IMAGE) {
            throw new ConversionException("Для экспорта TIFF принимаются только PNG-изображения.");
        }
        BufferedImage sourceImage = readBufferedImage(data);

        logTiffStage("source", baseName, sourceImage);

        int targetWidth = positiveOrDefault(request.getWidthPx(), sourceImage.getWidth());
        int targetHeight = positiveOrDefault(request.getHeightPx(), sourceImage.getHeight());

        int[] limitedSize = enforceTiffSizeLimits(targetWidth, targetHeight);
        if (limitedSize[0] != targetWidth || limitedSize[1] != targetHeight) {
            logger.info("TIFF размер {}x{} превышает лимиты, уменьшается до {}x{}", targetWidth, targetHeight, limitedSize[0], limitedSize[1]);
            targetWidth = limitedSize[0];
            targetHeight = limitedSize[1];
        }

        boolean supersample = TIFF_QUALITY_SUPERSAMPLE.equals(tiffQuality);
        if (supersample) {
            long supersamplePixels = (long) targetWidth * 2 * (long) targetHeight * 2;
            logger.info("Supersample запрошен: target={}x{}, supersample={}x{}, pixels={}", 
                targetWidth, targetHeight, targetWidth * 2, targetHeight * 2, supersamplePixels);
            if (targetWidth * 2 > MAX_TIFF_DIMENSION
                    || targetHeight * 2 > MAX_TIFF_DIMENSION
                    || supersamplePixels > MAX_TIFF_TOTAL_PIXELS) {
                logger.info("Supersample требует изображение {}x{}, превышающее лимиты. Используется стандартное качество.", targetWidth * 2, targetHeight * 2);
                supersample = false;
            } else {
                logger.info("Supersample активирован: будет обработка в {}x{}", targetWidth * 2, targetHeight * 2);
            }
        }

        int workWidth = supersample ? targetWidth * 2 : targetWidth;
        int workHeight = supersample ? targetHeight * 2 : targetHeight;

        if (sourceImage.getWidth() != workWidth || sourceImage.getHeight() != workHeight) {
            sourceImage = imageProcessingService.scaleImage(sourceImage, workWidth, workHeight, textHint);
            logTiffStage("scaled", baseName, sourceImage);
        }

        BufferedImage argb = imageProcessingService.ensureArgb(sourceImage);
        logTiffStage("argb", baseName, argb);
        flushIfDifferent(sourceImage, argb);
        sourceImage = null;

        BufferedImage flattened = imageProcessingService.flattenTransparency(argb, Color.WHITE, textHint);
        logTiffStage("flattened", baseName, flattened);
        flushIfDifferent(argb, flattened);
        argb = null;

        BufferedImage cmyk = imageProcessingService.convertToCmyk(flattened, colorProfile);
        logTiffStage("cmyk", baseName, cmyk);
        flushIfDifferent(flattened, cmyk);
        flattened = null;

        if (supersample && (cmyk.getWidth() != targetWidth || cmyk.getHeight() != targetHeight)) {
            BufferedImage downscaled = imageProcessingService.scaleImage(cmyk, targetWidth, targetHeight, textHint);
            logTiffStage("downscaled", baseName, downscaled);
            flushIfDifferent(cmyk, downscaled);
            cmyk = downscaled;
        }

        byte[] tiffBytes = tiffWriter.write(cmyk, ppi, useLzw);
        flushIfDifferent(cmyk, null);
        cmyk = null;

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        double bytesMb = tiffBytes.length / (1024d * 1024d);
        logMemoryUsage("tiff-bytes", baseName, tiffBytes.length, null);
        logger.info("TIFF экспорт завершён: name={}, размер={} байт ({}) МБ, ppi={}, compression={}, quality={}, время={} мс",
                baseName,
                tiffBytes.length,
                String.format(Locale.ROOT, "%.2f", bytesMb),
                ppi,
                useLzw ? "LZW" : "NONE",
                tiffQuality,
                elapsedMs);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(baseName + ".tif", StandardCharsets.UTF_8)
                .build();
        return new ExportResponse(tiffBytes, "image/tiff", disposition);
    }

    private PdfDocumentResult createSourcePdfDocument(byte[] data, UploadType uploadType, ExportRequest request, int dpi, ColorProfile colorProfile) throws IOException {
        return switch (uploadType) {
            case SVG -> createPdfFromSvg(data, request, colorProfile);
            case IMAGE -> new PdfDocumentResult(createPdfFromImage(data, request), false);
            case PDF -> new PdfDocumentResult(loadPdfDocument(data), true);
            default -> throw new ConversionException("Неподдерживаемый тип загруженного файла для экспорта в PDF.");
        };
    }

    private PdfDocumentResult createPdfFromSvg(byte[] data, ExportRequest request, ColorProfile colorProfile) throws IOException {
        logger.info("Создание векторного PDF из SVG: size={} bytes, width={}px, height={}px", 
            data.length, request.getWidthPx(), request.getHeightPx());
        
        PDDocument document = new PDDocument();
        try {
            int targetWidthPx = positiveOrDefault(request.getWidthPx(), 0);
            int targetHeightPx = positiveOrDefault(request.getHeightPx(), 0);
            float targetWidthPt = targetWidthPx > 0 ? pxToPoints(targetWidthPx) : 0f;
            float targetHeightPt = targetHeightPx > 0 ? pxToPoints(targetHeightPx) : 0f;
            
            logger.info("Размеры в points: width={}pt, height={}pt", targetWidthPt, targetHeightPt);
            
            CmykPdfColorMapper colorMapper = colorProfile != null
                    ? new CmykPdfColorMapper(colorProfile.getColorSpace())
                    : null;
            
            if (colorMapper != null) {
                logger.info("Используется CMYK color mapper с профилем: {}", colorProfile.getDisplayName());
                svgRenderer.renderSvg(data, document, targetWidthPt, targetHeightPt, colorMapper);
            } else {
                logger.warn("CMYK color mapper НЕ используется - цвета могут быть в RGB!");
                svgRenderer.renderSvg(data, document, targetWidthPt, targetHeightPt);
            }
            
            logger.info("SVG успешно отрендерен как векторный PDF");
            return new PdfDocumentResult(document, true);
        } catch (IOException | RuntimeException ex) {
            logger.error("Ошибка при создании PDF из SVG", ex);
            document.close();
            if (ex instanceof IOException io) {
                throw io;
            }
            throw ex;
        }
    }

    private PDDocument createPdfFromImage(byte[] data, ExportRequest request) throws IOException {
        BufferedImage image = readBufferedImage(data);
        return createPdfFromBufferedImage(image, request);
    }

    private PDDocument loadPdfDocument(byte[] data) throws IOException {
        try {
            return Loader.loadPDF(data);
        } catch (IOException ex) {
            throw new ConversionException("Не удалось прочитать PDF-документ.", ex);
        }
    }

    private PDDocument createPdfFromBufferedImage(BufferedImage image, ExportRequest request) throws IOException {
        if (image == null) {
            throw new ConversionException("Не удалось растеризовать SVG для дальнейшей обработки.");
        }

        int targetWidth = positiveOrDefault(request.getWidthPx(), image.getWidth());
        int targetHeight = positiveOrDefault(request.getHeightPx(), image.getHeight());
        if (image.getWidth() != targetWidth || image.getHeight() != targetHeight) {
            image = imageProcessingService.scaleImage(image, targetWidth, targetHeight);
        }

        BufferedImage argb = imageProcessingService.ensureArgb(image);
        PDDocument document = new PDDocument();
        PDRectangle pageSize = new PDRectangle(pxToPoints(argb.getWidth()), pxToPoints(argb.getHeight()));
        PDPage page = new PDPage(pageSize);
        document.addPage(page);

        PDImageXObject imageObject = LosslessFactory.createFromImage(document, argb);
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            contentStream.drawImage(imageObject, 0, 0, pageSize.getWidth(), pageSize.getHeight());
        }
        return document;
    }

    private PDDocument rasterizeToCmyk(PDDocument source, int dpi, ColorProfile profile) throws IOException {
        PDDocument result = new PDDocument();
        PDFRenderer renderer = new PDFRenderer(source);
        int pageCount = source.getNumberOfPages();
        for (int i = 0; i < pageCount; i++) {
            PDPage originalPage = source.getPage(i);
            PDRectangle mediaBox = originalPage.getMediaBox();
            BufferedImage rendered = renderer.renderImageWithDPI(i, dpi, ImageType.ARGB);
            BufferedImage flattened = imageProcessingService.flattenTransparency(rendered, Color.WHITE);
            BufferedImage cmyk = imageProcessingService.convertToCmyk(flattened, profile);
            byte[] jpegBytes = jpegWriter.writeCmyk(cmyk, 0.92f, dpi);

            PDPage page = new PDPage(new PDRectangle(mediaBox.getWidth(), mediaBox.getHeight()));
            result.addPage(page);

            PDImageXObject imageXObject = JPEGFactory.createFromStream(result, new ByteArrayInputStream(jpegBytes));
            try (PDPageContentStream contentStream = new PDPageContentStream(result, page)) {
                contentStream.drawImage(imageXObject, 0, 0, mediaBox.getWidth(), mediaBox.getHeight());
            }
        }
        return result;
    }

    private BufferedImage readBufferedImage(byte[] data) throws IOException {
        try {
            return imageInputLoader.read(data);
        } catch (IOException ex) {
            throw new ConversionException("Не удалось прочитать растровое изображение.", ex);
        }
    }

    private float parsePdfVersion(String pdfVersion) {
        if (pdfVersion == null || pdfVersion.isEmpty()) {
            return 1.4f;
        }
        try {
            float version = Float.parseFloat(pdfVersion);
            // Проверяем диапазон 1.3 - 1.7
            if (version >= 1.3f && version <= 1.7f) {
                return version;
            }
            logger.warn("Некорректная версия PDF: {}. Используется 1.4", pdfVersion);
            return 1.4f;
        } catch (NumberFormatException e) {
            logger.warn("Не удалось распарсить версию PDF: {}. Используется 1.4", pdfVersion);
            return 1.4f;
        }
    }

    private void applyPdfDefaults(PDDocument document, ColorProfile profile, String pdfVersion) throws IOException {
        float version = parsePdfVersion(pdfVersion);
        document.setVersion(version);
        logger.info("Установлена версия PDF: {}", version);
        PDDocumentInformation information = document.getDocumentInformation();
        if (information == null) {
            information = new PDDocumentInformation();
            document.setDocumentInformation(information);
        }
        information.setProducer("Figma Export Server");
        information.setCreator("Figma Export Server");
        information.setTrapped("False");
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(Instant.now().toEpochMilli());
        information.setCreationDate(calendar);
        information.setModificationDate(calendar);

        PDDocumentCatalog catalog = document.getDocumentCatalog();
        var outputIntents = catalog.getOutputIntents();
        if (outputIntents != null) {
            outputIntents.clear();
        }
        PDOutputIntent intent = new PDOutputIntent(document, new ByteArrayInputStream(profile.getIccBytes()));
        intent.setInfo(profile.getDisplayName());
        intent.setOutputCondition(profile.getOutputCondition());
        intent.setOutputConditionIdentifier(profile.getOutputConditionIdentifier());
        intent.setRegistryName("http://www.color.org");
        catalog.addOutputIntent(intent);
    }

    private byte[] saveDocument(PDDocument document) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.save(output);
            return output.toByteArray();
        }
    }

    private UploadType detectUploadType(MultipartFile file, String format) {
        UploadType uploadType = detectUploadType(file);
        if (FORMAT_TIFF.equals(format)) {
            if (uploadType != UploadType.IMAGE || !isPng(file)) {
                throw new ConversionException("Для экспорта TIFF необходимо прикладывать PNG-файл.");
            }
        }
        return uploadType;
    }

    private boolean isPng(MultipartFile file) {
        String contentType = normalize(file.getContentType());
        if (contentType != null && contentType.contains("png")) {
            return true;
        }
        String originalName = file.getOriginalFilename();
        return originalName != null && originalName.toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private UploadType detectUploadType(MultipartFile file) {
        String contentType = normalize(file.getContentType());
        String originalName = file.getOriginalFilename();
        if (contentType != null) {
            if (contentType.contains("svg")) {
                return UploadType.SVG;
            }
            if (contentType.contains("pdf")) {
                return UploadType.PDF;
            }
            if (contentType.startsWith("image/")) {
                return UploadType.IMAGE;
            }
        }

        if (originalName != null) {
            String lower = originalName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".svg")) {
                return UploadType.SVG;
            }
            if (lower.endsWith(".pdf")) {
                return UploadType.PDF;
            }
            if (lower.matches(".*\\.(png|jpg|jpeg|tif|tiff)$")) {
                return UploadType.IMAGE;
            }
        }

        throw new ConversionException("Не удалось определить тип загруженного файла. Поддерживаются SVG, PDF и растровые изображения.");
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String sanitizeName(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String safe = value.trim().replaceAll("[\\\\/:*?\"<>|]+", "_").replaceAll("\\s+", "_");
        return safe.isEmpty() ? fallback : safe;
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private int[] enforceTiffSizeLimits(int width, int height) {
        int clampedWidth = Math.max(1, width);
        int clampedHeight = Math.max(1, height);
        double scale = 1.0;

        if (clampedWidth > MAX_TIFF_DIMENSION) {
            scale = Math.min(scale, (double) MAX_TIFF_DIMENSION / clampedWidth);
        }
        if (clampedHeight > MAX_TIFF_DIMENSION) {
            scale = Math.min(scale, (double) MAX_TIFF_DIMENSION / clampedHeight);
        }

        long totalPixels = (long) clampedWidth * clampedHeight;
        if (totalPixels > MAX_TIFF_TOTAL_PIXELS) {
            double pixelScale = Math.sqrt((double) MAX_TIFF_TOTAL_PIXELS / totalPixels);
            scale = Math.min(scale, pixelScale);
        }

        if (scale < 1.0) {
            int scaledWidth = Math.max(1, (int) Math.round(clampedWidth * scale));
            int scaledHeight = Math.max(1, (int) Math.round(clampedHeight * scale));
            return new int[]{scaledWidth, scaledHeight};
        }

        return new int[]{clampedWidth, clampedHeight};
    }

    private void logTiffStage(String stage, String baseName, BufferedImage image) {
        if (!logger.isInfoEnabled() || image == null) {
            return;
        }
        logMemoryUsage(stage, baseName, image.getWidth() * image.getHeight(), image);
    }

    private void logMemoryUsage(String stage, String baseName, long payloadSize, BufferedImage image) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        double usedMb = usedBytes / (1024d * 1024d);
        String usedMbFormatted = String.format(Locale.ROOT, "%.2f", usedMb);
        if (image != null) {
            logger.info("TIFF стадия {}: name={}, размер={}x{}, пикселей={}, память={} МБ",
                    stage, baseName, image.getWidth(), image.getHeight(), image.getWidth() * (long) image.getHeight(), usedMbFormatted);
        } else {
            logger.info("TIFF стадия {}: name={}, payload={}, память={} МБ", stage, baseName, payloadSize, usedMbFormatted);
        }
    }

    private void flushIfDifferent(BufferedImage original, BufferedImage replacement) {
        if (original == null || original == replacement) {
            return;
        }
        original.flush();
    }

    private float pxToPoints(Integer value) {
        if (value == null || value <= 0) {
            return 0f;
        }
        return (float) (value * PX_TO_POINT);
    }

    private float pxToPoints(int value) {
        return (float) (value * PX_TO_POINT);
    }

    private record PdfDocumentResult(PDDocument document, boolean vector) {
    }
}
