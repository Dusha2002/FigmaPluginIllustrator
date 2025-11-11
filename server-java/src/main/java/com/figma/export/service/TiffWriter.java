package com.figma.export.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Iterator;

@Service
public class TiffWriter {

    static {
        ImageIO.setUseCache(false);
        ImageIO.scanForPlugins();
    }

    private static final Logger logger = LoggerFactory.getLogger(TiffWriter.class);
    private static final int ORIENTATION_TOP_LEFT = 1;
    private static final int PREDICTOR_HORIZONTAL_DIFFERENCING = 2;
    private static final int DEFAULT_ROWS_PER_STRIP = 25;
    private static final String SOFTWARE_NAME = "Adobe Illustrator 24.1 (Windows)";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT);

    private static final String NODE_TIFF_IFD = "TIFFIFD";
    private static final String NODE_TIFF_FIELD = "TIFFField";
    private static final String NODE_TIFF_SHORTS = "TIFFShorts";
    private static final String NODE_TIFF_SHORT = "TIFFShort";
    private static final String NODE_TIFF_LONGS = "TIFFLongs";
    private static final String NODE_TIFF_LONG = "TIFFLong";
    private static final String NODE_TIFF_ASCIIS = "TIFFAsciis";
    private static final String NODE_TIFF_ASCII = "TIFFAscii";

    private static final int TAG_ORIENTATION = 274;
    private static final int TAG_ROWS_PER_STRIP = 278;
    private static final int TAG_SOFTWARE = 305;
    private static final int TAG_DATETIME = 306;
    private static final int TAG_PREDICTOR = 317;

    private final ImageResolutionMetadata resolutionMetadata;

    public TiffWriter(ImageResolutionMetadata resolutionMetadata) {
        this.resolutionMetadata = resolutionMetadata;
    }

    public byte[] write(BufferedImage image, int ppi) throws IOException {
        return write(image, ppi, false);
    }

    public byte[] write(BufferedImage image, int ppi, boolean lzwCompression) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        long startNs = System.nanoTime();
        if (logger.isInfoEnabled()) {
            logger.info("TIFF write start: size={}x{}, ppi={}, compression={}",
                    image.getWidth(), image.getHeight(), ppi, lzwCompression ? "LZW" : "NONE");
        }

        byte[] result = writeWithImageIO(image, ppi, lzwCompression);

        if (logger.isInfoEnabled()) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            logger.info("TIFF write finished: bytes={}, time={} мс", result.length, elapsedMs);
        }
        return result;
    }

    private byte[] writeWithImageIO(BufferedImage image, int ppi, boolean lzwCompression) throws IOException {
        ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromRenderedImage(image);
        ImageWriter writer = selectSunTiffWriter();
        if (writer == null) {
            throw new IOException("Sun TIFF writer not found");
        }

        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(buffer)) {

            writer.setOutput(ios);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();

            if (writeParam.canWriteCompressed()) {
                if (lzwCompression) {
                    writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    String[] types = writeParam.getCompressionTypes();
                    boolean supportsLzw = false;
                    if (types != null) {
                        for (String type : types) {
                            if ("LZW".equalsIgnoreCase(type)) {
                                writeParam.setCompressionType(type);
                                supportsLzw = true;
                                break;
                            }
                        }
                    }
                    if (!supportsLzw) {
                        logger.warn("LZW compression requested but not supported by writer. Falling back to no compression.");
                        writeParam.setCompressionMode(ImageWriteParam.MODE_DISABLED);
                    }
                } else {
                    writeParam.setCompressionMode(ImageWriteParam.MODE_DISABLED);
                }
            }

            // Получаем метаданные и встраиваем ICC профиль
            IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam);
            embedIccProfile(metadata, image, ppi, lzwCompression);

            // Записываем изображение
            writer.write(null, new IIOImage(image, null, metadata), writeParam);
            ios.flush();

            return buffer.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private ImageWriter selectSunTiffWriter() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("TIFF");
        while (writers.hasNext()) {
            ImageWriter candidate = writers.next();
            String className = candidate.getClass().getName();
            // Используем встроенный Sun TIFF writer
            if (className.contains("sun.imageio") || className.contains("com.sun.imageio")) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selected TIFF writer: {}", className);
                }
                return candidate;
            }
            candidate.dispose();
        }
        // Fallback: берём первый доступный
        writers = ImageIO.getImageWritersByFormatName("TIFF");
        if (writers.hasNext()) {
            return writers.next();
        }
        return null;
    }

    private void embedIccProfile(IIOMetadata metadata, BufferedImage image, int ppi, boolean lzwCompression) {
        try {
            // Используем ImageResolutionMetadata для встраивания resolution
            resolutionMetadata.apply(metadata, ppi);

            // Добавляем ICC профиль через нативный формат
            String nativeFormat = metadata.getNativeMetadataFormatName();
            if (nativeFormat != null && nativeFormat.contains("tiff")) {
                // Не добавляем тег Compression вручную - ImageWriteParam сам это сделает
                ColorSpace colorSpace = image.getColorModel().getColorSpace();
                if (colorSpace instanceof ICC_ColorSpace iccColorSpace) {
                    ICC_Profile profile = iccColorSpace.getProfile();
                    if (profile != null) {
                        addIccProfileToMetadata(metadata, nativeFormat, profile.getData());
                    }
                }
                applyIllustratorMetadata(metadata, nativeFormat, image, lzwCompression);
            }
        } catch (Exception e) {
            logger.warn("Failed to embed ICC profile in TIFF metadata", e);
        }
    }

    private void addIccProfileToMetadata(IIOMetadata metadata, String nativeFormat, byte[] iccData) {
        try {
            org.w3c.dom.Node root = metadata.getAsTree(nativeFormat);
            // Ищем TIFFIFD
            org.w3c.dom.NodeList children = root.getChildNodes();
            org.w3c.dom.Node ifd = null;
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);
                if ("TIFFIFD".equals(child.getNodeName())) {
                    ifd = child;
                    break;
                }
            }

            if (ifd != null) {
                // Создаём TIFFField для ICC профиля (тег 34675)
                org.w3c.dom.Document doc = root instanceof org.w3c.dom.Document
                        ? (org.w3c.dom.Document) root
                        : root.getOwnerDocument();
                if (doc == null) {
                    return;
                }
                org.w3c.dom.Element field = doc.createElement("TIFFField");
                field.setAttribute("number", "34675");
                field.setAttribute("name", "ICC Profile");

                org.w3c.dom.Element undefineds = doc.createElement("TIFFUndefineds");
                org.w3c.dom.Element undefined = doc.createElement("TIFFUndefined");

                // Конвертируем байты в строку Base64 или используем другой формат
                StringBuilder sb = new StringBuilder();
                for (byte b : iccData) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(b & 0xFF);
                }
                undefined.setAttribute("value", sb.toString());

                undefineds.appendChild(undefined);
                field.appendChild(undefineds);
                ifd.appendChild(field);
                metadata.setFromTree(nativeFormat, root);
            }
        } catch (Exception e) {
            logger.warn("Failed to add ICC profile to TIFF metadata", e);
        }
    }

    private void applyIllustratorMetadata(IIOMetadata metadata,
                                          String nativeFormat,
                                          BufferedImage image,
                                          boolean lzwCompression) {
        try {
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(nativeFormat);
            IIOMetadataNode ifd = getOrCreateNode(root, NODE_TIFF_IFD);

            replaceTiffField(ifd, TAG_ORIENTATION,
                    createShortField(TAG_ORIENTATION, "Orientation", ORIENTATION_TOP_LEFT));

            int rowsPerStrip = calculateRowsPerStrip(image.getHeight());
            replaceTiffField(ifd, TAG_ROWS_PER_STRIP,
                    createLongField(TAG_ROWS_PER_STRIP, "RowsPerStrip", rowsPerStrip));

            replaceTiffField(ifd, TAG_SOFTWARE,
                    createAsciiField(TAG_SOFTWARE, "Software", SOFTWARE_NAME));

            String dateTimeValue = DATE_TIME_FORMATTER.format(LocalDateTime.now());
            replaceTiffField(ifd, TAG_DATETIME,
                    createAsciiField(TAG_DATETIME, "DateTime", dateTimeValue));

            if (lzwCompression) {
                replaceTiffField(ifd, TAG_PREDICTOR,
                        createShortField(TAG_PREDICTOR, "Predictor", PREDICTOR_HORIZONTAL_DIFFERENCING));
            } else {
                removeTiffField(ifd, TAG_PREDICTOR);
            }

            metadata.setFromTree(nativeFormat, root);
        } catch (Exception e) {
            logger.warn("Failed to apply Illustrator-compatible TIFF metadata", e);
        }
    }

    private int calculateRowsPerStrip(int imageHeight) {
        int maxRows = Math.max(1, imageHeight);
        return Math.max(1, Math.min(DEFAULT_ROWS_PER_STRIP, maxRows));
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

    private void replaceTiffField(IIOMetadataNode ifd, int tagNumber, IIOMetadataNode newField) {
        removeTiffField(ifd, tagNumber);
        ifd.appendChild(newField);
    }

    private void removeTiffField(IIOMetadataNode ifd, int tagNumber) {
        for (int i = ifd.getLength() - 1; i >= 0; i--) {
            if (ifd.item(i) instanceof IIOMetadataNode node && NODE_TIFF_FIELD.equals(node.getNodeName())) {
                String number = node.getAttribute("number");
                if (Integer.toString(tagNumber).equals(number)) {
                    ifd.removeChild(node);
                }
            }
        }
    }

    private IIOMetadataNode createShortField(int tagNumber, String name, int value) {
        IIOMetadataNode field = new IIOMetadataNode(NODE_TIFF_FIELD);
        field.setAttribute("number", Integer.toString(tagNumber));
        field.setAttribute("name", name);
        field.setAttribute("type", "3");
        field.setAttribute("count", "1");
        IIOMetadataNode shorts = new IIOMetadataNode(NODE_TIFF_SHORTS);
        IIOMetadataNode shortNode = new IIOMetadataNode(NODE_TIFF_SHORT);
        shortNode.setAttribute("value", Integer.toString(value));
        shorts.appendChild(shortNode);
        field.appendChild(shorts);
        return field;
    }

    private IIOMetadataNode createLongField(int tagNumber, String name, long value) {
        IIOMetadataNode field = new IIOMetadataNode(NODE_TIFF_FIELD);
        field.setAttribute("number", Integer.toString(tagNumber));
        field.setAttribute("name", name);
        field.setAttribute("type", "4");
        field.setAttribute("count", "1");
        IIOMetadataNode longs = new IIOMetadataNode(NODE_TIFF_LONGS);
        IIOMetadataNode longNode = new IIOMetadataNode(NODE_TIFF_LONG);
        longNode.setAttribute("value", Long.toString(value));
        longs.appendChild(longNode);
        field.appendChild(longs);
        return field;
    }

    private IIOMetadataNode createAsciiField(int tagNumber, String name, String value) {
        IIOMetadataNode field = new IIOMetadataNode(NODE_TIFF_FIELD);
        field.setAttribute("number", Integer.toString(tagNumber));
        field.setAttribute("name", name);
        field.setAttribute("type", "2");
        int count = value != null ? value.length() + 1 : 1;
        field.setAttribute("count", Integer.toString(count));
        IIOMetadataNode asciis = new IIOMetadataNode(NODE_TIFF_ASCIIS);
        IIOMetadataNode ascii = new IIOMetadataNode(NODE_TIFF_ASCII);
        ascii.setAttribute("value", value != null ? value : "");
        asciis.appendChild(ascii);
        field.appendChild(asciis);
        return field;
    }
}
