package com.figma.export.service;

import com.figma.export.color.ColorProfile;
import com.figma.export.color.ColorProfileManager;
import com.figma.export.exception.ConversionException;
import com.figma.export.model.ExportRequest;
import com.figma.export.model.ExportResponse;
import com.figma.export.model.UploadType;
import com.figma.export.pdf.itext.ITextPdfResourceFactory;
import com.figma.export.svg.SvgRenderer;
import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfDocumentInfo;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.kernel.utils.PdfMerger;
import com.itextpdf.layout.font.FontProvider;
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
import java.util.List;
import java.util.Locale;

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
    private final ITextPdfResourceFactory pdfResourceFactory;

    public ExportService(SvgRenderer svgRenderer,
                         ImageProcessingService imageProcessingService,
                         ImageInputLoader imageInputLoader,
                         TiffWriter tiffWriter,
                         JpegWriter jpegWriter,
                         ColorProfileManager colorProfileManager,
                         ITextPdfResourceFactory pdfResourceFactory) {
        this.svgRenderer = svgRenderer;
        this.imageProcessingService = imageProcessingService;
        this.imageInputLoader = imageInputLoader;
        this.tiffWriter = tiffWriter;
        this.jpegWriter = jpegWriter;
        this.colorProfileManager = colorProfileManager;
        this.pdfResourceFactory = pdfResourceFactory;
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
        if (files == null || files.isEmpty()) {
            throw new ConversionException("Не переданы элементы для объединения в PDF.");
        }

        ColorProfile colorProfile = colorProfileManager.getDefaultProfile();
        int dpi = Math.max(request.getPpi(), DEFAULT_PPI);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        WriterProperties writerProperties = pdfResourceFactory.createWriterProperties(null);
        try (PdfDocument mergedDocument = new PdfDocument(new PdfWriter(destination, writerProperties))) {
            PdfMerger merger = new PdfMerger(mergedDocument);

            for (int i = 0; i < files.size(); i++) {
                org.springframework.web.multipart.MultipartFile file = files.get(i);
                byte[] data = file.getBytes();
                UploadType uploadType = detectUploadType(file);

                ExportRequest itemRequest = new ExportRequest();
                itemRequest.setFormat(request.getFormat());
                itemRequest.setName(baseName + "_" + (i + 1));
                itemRequest.setPpi(request.getPpi());
                itemRequest.setWidthPx(request.getWidthPx(i));
                itemRequest.setHeightPx(request.getHeightPx(i));
                itemRequest.setSvgTextMode(request.getSvgTextMode());

                byte[] prepared = preparePdfDocumentBytes(data, uploadType, itemRequest, dpi, colorProfile);
                try (PdfDocument sourceDocument = new PdfDocument(new PdfReader(new ByteArrayInputStream(prepared)))) {
                    merger.merge(sourceDocument, 1, sourceDocument.getNumberOfPages());
                }
            }

            applyPdfDefaults(mergedDocument, colorProfile);
        }

        byte[] mergedBytes = destination.toByteArray();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(baseName + ".pdf", StandardCharsets.UTF_8)
                .build();
        return new ExportResponse(mergedBytes, MediaType.APPLICATION_PDF_VALUE, disposition);
    }

    private ExportResponse convertToPdf(byte[] data, UploadType uploadType, ExportRequest request, String baseName) throws IOException {
        ColorProfile colorProfile = colorProfileManager.getDefaultProfile();
        int dpi = Math.max(request.getPpi(), DEFAULT_PPI);
        byte[] pdfBytes = preparePdfDocumentBytes(data, uploadType, request, dpi, colorProfile);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(baseName + ".pdf", StandardCharsets.UTF_8)
                .build();
        return new ExportResponse(pdfBytes, MediaType.APPLICATION_PDF_VALUE, disposition);
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

    private byte[] preparePdfDocumentBytes(byte[] data, UploadType uploadType, ExportRequest request, int dpi, ColorProfile colorProfile) throws IOException {
        return switch (uploadType) {
            case SVG -> createPdfFromSvg(data, request, colorProfile);
            case IMAGE -> createPdfFromImage(data, request, colorProfile, dpi);
            case PDF -> processExistingPdf(data, colorProfile);
            default -> throw new ConversionException("Неподдерживаемый тип загруженного файла для экспорта в PDF.");
        };
    }

    private byte[] createPdfFromSvg(byte[] data, ExportRequest request, ColorProfile colorProfile) throws IOException {
        int targetWidthPx = positiveOrDefault(request.getWidthPx(), 0);
        int targetHeightPx = positiveOrDefault(request.getHeightPx(), 0);
        float targetWidthPt = targetWidthPx > 0 ? pxToPoints(targetWidthPx) : 0f;
        float targetHeightPt = targetHeightPx > 0 ? pxToPoints(targetHeightPx) : 0f;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WriterProperties writerProperties = pdfResourceFactory.createWriterProperties(null);
        PdfWriter writer = new PdfWriter(output, writerProperties);
        PdfDocument pdfDocument = new PdfDocument(writer);
        try {
            FontProvider fontProvider = pdfResourceFactory.createFontProvider();
            boolean outlineText = request.isSvgTextAsOutlines();
            svgRenderer.renderSvg(data, pdfDocument, targetWidthPt, targetHeightPt, fontProvider, outlineText);
            applyPdfDefaults(pdfDocument, colorProfile);
        } finally {
            pdfDocument.close();
        }
        return output.toByteArray();
    }

    private byte[] createPdfFromImage(byte[] data, ExportRequest request, ColorProfile colorProfile, int dpi) throws IOException {
        BufferedImage image = readBufferedImage(data);
        if (image == null) {
            throw new ConversionException("Не удалось прочитать растровое изображение для PDF.");
        }

        int targetWidth = positiveOrDefault(request.getWidthPx(), image.getWidth());
        int targetHeight = positiveOrDefault(request.getHeightPx(), image.getHeight());
        if (image.getWidth() != targetWidth || image.getHeight() != targetHeight) {
            BufferedImage scaled = imageProcessingService.scaleImage(image, targetWidth, targetHeight);
            flushIfDifferent(image, scaled);
            image = scaled;
        }

        BufferedImage argb = imageProcessingService.ensureArgb(image);
        flushIfDifferent(image, argb);
        image = null;

        BufferedImage flattened = imageProcessingService.flattenTransparency(argb, Color.WHITE);
        flushIfDifferent(argb, flattened);
        argb = null;

        BufferedImage cmyk = imageProcessingService.convertToCmyk(flattened, colorProfile);
        flushIfDifferent(flattened, cmyk);
        flattened = null;

        int targetPpi = request.getPpi() > 0 ? request.getPpi() : dpi;
        byte[] jpegBytes = jpegWriter.writeCmyk(cmyk, 0.92f, targetPpi);
        flushIfDifferent(cmyk, null);
        cmyk = null;

        float widthPt = pxToPoints(targetWidth);
        float heightPt = pxToPoints(targetHeight);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WriterProperties writerProperties = pdfResourceFactory.createWriterProperties(null);
        PdfWriter writer = new PdfWriter(output, writerProperties);
        PdfDocument pdfDocument = new PdfDocument(writer);
        pdfDocument.setDefaultPageSize(new PageSize(widthPt, heightPt));
        com.itextpdf.layout.Document document = new com.itextpdf.layout.Document(pdfDocument, new PageSize(widthPt, heightPt));
        try {
            ImageData imageData = ImageDataFactory.create(jpegBytes);
            com.itextpdf.layout.element.Image imageElement = new com.itextpdf.layout.element.Image(imageData)
                    .scaleAbsolute(widthPt, heightPt)
                    .setFixedPosition(0, 0);
            document.add(imageElement);
            applyPdfDefaults(pdfDocument, colorProfile);
        } finally {
            document.close();
        }
        return output.toByteArray();
    }

    private byte[] processExistingPdf(byte[] data, ColorProfile colorProfile) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WriterProperties writerProperties = pdfResourceFactory.createWriterProperties(null);
        PdfDocument pdfDocument = new PdfDocument(new PdfReader(new ByteArrayInputStream(data)), new PdfWriter(output, writerProperties));
        try {
            applyPdfDefaults(pdfDocument, colorProfile);
        } finally {
            pdfDocument.close();
        }
        return output.toByteArray();
    }

    private BufferedImage readBufferedImage(byte[] data) throws IOException {
        try {
            return imageInputLoader.read(data);
        } catch (IOException ex) {
            throw new ConversionException("Не удалось прочитать растровое изображение.", ex);
        }
    }

    private void applyPdfDefaults(PdfDocument document, ColorProfile profile) {
        PdfDocumentInfo info = document.getDocumentInfo();
        info.setProducer("Figma Export Server");
        info.setCreator("Figma Export Server");
        info.setTrapped(PdfName.False);
        info.addCreationDate();
        info.addModDate();

        pdfResourceFactory.applyOutputIntent(document, profile);
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
}
