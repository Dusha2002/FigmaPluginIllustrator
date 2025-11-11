package com.figma.export.service;

import com.figma.export.color.ColorProfile;
import com.figma.export.color.ColorProfileManager;
import com.figma.export.exception.ConversionException;
import com.figma.export.model.ExportRequest;
import com.figma.export.model.ExportResponse;
import com.figma.export.model.UploadType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
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
    private static final int DEFAULT_TIFF_PPI = 300;
    private static final int MAX_TIFF_DIMENSION = 6000;
    private static final long MAX_TIFF_TOTAL_PIXELS = 36_000_000L;
    private static final String TIFF_QUALITY_STANDARD = "standard";
    private static final String TIFF_QUALITY_SUPERSAMPLE = "supersample";
    private static final String TIFF_QUALITY_TEXT_HINT = "texthint";

    private final ImageProcessingService imageProcessingService;
    private final ImageInputLoader imageInputLoader;
    private final TiffWriter tiffWriter;
    private final ColorProfileManager colorProfileManager;

    public ExportService(ImageProcessingService imageProcessingService,
                         ImageInputLoader imageInputLoader,
                         TiffWriter tiffWriter,
                         ColorProfileManager colorProfileManager) {
        this.imageProcessingService = imageProcessingService;
        this.imageInputLoader = imageInputLoader;
        this.tiffWriter = tiffWriter;
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
        PDFMergerUtility mergerUtility = new PDFMergerUtility();
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        mergerUtility.setDestinationStream(destination);
        java.util.List<RandomAccessRead> preparedSources = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                org.springframework.web.multipart.MultipartFile file = files.get(i);
                byte[] data = file.getBytes();
                UploadType uploadType = detectUploadType(file);
                if (uploadType != UploadType.PDF) {
                    throw new ConversionException("Для объединения PDF необходимо загружать PDF-файлы.");
                }

                try (PDDocument document = loadPdfDocument(data)) {
                    applyPdfDefaults(document, colorProfile);
                    byte[] prepared = saveDocument(document);
                    RandomAccessReadBuffer sourceBuffer = new RandomAccessReadBuffer(prepared);
                    preparedSources.add(sourceBuffer);
                    mergerUtility.addSource(sourceBuffer);
                }
            }

            mergerUtility.mergeDocuments(null);
            byte[] pdfBytes = destination.toByteArray();
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(baseName + ".pdf", StandardCharsets.UTF_8)
                    .build();
            return new ExportResponse(pdfBytes, MediaType.APPLICATION_PDF_VALUE, disposition);
        } finally {
            for (RandomAccessRead source : preparedSources) {
                try {
                    source.close();
                } catch (IOException ignored) {
                }
            }
            destination.close();
        }
    }

    private ExportResponse convertToPdf(byte[] data, UploadType uploadType, ExportRequest request, String baseName) throws IOException {
        ColorProfile colorProfile = colorProfileManager.getDefaultProfile();
        if (uploadType != UploadType.PDF) {
            throw new ConversionException("Для экспорта PDF необходимо прикладывать PDF-файлы.");
        }
        try (PDDocument document = loadPdfDocument(data)) {
            applyPdfDefaults(document, colorProfile);
            byte[] pdfBytes = saveDocument(document);
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(baseName + ".pdf", StandardCharsets.UTF_8)
                    .build();
            return new ExportResponse(pdfBytes, MediaType.APPLICATION_PDF_VALUE, disposition);
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
        int oversampleFactor = supersample ? 4 : (textHint ? 2 : 1);
        if (supersample) {
            long supersamplePixels = (long) targetWidth * oversampleFactor * (long) targetHeight * oversampleFactor;
            logger.info("Supersample запрошен: target={}x{}, factor={}, supersample={}x{}, pixels={}",
                    targetWidth, targetHeight, oversampleFactor, targetWidth * oversampleFactor, targetHeight * oversampleFactor, supersamplePixels);
            if (targetWidth * oversampleFactor > MAX_TIFF_DIMENSION
                    || targetHeight * oversampleFactor > MAX_TIFF_DIMENSION
                    || supersamplePixels > MAX_TIFF_TOTAL_PIXELS) {
                logger.info("Supersample требует изображение {}x{}, превышающее лимиты. Используется стандартное качество.",
                        targetWidth * oversampleFactor, targetHeight * oversampleFactor);
                supersample = false;
                oversampleFactor = 1;
            } else {
                logger.info("Supersample активирован: будет обработка в {}x{}", targetWidth * oversampleFactor, targetHeight * oversampleFactor);
            }
        }

        int workWidth = targetWidth * oversampleFactor;
        int workHeight = targetHeight * oversampleFactor;

        if (sourceImage.getWidth() != workWidth || sourceImage.getHeight() != workHeight) {
            sourceImage = imageProcessingService.gammaAwareScale(sourceImage, workWidth, workHeight, textHint);
            logTiffStage("scaled-rgb", baseName, sourceImage);
        }

        BufferedImage argb = imageProcessingService.ensureArgb(sourceImage);
        logTiffStage("argb", baseName, argb);
        flushIfDifferent(sourceImage, argb);
        sourceImage = null;

        BufferedImage flattened = imageProcessingService.flattenTransparency(argb, Color.WHITE, textHint);
        logTiffStage("flattened", baseName, flattened);
        flushIfDifferent(argb, flattened);
        argb = null;

        BufferedImage rgbFinal = flattened;
        if (oversampleFactor > 1 && (flattened.getWidth() != targetWidth || flattened.getHeight() != targetHeight)) {
            rgbFinal = imageProcessingService.gammaAwareScale(flattened, targetWidth, targetHeight, textHint);
            logTiffStage("downscaled-rgb", baseName, rgbFinal);
            flushIfDifferent(flattened, rgbFinal);
        }

        BufferedImage cmyk = imageProcessingService.convertToCmyk(rgbFinal, colorProfile);
        logTiffStage("cmyk", baseName, cmyk);
        flushIfDifferent(rgbFinal, cmyk == rgbFinal ? null : rgbFinal);
        if (rgbFinal != cmyk) {
            rgbFinal.flush();
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

    private PDDocument loadPdfDocument(byte[] data) throws IOException {
        try {
            return Loader.loadPDF(data);
        } catch (IOException ex) {
            throw new ConversionException("Не удалось прочитать PDF-документ.", ex);
        }
    }

    private BufferedImage readBufferedImage(byte[] data) throws IOException {
        try {
            return imageInputLoader.read(data);
        } catch (IOException ex) {
            throw new ConversionException("Не удалось прочитать растровое изображение.", ex);
        }
    }

    private void applyPdfDefaults(PDDocument document, ColorProfile profile) throws IOException {
        document.setVersion(1.4f);
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
}
