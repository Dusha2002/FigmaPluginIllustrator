package com.figma.export.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
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
        Iterator<ImageWriter> writers = ImageIO.getImageWriters(new ImageTypeSpecifier(image), "tiff");
        if (!writers.hasNext()) {
            throw new IOException("TIFF writer not found");
        }
        ImageWriter writer = writers.next();
        long startNs = System.nanoTime();
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(buffer)) {
            if (logger.isInfoEnabled()) {
                logger.info("TIFF write start: size={}x{}, ppi={}", image.getWidth(), image.getHeight(), ppi);
            }
            writer.setOutput(ios);
            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), writer.getDefaultWriteParam());
            resolutionMetadata.apply(metadata, ppi);
            writer.write(null, new IIOImage(image, null, metadata), writer.getDefaultWriteParam());
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
}
