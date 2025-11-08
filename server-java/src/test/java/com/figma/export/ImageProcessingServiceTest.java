package com.figma.export;

import com.figma.export.color.ColorProfileManager;
import com.figma.export.service.ImageProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ImageProcessingServiceTest {

    private ImageProcessingService service;

    @BeforeEach
    void setUp() {
        service = new ImageProcessingService(new ColorProfileManager());
    }

    @Test
    void convertToCmyk_keepsDimensions() throws IOException {
        BufferedImage source = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = source.createGraphics();
        try {
            g2.setColor(Color.BLUE);
            g2.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            g2.dispose();
        }

        BufferedImage cmyk = service.convertToCmyk(source);
        assertNotNull(cmyk);
        assertEquals(source.getWidth(), cmyk.getWidth());
        assertEquals(source.getHeight(), cmyk.getHeight());
        assertEquals(4, cmyk.getColorModel().getNumComponents());
    }

    @Test
    void writeTiffProducesBytes() throws IOException {
        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = source.createGraphics();
        try {
            g2.setColor(Color.RED);
            g2.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            g2.dispose();
        }
        byte[] output = service.writeTiff(service.convertToCmyk(source), "lzw", 300);
        assertNotNull(output);
        assertTrue(output.length > 0, "TIFF output should not be empty");
    }

    @Test
    void writeJpegCmykProducesBytes() throws IOException {
        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = source.createGraphics();
        try {
            g2.setColor(Color.GREEN);
            g2.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            g2.dispose();
        }
        byte[] output = service.writeJpegCmyk(service.convertToCmyk(source), 0.8f, 300);
        assertNotNull(output);
        assertTrue(output.length > 0, "JPEG output should not be empty");
    }
}
