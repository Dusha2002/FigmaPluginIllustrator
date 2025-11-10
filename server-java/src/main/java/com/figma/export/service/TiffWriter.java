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
import javax.imageio.stream.ImageOutputStream;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

@Service
public class TiffWriter {

    static {
        ImageIO.setUseCache(false);
        ImageIO.scanForPlugins();
    }

    private static final Logger logger = LoggerFactory.getLogger(TiffWriter.class);
    private final ImageResolutionMetadata resolutionMetadata;

    public TiffWriter(ImageResolutionMetadata resolutionMetadata) {
        this.resolutionMetadata = resolutionMetadata;
    }

    public byte[] write(BufferedImage image, int ppi) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        long startNs = System.nanoTime();
        if (logger.isInfoEnabled()) {
            logger.info("TIFF write start: size={}x{}, ppi={}", image.getWidth(), image.getHeight(), ppi);
        }
        
        byte[] result = writeWithImageIO(image, ppi);
        
        if (logger.isInfoEnabled()) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            logger.info("TIFF write finished: bytes={}, time={} мс", result.length, elapsedMs);
        }
        return result;
    }
    
    private byte[] writeWithImageIO(BufferedImage image, int ppi) throws IOException {
        ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromRenderedImage(image);
        ImageWriter writer = selectSunTiffWriter();
        if (writer == null) {
            throw new IOException("Sun TIFF writer not found");
        }
        
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(buffer)) {
            
            writer.setOutput(ios);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            
            // Отключаем сжатие
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_DISABLED);
            }
            
            // Получаем метаданные и встраиваем ICC профиль
            IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam);
            embedIccProfile(metadata, image, ppi);
            
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
    
    private void embedIccProfile(IIOMetadata metadata, BufferedImage image, int ppi) {
        try {
            // Используем ImageResolutionMetadata для встраивания resolution
            resolutionMetadata.apply(metadata, ppi);
            
            // Добавляем ICC профиль через нативный формат
            String nativeFormat = metadata.getNativeMetadataFormatName();
            if (nativeFormat != null && nativeFormat.contains("tiff")) {
                ColorSpace colorSpace = image.getColorModel().getColorSpace();
                if (colorSpace instanceof ICC_ColorSpace iccColorSpace) {
                    ICC_Profile profile = iccColorSpace.getProfile();
                    if (profile != null) {
                        addIccProfileToMetadata(metadata, nativeFormat, profile.getData());
                    }
                }
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
                org.w3c.dom.Document doc = root.getOwnerDocument();
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
}
