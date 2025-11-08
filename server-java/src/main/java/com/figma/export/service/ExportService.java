package com.figma.export.service;

import com.figma.export.color.ColorProfileManager;
import com.figma.export.exception.ConversionException;
import com.figma.export.model.ExportRequest;
import com.figma.export.model.ExportResponse;
import com.figma.export.model.UploadType;
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

    private static final String FORMAT_PDF = "pdf";
    private static final String FORMAT_TIFF = "tiff";
    private static final double PX_TO_POINT = 72d / 96d;

    private final SvgRenderer svgRenderer;
    private final ImageProcessingService imageProcessingService;
    private final ColorProfileManager colorProfileManager;

    public ExportService(SvgRenderer svgRenderer,
                         ImageProcessingService imageProcessingService,
                         ColorProfileManager colorProfileManager) {
        this.svgRenderer = svgRenderer;
        this.imageProcessingService = imageProcessingService;
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
        PdfStandard standard = PdfStandard.fromName(request.getPdfStandard());
        String effectiveVersion = standard.ensureVersion(request.getPdfVersion());
        int dpi = Math.max(request.getDpi(), 72);
        boolean keepVector = request.isKeepVector();
        if (keepVector && !(uploadType == UploadType.SVG || uploadType == UploadType.PDF)) {
            throw new ConversionException("Опция сохранения векторов доступна только для SVG или PDF источников.");
        }
        keepVector = keepVector && (uploadType == UploadType.SVG || uploadType == UploadType.PDF);

        PDDocument sourceDocument = createSourcePdfDocument(data, uploadType, request, dpi);
        PDDocument rasterizedDocument = null;
        try {
            PDDocument workingDocument = sourceDocument;
            if (!keepVector) {
                rasterizedDocument = rasterizeToCmyk(workingDocument, dpi);
                workingDocument = rasterizedDocument;
            }

            applyPdfStandard(workingDocument, standard, effectiveVersion);
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
        int dpi = request.getTiffDpi() > 0 ? request.getTiffDpi() : Math.max(request.getDpi(), 72);
        BufferedImage sourceImage = switch (uploadType) {
            case SVG -> rasterizeSvgForImage(data, request);
            case PDF -> renderPdfPage(data, 0, dpi);
            case IMAGE -> readBufferedImage(data);
            default -> throw new ConversionException("Неподдерживаемый тип загруженного файла для экспорта в TIFF.");
        };

        int targetWidth = positiveOrDefault(request.getWidthPx(), sourceImage.getWidth());
        int targetHeight = positiveOrDefault(request.getHeightPx(), sourceImage.getHeight());

        if (sourceImage.getWidth() != targetWidth || sourceImage.getHeight() != targetHeight) {
            sourceImage = imageProcessingService.scaleImage(sourceImage, targetWidth, targetHeight);
        }

        BufferedImage argb = imageProcessingService.ensureArgb(sourceImage);
        BufferedImage flattened = imageProcessingService.flattenTransparency(argb, Color.WHITE);
        BufferedImage cmyk = imageProcessingService.convertToCmyk(flattened);
        byte[] tiffBytes = imageProcessingService.writeTiff(cmyk, request.getTiffCompression(), dpi);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(baseName + ".tiff", StandardCharsets.UTF_8)
                .build();
        return new ExportResponse(tiffBytes, "image/tiff", disposition);
    }

    private PDDocument createSourcePdfDocument(byte[] data, UploadType uploadType, ExportRequest request, int dpi) throws IOException {
        return switch (uploadType) {
            case SVG -> createPdfFromSvg(data, request);
            case PDF -> loadPdf(data);
            case IMAGE -> createPdfFromImage(data, request);
            default -> throw new ConversionException("Неподдерживаемый тип загруженного файла для экспорта в PDF.");
        };
    }

    private PDDocument createPdfFromSvg(byte[] data, ExportRequest request) throws IOException {
        PDDocument document = new PDDocument();
        float widthPt = pxToPoints(request.getWidthPx());
        float heightPt = pxToPoints(request.getHeightPx());
        SvgRenderResult renderResult = svgRenderer.renderSvg(data, document, widthPt, heightPt);
        if (renderResult.widthPt() > 0 && renderResult.heightPt() > 0) {
            PDPage page = renderResult.page();
            page.setMediaBox(new PDRectangle(renderResult.widthPt(), renderResult.heightPt()));
        }
        return document;
    }

    private PDDocument createPdfFromImage(byte[] data, ExportRequest request) throws IOException {
        BufferedImage image = readBufferedImage(data);
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

    private PDDocument rasterizeToCmyk(PDDocument source, int dpi) throws IOException {
        PDDocument result = new PDDocument();
        PDFRenderer renderer = new PDFRenderer(source);
        int pageCount = source.getNumberOfPages();
        for (int i = 0; i < pageCount; i++) {
            PDPage originalPage = source.getPage(i);
            PDRectangle mediaBox = originalPage.getMediaBox();
            BufferedImage rendered = renderer.renderImageWithDPI(i, dpi, ImageType.ARGB);
            BufferedImage flattened = imageProcessingService.flattenTransparency(rendered, Color.WHITE);
            BufferedImage cmyk = imageProcessingService.convertToCmyk(flattened);
            byte[] jpegBytes = imageProcessingService.writeJpegCmyk(cmyk, 0.92f, dpi);

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
            return imageProcessingService.readImage(data);
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

    private void applyPdfStandard(PDDocument document, PdfStandard standard, String effectiveVersion) throws IOException {
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
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(Instant.now().toEpochMilli());
        information.setCreationDate(calendar);
        information.setModificationDate(calendar);

        PDDocumentCatalog catalog = document.getDocumentCatalog();
        var outputIntents = catalog.getOutputIntents();
        if (outputIntents != null) {
            outputIntents.clear();
        }
        PDOutputIntent intent = new PDOutputIntent(document, new ByteArrayInputStream(colorProfileManager.getCmykProfileBytes()));
        intent.setInfo("Coated FOGRA39");
        intent.setOutputCondition("Coated FOGRA39");
        intent.setOutputConditionIdentifier("Coated FOGRA39");
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
}
