package com.figma.export.service;

import com.figma.export.color.ColorProfileManager;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

@Service
public class ImageProcessingService {

    private final ColorProfileManager colorProfileManager;

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
        BufferedImage rgb = ensureRgb(sourceRgb);
        ICC_ColorSpace cmykSpace = (ICC_ColorSpace) colorProfileManager.getCmykColorSpace();
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
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("tiff");
        if (!writers.hasNext()) {
            throw new IOException("Не найден TIFF writer.");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(ios);
            var writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed() && compression != null && !compression.equalsIgnoreCase("none")) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String compressionType = selectCompressionType(writeParam.getCompressionTypes(), compression);
                if (compressionType != null) {
                    writeParam.setCompressionType(compressionType);
                }
            }
            IIOMetadata metadata = createTiffMetadata(writer, cmykImage, dpi);
            writer.write(null, new IIOImage(cmykImage, null, metadata), writeParam);
        } finally {
            writer.dispose();
        }
        return outputStream.toByteArray();
    }

    private IIOMetadata createTiffMetadata(ImageWriter writer, BufferedImage image, int dpi) throws IOException {
        IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), writer.getDefaultWriteParam());
        try {
            setResolutionMetadata(metadata, dpi);
        } catch (IIOInvalidTreeException e) {
            throw new IOException("Не удалось создать TIFF metadata", e);
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
        metadata.mergeTree("javax_imageio_1.0", root);
    }

    private IIOMetadataNode getOrCreateNode(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (name.equals(parent.item(i).getNodeName())) {
                return (IIOMetadataNode) parent.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
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
