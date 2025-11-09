package com.figma.export.service;

import com.figma.export.color.ColorProfile;
import com.figma.export.color.ColorProfileManager;
import com.twelvemonkeys.imageio.plugins.tiff.BaselineTIFFTagSet;
import com.twelvemonkeys.imageio.plugins.tiff.TIFFDirectory;
import com.twelvemonkeys.imageio.plugins.tiff.TIFFField;
import com.twelvemonkeys.imageio.plugins.tiff.TIFFRational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
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

    public byte[] writeTiff(BufferedImage cmykImage, String compression, int dpi) throws IOException {
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
            IIOMetadata metadata = createTiffMetadata(writer, cmykImage, dpi);
            logger.info("Запись TIFF начата: {}x{}, dpi={}, compression={}", cmykImage.getWidth(), cmykImage.getHeight(), dpi, compression);
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
        return outputStream.toByteArray();
    }

    private IIOMetadata createTiffMetadata(ImageWriter writer, BufferedImage image, int dpi) throws IOException {
        IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), writer.getDefaultWriteParam());
        try {
            setResolutionMetadata(metadata, dpi);
            logger.info("DPI метаданные применены: dpi={}", dpi);
        } catch (IIOInvalidTreeException ex) {
            logger.warn("Не удалось применить DPI метаданные для TIFF", ex);
        }
        return metadata;
    }

    public byte[] writeJpegCmyk(BufferedImage cmykImage, float quality, int dpi) throws IOException {
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
                setResolutionMetadata(metadata, dpi);
            } catch (IIOInvalidTreeException e) {
                throw new IOException("Не удалось добавить DPI метаданные в JPEG", e);
            }
            writer.write(null, new IIOImage(cmykImage, null, metadata), writeParam);
        } finally {
            writer.dispose();
        }
        return outputStream.toByteArray();
    }

    private void setResolutionMetadata(IIOMetadata metadata, int dpi) throws IIOInvalidTreeException {
        if (metadata == null || !metadata.isStandardMetadataFormatSupported() || dpi <= 0) {
            return;
        }
        double inchesPerMeter = 39.3700787;
        double pixelsPerMeter = dpi * inchesPerMeter;
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
            try {
                var directory = new com.twelvemonkeys.imageio.plugins.tiff.TIFFDirectory();
                directory.add(com.twelvemonkeys.imageio.plugins.tiff.TIFFField.create(
                        com.twelvemonkeys.imageio.plugins.tiff.BaselineTIFFTagSet.TAG_X_RESOLUTION,
                        new com.twelvemonkeys.imageio.plugins.tiff.TIFFRational(dpi, 1)));
                directory.add(com.twelvemonkeys.imageio.plugins.tiff.TIFFField.create(
                        com.twelvemonkeys.imageio.plugins.tiff.BaselineTIFFTagSet.TAG_Y_RESOLUTION,
                        new com.twelvemonkeys.imageio.plugins.tiff.TIFFRational(dpi, 1)));
                directory.add(com.twelvemonkeys.imageio.plugins.tiff.TIFFField.create(
                        com.twelvemonkeys.imageio.plugins.tiff.BaselineTIFFTagSet.TAG_RESOLUTION_UNIT,
                        new char[]{2}));
                metadata.mergeTree(nativeFormat, directory.getAsTree(nativeFormat));
            } catch (IllegalArgumentException e) {
                throw new IIOInvalidTreeException("Не удалось применить TIFF DPI данные", e, null);
            }
        }
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

    private IIOMetadataNode createRationalNode(int numerator, int denominator) {
        IIOMetadataNode rational = new IIOMetadataNode("TIFFRational");
        rational.setAttribute("value", numerator + "/" + denominator);
        rational.setAttribute("numerator", Integer.toString(numerator));
        rational.setAttribute("denominator", Integer.toString(denominator));
        return rational;
    }

    private IIOMetadataNode createShortNode(int value) {
        IIOMetadataNode shortNode = new IIOMetadataNode("TIFFShort");
        shortNode.setAttribute("value", Integer.toString(value));
        return shortNode;
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
}
