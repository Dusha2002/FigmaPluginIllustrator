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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

@Service
public class TiffWriter {

    static {
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
        ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromRenderedImage(image);
        ImageWriter writer = selectWriter(typeSpecifier);
        if (writer == null) {
            throw new IOException("TIFF writer not found");
        }
        long startNs = System.nanoTime();
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(buffer)) {
            if (logger.isInfoEnabled()) {
                logger.info("TIFF write start: size={}x{}, ppi={}, writer={}", image.getWidth(), image.getHeight(), ppi, writer.getClass().getName());
            }
            writer.setOutput(ios);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_DISABLED);
            }
            long metadataStartNs = System.nanoTime();
            IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam);
            resolutionMetadata.apply(metadata, ppi);
            IIOMetadata convertedMetadata;
            try {
                convertedMetadata = writer.convertImageMetadata(metadata, typeSpecifier, writeParam);
            } catch (Exception ex) {
                if (logger.isWarnEnabled()) {
                    logger.warn("TIFF metadata conversion failed, using original metadata", ex);
                }
                convertedMetadata = metadata;
            }
            if (logger.isDebugEnabled()) {
                long metadataElapsed = (System.nanoTime() - metadataStartNs) / 1_000_000L;
                logger.debug("TIFF metadata prepared in {} мс", metadataElapsed);
            }
            long writeStartNs = System.nanoTime();
            writer.write(null, new IIOImage(image, null, convertedMetadata), writeParam);
            if (logger.isDebugEnabled()) {
                long writeElapsed = (System.nanoTime() - writeStartNs) / 1_000_000L;
                logger.debug("TIFF image data written in {} мс", writeElapsed);
            }
            ios.flush();
            byte[] result = buffer.toByteArray();
            if (logger.isInfoEnabled()) {
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                logger.info("TIFF write finished: bytes={}, time={} мс", result.length, elapsedMs);
            }
            return result;
        } finally {
            writer.dispose();
        }
    }

    private ImageWriter selectWriter(ImageTypeSpecifier typeSpecifier) {
        ImageWriter preferred = null;
        ImageWriter fallback = null;
        Iterator<ImageWriter> writers = ImageIO.getImageWriters(typeSpecifier, "tiff");
        while (writers.hasNext()) {
            ImageWriter candidate = writers.next();
            String className = candidate.getClass().getName();
            if (className.startsWith("com.twelvemonkeys.imageio.plugins.tiff")) {
                preferred = candidate;
                break;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        if (preferred != null) {
            return preferred;
        }
        return fallback;
    }
}
