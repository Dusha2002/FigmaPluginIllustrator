package com.figma.export.service;

import com.figma.export.color.ColorProfile;
import com.figma.export.color.ColorProfileManager;
import com.figma.export.exception.ConversionException;
import com.figma.export.model.ExportRequest;
import com.figma.export.model.ExportResponse;
import com.figma.export.model.TiffAntialias;
import com.figma.export.model.UploadType;
import com.figma.export.pdf.CmykPdfColorMapper;
import com.figma.export.pdf.PdfStandard;
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
    private static final double PX_TO_POINT = 72d / 96d;
    private static final int MAX_TIFF_DIMENSION = 6000;
    private static final long MAX_TIFF_TOTAL_PIXELS = 36_000_000L;

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
            UploadType uploadType = detectUploadType(file);

            return switch (format) {
                case FORMAT_PDF -> convertToPdf(data, uploadType, request, baseName);
                case FORMAT_TIFF -> convertToTiff(data, uploadType, request, baseName);
                default -> throw new ConversionException("Формат \"" + format + "\" пока не поддерживается.");
            };
        } catch (ConversionException ex) {
            throw ex;
        } catch (IOException e) {
            throw new ConversionException("Не удалось прочитать загруженный файл.", e);
        }
    }

    private ExportResponse convertToPdf(byte[] data, UploadType uploadType, ExportRequest request, String baseName) throws IOException {
        ColorProfile colorProfile = colorProfileManager.getProfileOrDefault(request.getPdfColorProfile());
        PdfStandard standard = PdfStandard.fromName(request.getPdfStandard());
        String effectiveVersion = standard.ensureVersion(request.getPdfVersion());
        int dpi = Math.max(request.getDpi(), 72);
        PdfDocumentResult sourceResult = createSourcePdfDocument(data, uploadType, request, dpi, colorProfile);
        PDDocument sourceDocument = sourceResult.document();
        PDDocument rasterizedDocument = null;
        try {
            PDDocument workingDocument = sourceDocument;
            if (!sourceResult.vector()) {
                rasterizedDocument = rasterizeToCmyk(workingDocument, dpi, colorProfile);
                workingDocument = rasterizedDocument;
            }

            applyPdfStandard(workingDocument, standard, effectiveVersion, colorProfile);
            byte[] pdfBytes = saveDocument(workingDocument);
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
        int ppi = request.getTiffPpi() > 0 ? request.getTiffPpi() : Math.max(request.getDpi(), 72);
        ColorProfile colorProfile = colorProfileManager.getProfileOrDefault(request.getPdfColorProfile());
        BufferedImage sourceImage = switch (uploadType) {
            case SVG -> rasterizeSvgForImage(data, request);
            case PDF -> renderPdfPage(data, 0, ppi);
            case IMAGE -> readBufferedImage(data);
            default -> throw new ConversionException("Неподдерживаемый тип загруженного файла для экспорта в TIFF.");
        };

        logTiffStage("source", baseName, sourceImage);

        int targetWidth = positiveOrDefault(request.getWidthPx(), sourceImage.getWidth());
        int targetHeight = positiveOrDefault(request.getHeightPx(), sourceImage.getHeight());

        int[] limitedSize = enforceTiffSizeLimits(targetWidth, targetHeight);
        if (limitedSize[0] != targetWidth || limitedSize[1] != targetHeight) {
            logger.info("TIFF размер {}x{} превышает лимиты, уменьшается до {}x{}", targetWidth, targetHeight, limitedSize[0], limitedSize[1]);
            targetWidth = limitedSize[0];
            targetHeight = limitedSize[1];
        }

        if (sourceImage.getWidth() != targetWidth || sourceImage.getHeight() != targetHeight) {
            sourceImage = imageProcessingService.scaleImage(sourceImage, targetWidth, targetHeight);
            logTiffStage("scaled", baseName, sourceImage);
        }

        BufferedImage argb = imageProcessingService.ensureArgb(sourceImage);
        logTiffStage("argb", baseName, argb);
        flushIfDifferent(sourceImage, argb);
        sourceImage = null;

        BufferedImage antialiased = imageProcessingService.applyAntialias(argb, request.getTiffAntialias());
        logTiffStage("antialias", baseName, antialiased);
        flushIfDifferent(argb, antialiased);
        argb = null;

        BufferedImage flattened = imageProcessingService.flattenTransparency(antialiased, Color.WHITE);
        logTiffStage("flattened", baseName, flattened);
        flushIfDifferent(antialiased, flattened);
        antialiased = null;

        BufferedImage cmyk = imageProcessingService.convertToCmyk(flattened, colorProfile);
        logTiffStage("cmyk", baseName, cmyk);
        flushIfDifferent(flattened, cmyk);
        flattened = null;

        byte[] tiffBytes = tiffWriter.write(cmyk, request.getTiffCompression(), ppi);
        flushIfDifferent(cmyk, null);
        cmyk = null;

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        double bytesMb = tiffBytes.length / (1024d * 1024d);
        logMemoryUsage("tiff-bytes", baseName, tiffBytes.length, null);
        logger.info("TIFF экспорт завершён: name={}, размер={} байт ({}) МБ, dpi={}, время={} мс",
                baseName,
                tiffBytes.length,
                String.format(Locale.ROOT, "%.2f", bytesMb),
                ppi,
                elapsedMs);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(baseName + ".tiff", StandardCharsets.UTF_8)
                .build();
        return new ExportResponse(tiffBytes, "image/tiff", disposition);
    }

    private PdfDocumentResult createSourcePdfDocument(byte[] data, UploadType uploadType, ExportRequest request, int dpi, ColorProfile colorProfile) throws IOException {
        return switch (uploadType) {
            case SVG -> createPdfFromSvg(data, request, colorProfile);
            case PDF -> new PdfDocumentResult(loadPdf(data), true);
            case IMAGE -> new PdfDocumentResult(createPdfFromImage(data, request), false);
            default -> throw new ConversionException("Неподдерживаемый тип загруженного файла для экспорта в PDF.");
        };
    }

    private PdfDocumentResult createPdfFromSvg(byte[] data, ExportRequest request, ColorProfile colorProfile) throws IOException {
        try {
            PDDocument document = renderSvgAsVector(data, request, colorProfile);
            return new PdfDocumentResult(document, true);
        } catch (RuntimeException ex) {
            logger.warn("Не удалось отрендерить SVG векторно, выполняется fallback в растр.", ex);
            PDDocument document = renderSvgAsRasterPdf(data, request);
            return new PdfDocumentResult(document, false);
        }
    }

    private PDDocument renderSvgAsVector(byte[] data, ExportRequest request, ColorProfile colorProfile) throws IOException {
        PDDocument document = new PDDocument();
        boolean success = false;
        try {
            float widthPt = pxToPoints(request.getWidthPx());
            float heightPt = pxToPoints(request.getHeightPx());
            SvgRenderResult renderResult = svgRenderer.renderSvg(data, document, widthPt, heightPt, new CmykPdfColorMapper(colorProfile.getColorSpace()));
            if (renderResult.widthPt() > 0 && renderResult.heightPt() > 0) {
                PDPage page = renderResult.page();
                page.setMediaBox(new PDRectangle(renderResult.widthPt(), renderResult.heightPt()));
            }
            success = true;
            return document;
        } finally {
            if (!success) {
                document.close();
            }
        }
    }

    private PDDocument renderSvgAsRasterPdf(byte[] data, ExportRequest request) throws IOException {
        BufferedImage image = rasterizeSvgForImage(data, request);
        return createPdfFromBufferedImage(image, request);
    }

    private PDDocument createPdfFromImage(byte[] data, ExportRequest request) throws IOException {
        BufferedImage image = readBufferedImage(data);
        return createPdfFromBufferedImage(image, request);
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
            rendered = imageProcessingService.applyAntialias(rendered, TiffAntialias.BALANCED);
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

    private BufferedImage renderPdfPage(byte[] data, int pageIndex, int dpi) throws IOException {
        try (PDDocument document = loadPdf(data)) {
            PDFRenderer renderer = new PDFRenderer(document);
            return renderer.renderImageWithDPI(pageIndex, dpi, ImageType.ARGB);
        }
    }

    private BufferedImage rasterizeSvgForImage(byte[] data, ExportRequest request) throws IOException {
        float widthPx = request.getWidthPx() != null ? request.getWidthPx() : 0;
        float heightPx = request.getHeightPx() != null ? request.getHeightPx() : 0;
        var rasterResult = svgRenderer.rasterize(data, widthPx, heightPx);
        BufferedImage image = rasterResult.image();
        if (image != null) {
            return image;
        }
        int derivedWidth = Math.max(1, Math.round(rasterResult.widthPx()));
        int derivedHeight = Math.max(1, Math.round(rasterResult.heightPx()));
        return new BufferedImage(derivedWidth, derivedHeight, BufferedImage.TYPE_INT_ARGB);
    }

    private BufferedImage readBufferedImage(byte[] data) throws IOException {
        try {
            return imageInputLoader.read(data);
        } catch (IOException ex) {
            throw new ConversionException("Не удалось прочитать растровое изображение.", ex);
        }
    }

    private PDDocument loadPdf(byte[] data) throws IOException {
        try {
            return Loader.loadPDF(data);
        } catch (IOException ex) {
            throw new ConversionException("Не удалось прочитать PDF-файл.", ex);
        }
    }

    private void applyPdfStandard(PDDocument document, PdfStandard standard, String effectiveVersion, ColorProfile profile) throws IOException {
        float version = parsePdfVersion(effectiveVersion);
        if (!Float.isNaN(version)) {
            document.setVersion(version);
        }

        PDDocumentInformation information = document.getDocumentInformation();
        if (information == null) {
            information = new PDDocumentInformation();
            document.setDocumentInformation(information);
        }
        information.setProducer("Figma Export Server");
        information.setCreator("Figma Export Server");
        information.setKeywords(standard.getId());
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

        if (standard.isPdfx()) {
            catalog.setLanguage("ru-RU");
        }
    }

    private byte[] saveDocument(PDDocument document) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.save(output);
            return output.toByteArray();
        }
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

    private float parsePdfVersion(String value) {
        if (value == null || value.isBlank()) {
            return Float.NaN;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ex) {
            return Float.NaN;
        }
    }

    private record PdfDocumentResult(PDDocument document, boolean vector) {
    }
}
