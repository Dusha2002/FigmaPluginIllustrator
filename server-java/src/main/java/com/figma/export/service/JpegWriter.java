package com.figma.export.service;

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
public class JpegWriter {

    private final ImageResolutionMetadata resolutionMetadata;

    public JpegWriter(ImageResolutionMetadata resolutionMetadata) {
        this.resolutionMetadata = resolutionMetadata;
    }

    public byte[] writeCmyk(BufferedImage image, float quality, int ppi) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        float normalizedQuality = Math.max(0f, Math.min(1f, quality));
        ImageWriter writer = findSupportingWriter(image);
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(ios);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(normalizedQuality);
            }
            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), writeParam);
            resolutionMetadata.apply(metadata, ppi);
            writer.write(null, new IIOImage(image, null, metadata), writeParam);
            writer.dispose();
            return buffer.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private ImageWriter findSupportingWriter(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        while (writers.hasNext()) {
            ImageWriter candidate = writers.next();
            try {
                candidate.getDefaultImageMetadata(new ImageTypeSpecifier(image), candidate.getDefaultWriteParam());
                return candidate;
            } catch (IllegalArgumentException ex) {
                candidate.dispose();
            }
        }
        throw new IOException("Не найден JPEG writer, поддерживающий CMYK.");
    }
}
