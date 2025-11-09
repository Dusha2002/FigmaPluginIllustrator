package com.figma.export.service;

import com.figma.export.model.TiffCompression;
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
import java.util.Arrays;
import java.util.Iterator;

@Service
public class TiffWriter {

    private static final Logger logger = LoggerFactory.getLogger(TiffWriter.class);

    static {
        ImageIO.scanForPlugins();
    }

    private final ImageResolutionMetadata resolutionMetadata;

    public TiffWriter(ImageResolutionMetadata resolutionMetadata) {
        this.resolutionMetadata = resolutionMetadata;
    }

    public byte[] write(BufferedImage image, TiffCompression compression, int ppi) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWriters(new ImageTypeSpecifier(image), "tiff");
        if (!writers.hasNext()) {
            throw new IOException("TIFF writer not found");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(ios);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed() && compression != null) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String selected = selectCompression(writeParam.getCompressionTypes(), compression);
                if (selected != null) {
                    writeParam.setCompressionType(selected);
                    logger.debug("TIFF compression selected: {} (requested: {})", selected, compression.getId());
                }
            }
            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), writeParam);
            resolutionMetadata.apply(metadata, ppi);
            writer.write(null, new IIOImage(image, null, metadata), writeParam);
            return buffer.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private String selectCompression(String[] available, TiffCompression requested) {
        if (available == null || available.length == 0 || requested == null) {
            return null;
        }
        String normalized = requested.getId();
        return Arrays.stream(available)
                .filter(type -> type != null && type.equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(available[0]);
    }
}
