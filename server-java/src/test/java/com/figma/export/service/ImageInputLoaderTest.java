package com.figma.export.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ImageInputLoaderTest {

    private ImageInputLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ImageInputLoader();
    }

    @Test
    void readValidPng() throws IOException {
        ImageProcessingServiceTestImage imageProvider = new ImageProcessingServiceTestImage();
        byte[] pngBytes = imageProvider.createPngBytes(32, 16);

        BufferedImage image = loader.read(pngBytes);

        assertNotNull(image);
        assertEquals(32, image.getWidth());
        assertEquals(16, image.getHeight());
    }

    @Test
    void readInvalidImageThrows() {
        byte[] invalid = new byte[]{0x00, 0x01, 0x02};
        assertThrows(IOException.class, () -> loader.read(invalid));
    }

    private static final class ImageProcessingServiceTestImage {
        byte[] createPngBytes(int width, int height) throws IOException {
            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                javax.imageio.ImageIO.write(bufferedImage, "png", output);
                return output.toByteArray();
            }
        }
    }
}
