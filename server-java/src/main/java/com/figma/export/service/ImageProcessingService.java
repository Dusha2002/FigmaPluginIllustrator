package com.figma.export.service;

import com.figma.export.color.ColorProfile;
import com.figma.export.color.ColorProfileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFImageMetadata;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.image.*;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

@Service
public class ImageProcessingService {

    private final ColorProfileManager colorProfileManager;
    private static final Logger logger = LoggerFactory.getLogger(ImageProcessingService.class);

    private static final float[] FAST_KERNEL = {
            1f / 9f, 1f / 9f, 1f / 9f,
            1f / 9f, 1f / 9f, 1f / 9f,
            1f / 9f, 1f / 9f, 1f / 9f
    };

    private static final float[] BALANCED_KERNEL = {
            1f / 16f, 2f / 16f, 1f / 16f,
            2f / 16f, 4f / 16f, 2f / 16f,
            1f / 16f, 2f / 16f, 1f / 16f
    };

    private static final float[] BEST_KERNEL = {
            1f / 273f, 4f / 273f, 7f / 273f, 4f / 273f, 1f / 273f,
            4f / 273f, 16f / 273f, 26f / 273f, 16f / 273f, 4f / 273f,
            7f / 273f, 26f / 273f, 41f / 273f, 26f / 273f, 7f / 273f,
            4f / 273f, 16f / 273f, 26f / 273f, 16f / 273f, 4f / 273f,
            1f / 273f, 4f / 273f, 7f / 273f, 4f / 273f, 1f / 273f
    };

    static {
        ImageIO.scanForPlugins();
    }

    public ImageProcessingService(ColorProfileManager colorProfileManager) {
        this.colorProfileManager = colorProfileManager;
    }

    public BufferedImage readImage(byte[] data) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IOException("Не удалось прочитать изображение.");
            }
            return image;
        }
    }

    public BufferedImage ensureArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    public BufferedImage flattenTransparency(BufferedImage source, Color background) {
        if (!source.getColorModel().hasAlpha()) {
            return source;
        }
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(background);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    public BufferedImage scaleImage(BufferedImage source, int targetWidth, int targetHeight) {
        if (targetWidth <= 0 || targetHeight <= 0 || (source.getWidth() == targetWidth && source.getHeight() == targetHeight)) {
            return source;
        }
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, source.getType());
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    public BufferedImage convertToCmyk(BufferedImage sourceRgb) {
        return convertToCmyk(sourceRgb, null);
    }

    public BufferedImage convertToCmyk(BufferedImage sourceRgb, ColorProfile profile) {
        BufferedImage rgb = ensureRgb(sourceRgb);
        ColorProfile effectiveProfile = profile != null ? profile : colorProfileManager.getDefaultProfile();
        ICC_ColorSpace cmykSpace = effectiveProfile.getColorSpace();
        int width = rgb.getWidth();
        int height = rgb.getHeight();
        int[] bits = {8, 8, 8, 8};
        ColorSpace rgbSpace = rgb.getColorModel().getColorSpace();
        ComponentColorModel colorModel = new ComponentColorModel(cmykSpace, bits, false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 4, null);
        BufferedImage cmykImage = new BufferedImage(colorModel, raster, false, null);
        ColorConvertOp convertOp = new ColorConvertOp(rgbSpace, cmykSpace, null);
        convertOp.filter(rgb, cmykImage);
        return cmykImage;
    }

    public BufferedImage applyAntialias(BufferedImage source, String quality) {
        if (source == null || quality == null) {
            return source;
        }
        String normalized = quality.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fast" -> applyKernel(source, FAST_KERNEL, 3, 1);
            case "balanced" -> applyKernel(source, BALANCED_KERNEL, 3, 1);
            case "best" -> applyKernel(source, BEST_KERNEL, 5, 2);
            default -> source;
        };
    }

    private BufferedImage applyKernel(BufferedImage source, float[] kernelData, int size, int iterations) {
        BufferedImage current = ensureArgb(source);
        Kernel kernel = new Kernel(size, size, kernelData);
        for (int i = 0; i < iterations; i++) {
            BufferedImage target = new BufferedImage(current.getWidth(), current.getHeight(), BufferedImage.TYPE_INT_ARGB);
            ConvolveOp op = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
            op.filter(current, target);
            current = target;
        }
        return current;
    }

    private BufferedImage ensureRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    public byte[] writeTiff(BufferedImage cmykImage, String compression, int ppi) throws IOException {
        long startNs = System.nanoTime();
        Iterator<ImageWriter> writers = ImageIO.getImageWriters(new ImageTypeSpecifier(cmykImage), "tiff");
        if (!writers.hasNext()) {
            logger.error("TIFF writer не найден для изображения {}x{}", cmykImage.getWidth(), cmykImage.getHeight());
            throw new IOException("Не найден TIFF writer для данного изображения.");
        }
        ImageWriter writer = writers.next();
        logger.info("TIFF writer выбран: {}", writer.getClass().getName());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(ios);
            var writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String compressionType = selectCompressionType(writeParam.getCompressionTypes(), compression);
                if (compressionType != null) {
                    logger.info("TIFF compression type: {}", compressionType);
                    writeParam.setCompressionType(compressionType);
                }
            }
            IIOMetadata metadata = createTiffMetadata(writer, cmykImage, ppi);
            logger.info("Запись TIFF начата: {}x{}, ppi={}, compression={}", cmykImage.getWidth(), cmykImage.getHeight(), ppi, compression);
            try {
                writer.write(null, new IIOImage(cmykImage, null, metadata), writeParam);
            } catch (IOException ex) {
                logger.error("Ошибка записи TIFF", ex);
                throw ex;
            }
        } finally {
            writer.dispose();
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            logger.info("Запись TIFF завершена за {} мс, размер вывода={} байт", elapsedMs, outputStream.size());
        }
        logWrittenTiffMetadata(outputStream.toByteArray(), ppi);
        return outputStream.toByteArray();
    }

    private IIOMetadata createTiffMetadata(ImageWriter writer, BufferedImage image, int ppi) throws IOException {
        IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), writer.getDefaultWriteParam());
        try {
            setResolutionMetadata(metadata, ppi);
            logger.info("PPI метаданные применены: ppi={}", ppi);
        } catch (IIOInvalidTreeException ex) {
            logger.warn("Не удалось применить DPI метаданные для TIFF", ex);
        }
        return metadata;
    }

    public byte[] writeJpegCmyk(BufferedImage cmykImage, float quality, int ppi) throws IOException {
        float normalizedQuality = Math.max(0f, Math.min(1f, quality));
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = null;
        while (writers.hasNext()) {
            ImageWriter candidate = writers.next();
            try {
                candidate.getDefaultImageMetadata(new ImageTypeSpecifier(cmykImage), candidate.getDefaultWriteParam());
                writer = candidate;
                break;
            } catch (IllegalArgumentException ignored) {
                candidate.dispose();
            }
        }
        if (writer == null) {
            throw new IOException("Не найден JPEG writer, поддерживающий CMYK.");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(ios);
            var writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String compressionType = selectCompressionType(writeParam.getCompressionTypes(), "jpeg");
                if (compressionType != null) {
                    writeParam.setCompressionType(compressionType);
                }
                writeParam.setCompressionQuality(normalizedQuality);
            }
            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(cmykImage), writeParam);
            try {
                setResolutionMetadata(metadata, ppi);
            } catch (IIOInvalidTreeException e) {
                throw new IOException("Не удалось добавить DPI метаданные в JPEG", e);
            }
            writer.write(null, new IIOImage(cmykImage, null, metadata), writeParam);
        } finally {
            writer.dispose();
        }
        return outputStream.toByteArray();
    }

    private void setResolutionMetadata(IIOMetadata metadata, int ppi) throws IIOInvalidTreeException {
        if (metadata == null || !metadata.isStandardMetadataFormatSupported() || ppi <= 0) {
            return;
        }
        double inchesPerMeter = 39.3700787;
        double pixelsPerMeter = ppi * inchesPerMeter;
        double pixelSize = 1000.0 / pixelsPerMeter;

        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_1.0");
        IIOMetadataNode dimension = getOrCreateNode(root, "Dimension");
        IIOMetadataNode horizontal = getOrCreateNode(dimension, "HorizontalPixelSize");
        horizontal.setAttribute("value", Double.toString(pixelSize));
        IIOMetadataNode vertical = getOrCreateNode(dimension, "VerticalPixelSize");
        vertical.setAttribute("value", Double.toString(pixelSize));
        metadata.setFromTree("javax_imageio_1.0", root);

        String nativeFormat = metadata.getNativeMetadataFormatName();
        if (nativeFormat != null && nativeFormat.startsWith("com_twelvemonkeys_imageio_plugins_tiff")) {
            setNativeTiffResolution(metadata, ppi);
        }
    }

    private void setNativeTiffResolution(IIOMetadata metadata, int ppi) {
        if (!(metadata instanceof TIFFImageMetadata tiffMetadata)) {
            return;
        }

        BaselineTIFFTagSet baseline = BaselineTIFFTagSet.getInstance();
        long[][] resolutionValue = {{ppi, 1}};
        TIFFTag xTag = baseline.getTag(BaselineTIFFTagSet.TAG_X_RESOLUTION);
        TIFFTag yTag = baseline.getTag(BaselineTIFFTagSet.TAG_Y_RESOLUTION);
        TIFFTag unitTag = baseline.getTag(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT);

        tiffMetadata.removeTIFFField(BaselineTIFFTagSet.TAG_X_RESOLUTION);
        tiffMetadata.removeTIFFField(BaselineTIFFTagSet.TAG_Y_RESOLUTION);
        tiffMetadata.removeTIFFField(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT);

        tiffMetadata.addTIFFField(new TIFFField(xTag, TIFFTag.TIFF_RATIONAL, 1, resolutionValue));
        tiffMetadata.addTIFFField(new TIFFField(yTag, TIFFTag.TIFF_RATIONAL, 1, resolutionValue));
        char[] unitValue = {(char) 2};
        tiffMetadata.addTIFFField(new TIFFField(unitTag, TIFFTag.TIFF_SHORT, 1, unitValue));
    }

    private IIOMetadataNode getOrCreateNode(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i) instanceof IIOMetadataNode node && name.equals(node.getNodeName())) {
                return node;
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }

    private void logWrittenTiffMetadata(byte[] data, int expectedPpi) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                logger.warn("Не удалось прочитать метаданные TIFF для диагностики");
                return;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                IIOMetadata metadata = reader.getImageMetadata(0);
                double[] ppiValues = extractPpiFromMetadata(metadata);
                logger.info("Диагностика TIFF PPI: expected={}, standard={}dpi, native={}dpi, unit={} (1=NONE,2=INCH,3=CM)",
                        expectedPpi,
                        ppiValues[0],
                        ppiValues[1],
                        (int) ppiValues[2]);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            logger.warn("Не удалось прочитать выписанный TIFF для проверки PPI", e);
        }
    }

    private double[] extractPpiFromMetadata(IIOMetadata metadata) {
        double standardPpi = Double.NaN;
        double nativePpi = Double.NaN;
        int resolutionUnit = -1;

        try {
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_1.0");
            IIOMetadataNode dimension = getChildNode(root, "Dimension");
            IIOMetadataNode horizontal = getChildNode(dimension, "HorizontalPixelSize");
            if (horizontal != null) {
                double pixelSizeMm = Double.parseDouble(horizontal.getAttribute("value"));
                if (pixelSizeMm > 0) {
                    standardPpi = 25.4 / pixelSizeMm;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            if (metadata instanceof TIFFImageMetadata tiffMetadata) {
                TIFFField xField = tiffMetadata.getTIFFField(BaselineTIFFTagSet.TAG_X_RESOLUTION);
                if (xField != null && xField.getCount() > 0) {
                    nativePpi = xField.getAsDouble(0);
                }
                TIFFField unitField = tiffMetadata.getTIFFField(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT);
                if (unitField != null && unitField.getCount() > 0) {
                    resolutionUnit = unitField.getAsInt(0);
                }
            }
        } catch (Exception ignored) {
        }

        return new double[]{standardPpi, nativePpi, resolutionUnit};
    }

    private IIOMetadataNode getChildNode(IIOMetadataNode parent, String name) {
        if (parent == null) {
            return null;
        }
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i) instanceof IIOMetadataNode node && name.equals(node.getNodeName())) {
                return node;
            }
        }
        return null;
    }

    private IIOMetadataNode getTiffField(IIOMetadataNode ifd, int tagNumber) {
        String target = Integer.toString(tagNumber);
        for (int i = 0; i < ifd.getLength(); i++) {
            if (ifd.item(i) instanceof IIOMetadataNode field && "TIFFField".equals(field.getNodeName())) {
                if (target.equals(field.getAttribute("number"))) {
                    return field;
                }
            }
        }
        return null;
    }

    private double parseRationalField(IIOMetadataNode field) {
        IIOMetadataNode rationals = getChildNode(field, "TIFFRationals");
        if (rationals != null) {
            for (int i = 0; i < rationals.getLength(); i++) {
                if (rationals.item(i) instanceof IIOMetadataNode rational && "TIFFRational".equals(rational.getNodeName())) {
                    double numerator = Double.parseDouble(rational.getAttribute("numerator"));
                    double denominator = Double.parseDouble(rational.getAttribute("denominator"));
                    if (denominator != 0) {
                        return numerator / denominator;
                    }
                }
            }
        }
        return Double.NaN;
    }

    private int parseShortField(IIOMetadataNode field) {
        IIOMetadataNode shorts = getChildNode(field, "TIFFShorts");
        if (shorts != null) {
            for (int i = 0; i < shorts.getLength(); i++) {
                if (shorts.item(i) instanceof IIOMetadataNode shortNode && "TIFFShort".equals(shortNode.getNodeName())) {
                    return Integer.parseInt(shortNode.getAttribute("value"));
                }
            }
        }
        String valueAttr = field.getAttribute("value");
        if (!valueAttr.isEmpty()) {
            return Integer.parseInt(valueAttr);
        }
        return -1;
    }

    private String selectCompressionType(String[] available, String requested) {
        if (available == null || available.length == 0 || requested == null) {
            return null;
        }
        String normalized = requested.trim().toUpperCase(Locale.ROOT);
        for (String candidate : available) {
            if (candidate != null && candidate.trim().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        // Вернуть первую доступную, если совпадения нет, чтобы не провалить запись.
        return available[0];
    }

    private void setNativeTiffResolution(IIOMetadata metadata, int ppi) {
        if (!(metadata instanceof TIFFImageMetadata tiffMetadata)) {
            return;
        }

        BaselineTIFFTagSet baseline = BaselineTIFFTagSet.getInstance();
        long[][] resolutionValue = {{ppi, 1}};
        TIFFTag xTag = baseline.getTag(BaselineTIFFTagSet.TAG_X_RESOLUTION);
        TIFFTag yTag = baseline.getTag(BaselineTIFFTagSet.TAG_Y_RESOLUTION);
        TIFFTag unitTag = baseline.getTag(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT);

        tiffMetadata.removeTIFFField(BaselineTIFFTagSet.TAG_X_RESOLUTION);
        tiffMetadata.removeTIFFField(BaselineTIFFTagSet.TAG_Y_RESOLUTION);
        tiffMetadata.removeTIFFField(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT);

        tiffMetadata.addTIFFField(new TIFFField(xTag, TIFFTag.TIFF_RATIONAL, 1, resolutionValue));
        tiffMetadata.addTIFFField(new TIFFField(yTag, TIFFTag.TIFF_RATIONAL, 1, resolutionValue));
        char[] unitValue = {(char) 2};
        tiffMetadata.addTIFFField(new TIFFField(unitTag, TIFFTag.TIFF_SHORT, 1, unitValue));
    }
}
