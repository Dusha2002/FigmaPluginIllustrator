package com.figma.export.svg;

import com.figma.export.exception.ConversionException;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfNumber;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject;
import com.itextpdf.layout.font.FontProvider;
import com.itextpdf.svg.converter.SvgConverter;
import com.itextpdf.svg.processors.ISvgConverterProperties;
import com.itextpdf.svg.processors.impl.SvgConverterProperties;
import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.DocumentLoader;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.svggen.SVGGeneratorContext;
import org.apache.batik.svggen.SVGGraphics2D;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.util.XMLResourceDescriptor;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.svg.SVGDocument;

import java.awt.Dimension;
import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SvgRenderer {

    private static final Logger logger = LoggerFactory.getLogger(SvgRenderer.class);
    private static final Pattern FILL_RGB_PATTERN = Pattern.compile("([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+rg");
    private static final Pattern STROKE_RGB_PATTERN = Pattern.compile("([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+RG");

    public SvgRenderResult renderSvg(byte[] svgBytes,
                                     PdfDocument document,
                                     float targetWidthPt,
                                     float targetHeightPt,
                                     FontProvider fontProvider,
                                     boolean outlineText) {
        ISvgConverterProperties properties = createConverterProperties(fontProvider, outlineText);
        byte[] currentBytes = outlineText
                ? convertTextToPaths(sanitizeSvgFonts(svgBytes))
                : svgBytes;
        boolean sanitizedAttempt = outlineText;
        while (true) {
            try (InputStream inputStream = new ByteArrayInputStream(currentBytes)) {
                PdfFormXObject xObject = SvgConverter.convertToXObject(inputStream, document, properties);
                if (xObject == null) {
                    throw new ConversionException("SVG не содержит графики для конвертации в PDF.");
                }
                enforceCmykColors(xObject);
                Rectangle bbox = extractBoundingBox(xObject);
                float intrinsicWidth = bbox != null ? bbox.getWidth() : PageSize.A4.getWidth();
                float intrinsicHeight = bbox != null ? bbox.getHeight() : PageSize.A4.getHeight();

                float widthPt = targetWidthPt > 0 ? targetWidthPt : intrinsicWidth;
                float heightPt = targetHeightPt > 0 ? targetHeightPt : intrinsicHeight;
                if (widthPt <= 0 || Float.isNaN(widthPt)) {
                    widthPt = PageSize.A4.getWidth();
                }
                if (heightPt <= 0 || Float.isNaN(heightPt)) {
                    heightPt = PageSize.A4.getHeight();
                }

                PdfPage page = document.addNewPage(new PageSize(widthPt, heightPt));
                PdfCanvas canvas = new PdfCanvas(page);

                double scaleX = widthPt / (intrinsicWidth > 0 ? intrinsicWidth : widthPt);
                double scaleY = heightPt / (intrinsicHeight > 0 ? intrinsicHeight : heightPt);
                AffineTransform transform = AffineTransform.getScaleInstance(scaleX, scaleY);
                if (bbox != null) {
                    transform.preConcatenate(AffineTransform.getTranslateInstance(-bbox.getX(), -bbox.getY()));
                }

                double[] matrix = new double[6];
                transform.getMatrix(matrix);
                canvas.concatMatrix(matrix[0], matrix[2], matrix[1], matrix[3], matrix[4], matrix[5]);
                canvas.addXObject(xObject, 0, 0);
                canvas.release();

                return new SvgRenderResult(page, widthPt, heightPt);
            } catch (UnsupportedCharsetException ex) {
                if (sanitizedAttempt) {
                    throw new ConversionException("SVG содержит шрифты с неподдерживаемой кодировкой.", ex);
                }
                byte[] sanitized = sanitizeSvgFonts(svgBytes);
                if (Arrays.equals(sanitized, currentBytes)) {
                    throw new ConversionException("Не удалось обработать SVG из-за неподдерживаемых шрифтов.", ex);
                }
                sanitizedAttempt = true;
                currentBytes = sanitized;
                logger.warn("Найден неподдерживаемый шрифт в SVG. Применяется fallback на стандартную гарнитуру.");
            } catch (java.io.IOException ex) {
                throw new ConversionException("Не удалось прочитать SVG-файл.", ex);
            }
        }
    }

    public BufferedImageTranscoderResult rasterize(byte[] svgBytes, float widthPx, float heightPx) throws java.io.IOException {
        BufferedImageTranscoder transcoder = new BufferedImageTranscoder();
        if (widthPx > 0) {
            transcoder.addTranscodingHint(BufferedImageTranscoder.KEY_WIDTH, widthPx);
        }
        if (heightPx > 0) {
            transcoder.addTranscodingHint(BufferedImageTranscoder.KEY_HEIGHT, heightPx);
        }
        try {
            transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(svgBytes)), (TranscoderOutput) null);
        } catch (org.apache.batik.transcoder.TranscoderException e) {
            throw new java.io.IOException("Не удалось растеризовать SVG", e);
        }
        return new BufferedImageTranscoderResult(transcoder.getBufferedImage(), transcoder.getWidth(), transcoder.getHeight());
    }

    private ISvgConverterProperties createConverterProperties(FontProvider fontProvider, boolean outlineText) {
        SvgConverterProperties properties = new SvgConverterProperties();
        if (!outlineText && fontProvider != null) {
            properties.setFontProvider(fontProvider);
        }
        if (outlineText) {
            logger.warn("Режим конвертации текста в контуры: используется резервная замена шрифтов без встраивания гарнитур.");
            properties.setFontProvider(null);
        }
        return properties;
    }

    private byte[] sanitizeSvgFonts(byte[] svgBytes) {
        String svg = new String(svgBytes, StandardCharsets.UTF_8);
        String sanitized = svg
                .replaceAll("(?i)font-family\\s*:[^;>]+;?", "")
                .replaceAll("(?i)font-family\\s*=\\s*\"[^\"]*\"", "")
                .replaceAll("(?i)font-family\\s*=\\s*'[^']*'", "");
        return sanitized.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] convertTextToPaths(byte[] svgBytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(svgBytes)) {
            String parser = XMLResourceDescriptor.getXMLParserClassName();
            SAXSVGDocumentFactory factory = new SAXSVGDocumentFactory(parser);
            SVGDocument document = (SVGDocument) factory.createDocument("memory", input);
            Element originalRoot = document.getDocumentElement();

            UserAgent userAgent = new UserAgentAdapter();
            DocumentLoader loader = new DocumentLoader(userAgent);
            BridgeContext context = new BridgeContext(userAgent, loader);
            context.setDynamicState(BridgeContext.STATIC);

            try {
                GVTBuilder builder = new GVTBuilder();
                GraphicsNode graphicsNode = builder.build(context, document);

                SVGGeneratorContext generatorContext = SVGGeneratorContext.createDefault(document);
                generatorContext.setPrecision(8);
                SVGGraphics2D svgGenerator = new SVGGraphics2D(generatorContext, true);

                Dimension canvasSize = determineCanvasSize(originalRoot);
                if (canvasSize != null) {
                    svgGenerator.setSVGCanvasSize(canvasSize);
                }

                graphicsNode.paint(svgGenerator);

                Element outlineRoot = svgGenerator.getRoot();
                copySizeAttributes(originalRoot, outlineRoot);

                StringWriter writer = new StringWriter();
                svgGenerator.stream(outlineRoot, writer, true, false);
                return writer.toString().getBytes(StandardCharsets.UTF_8);
            } finally {
                context.dispose();
                loader.dispose();
            }
        } catch (java.io.IOException ex) {
            throw new ConversionException("Не удалось преобразовать текст SVG в кривые.", ex);
        }
    }

    private Rectangle extractBoundingBox(PdfFormXObject xObject) {
        PdfArray bboxArray = xObject.getBBox();
        if (bboxArray == null || bboxArray.size() < 4) {
            return null;
        }
        float llx = toFloat(bboxArray.getAsNumber(0));
        float lly = toFloat(bboxArray.getAsNumber(1));
        float urx = toFloat(bboxArray.getAsNumber(2));
        float ury = toFloat(bboxArray.getAsNumber(3));

        float width = urx - llx;
        float height = ury - lly;
        if (Float.isNaN(width) || Float.isNaN(height) || width <= 0 || height <= 0) {
            return null;
        }
        return new Rectangle(llx, lly, width, height);
    }

    private float toFloat(PdfNumber number) {
        return number != null ? number.floatValue() : 0f;
    }

    private double pxToPoints(double px) {
        return px * 72d / 96d;
    }

    private void enforceCmykColors(PdfFormXObject xObject) {
        if (xObject == null) {
            return;
        }
        Set<PdfStream> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        enforceCmykColorsRecursive(xObject.getPdfObject(), visited);
    }

    private void enforceCmykColorsRecursive(PdfStream stream, Set<PdfStream> visited) {
        if (stream == null || !visited.add(stream)) {
            return;
        }
        byte[] original = stream.getBytes();
        byte[] converted = convertRgbOperatorsToCmyk(original);
        if (converted != original) {
            stream.setData(converted);
        }

        PdfDictionary resources = stream.getAsDictionary(PdfName.Resources);
        if (resources == null) {
            return;
        }
        PdfDictionary xObjects = resources.getAsDictionary(PdfName.XObject);
        if (xObjects != null) {
            for (PdfName name : xObjects.keySet()) {
                PdfStream nested = xObjects.getAsStream(name);
                if (nested != null && PdfName.Form.equals(nested.getAsName(PdfName.Subtype))) {
                    enforceCmykColorsRecursive(nested, visited);
                }
            }
        }
    }

    private byte[] convertRgbOperatorsToCmyk(byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            return contentBytes;
        }
        String content = new String(contentBytes, StandardCharsets.ISO_8859_1);
        String converted = replaceRgbOperators(content, FILL_RGB_PATTERN, false);
        converted = replaceRgbOperators(converted, STROKE_RGB_PATTERN, true);
        if (converted.equals(content)) {
            return contentBytes;
        }
        return converted.getBytes(StandardCharsets.ISO_8859_1);
    }

    private String replaceRgbOperators(String content, Pattern pattern, boolean stroke) {
        Matcher matcher = pattern.matcher(content);
        StringBuffer buffer = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            double r = clamp(Double.parseDouble(matcher.group(1)));
            double g = clamp(Double.parseDouble(matcher.group(2)));
            double b = clamp(Double.parseDouble(matcher.group(3)));
            double[] cmyk = rgbToCmyk(r, g, b);
            String replacement = formatCmyk(cmyk, stroke);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
            changed = true;
        }
        if (changed) {
            matcher.appendTail(buffer);
            return buffer.toString();
        }
        return content;
    }

    private double[] rgbToCmyk(double r, double g, double b) {
        double k = 1.0 - Math.max(r, Math.max(g, b));
        if (almostOne(k)) {
            return new double[]{0.0, 0.0, 0.0, 1.0};
        }
        double denominator = 1.0 - k;
        double c = (1.0 - r - k) / denominator;
        double m = (1.0 - g - k) / denominator;
        double y = (1.0 - b - k) / denominator;
        return new double[]{clamp(c), clamp(m), clamp(y), clamp(k)};
    }

    private String formatCmyk(double[] cmyk, boolean stroke) {
        return String.format(Locale.US, "%.6f %.6f %.6f %.6f %s",
                cmyk[0], cmyk[1], cmyk[2], cmyk[3], stroke ? "K" : "k");
    }

    private double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private boolean almostOne(double value) {
        return value >= 0.999999d;
    }

    private Dimension determineCanvasSize(Element root) {
        if (root == null) {
            return null;
        }
        int width = parseSvgLength(root.getAttribute("width"));
        int height = parseSvgLength(root.getAttribute("height"));
        if (width > 0 && height > 0) {
            return new Dimension(width, height);
        }
        String viewBox = root.getAttribute("viewBox");
        if (viewBox != null && !viewBox.isBlank()) {
            String[] parts = viewBox.trim().split("\\s+");
            if (parts.length == 4) {
                double vbWidth = parseSvgDouble(parts[2]);
                double vbHeight = parseSvgDouble(parts[3]);
                if (vbWidth > 0 && vbHeight > 0) {
                    return new Dimension((int) Math.round(vbWidth), (int) Math.round(vbHeight));
                }
            }
        }
        return null;
    }

    private int parseSvgLength(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.endsWith("px") || normalized.endsWith("pt")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        try {
            double numeric = Double.parseDouble(normalized);
            return numeric > 0 ? (int) Math.round(numeric) : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private double parseSvgDouble(String value) {
        if (value == null || value.isBlank()) {
            return -1d;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return -1d;
        }
    }

    private void copySizeAttributes(Element source, Element target) {
        if (source == null || target == null) {
            return;
        }
        copyAttribute(source, target, "width");
        copyAttribute(source, target, "height");
        copyAttribute(source, target, "viewBox");
        copyAttribute(source, target, "preserveAspectRatio");
        if (!target.hasAttribute("xmlns")) {
            target.setAttribute("xmlns", source.hasAttribute("xmlns")
                    ? source.getAttribute("xmlns")
                    : "http://www.w3.org/2000/svg");
        }
    }

    private void copyAttribute(Element source, Element target, String name) {
        if (source.hasAttribute(name)) {
            target.setAttribute(name, source.getAttribute(name));
        } else if (target.hasAttribute(name)) {
            target.removeAttribute(name);
        }
    }

    public record SvgRenderResult(PdfPage page, float widthPt, float heightPt) {
    }

    public record BufferedImageTranscoderResult(java.awt.image.BufferedImage image, float widthPx, float heightPx) {
    }

    private static class BufferedImageTranscoder extends org.apache.batik.transcoder.image.ImageTranscoder {
        private java.awt.image.BufferedImage bufferedImage;
        private float width;
        private float height;

        @Override
        public java.awt.image.BufferedImage createImage(int w, int h) {
            this.width = w;
            this.height = h;
            return new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public void writeImage(java.awt.image.BufferedImage img, TranscoderOutput output) {
            this.bufferedImage = img;
        }

        public java.awt.image.BufferedImage getBufferedImage() {
            return bufferedImage;
        }

        public float getWidth() {
            return width;
        }

        public float getHeight() {
            return height;
        }
    }
}
