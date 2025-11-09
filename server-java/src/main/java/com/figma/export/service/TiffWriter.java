package com.figma.export.service;

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
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(ios);
            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), writer.getDefaultWriteParam());
            resolutionMetadata.apply(metadata, ppi);
            writer.write(null, new IIOImage(image, null, metadata), writer.getDefaultWriteParam());
            return buffer.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
