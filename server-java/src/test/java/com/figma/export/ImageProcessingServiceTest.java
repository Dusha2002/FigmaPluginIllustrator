package com.figma.export;

import com.figma.export.color.ColorProfileManager;
import com.figma.export.service.ImageProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

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
    void writeTiffSetsDpiMetadata() throws IOException {
        BufferedImage source = new BufferedImage(128, 256, BufferedImage.TYPE_INT_RGB);
        byte[] output = service.writeTiff(service.convertToCmyk(source), "lzw", 300);

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(output))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            assertTrue(readers.hasNext(), "TIFF reader should be available");
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                IIOMetadata metadata = reader.getImageMetadata(0);
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_1.0");
                IIOMetadataNode dimension = getChild(root, "Dimension");
                assertNotNull(dimension);
                double hPixelSize = Double.parseDouble(getChild(dimension, "HorizontalPixelSize").getAttribute("value"));
                double vPixelSize = Double.parseDouble(getChild(dimension, "VerticalPixelSize").getAttribute("value"));
                double expectedDpi = 300d;
                double hDpi = 25.4 / hPixelSize;
                double vDpi = 25.4 / vPixelSize;
                assertEquals(expectedDpi, hDpi, 0.5);
                assertEquals(expectedDpi, vDpi, 0.5);
            } finally {
                reader.dispose();
            }
        }
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

    private IIOMetadataNode getChild(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i) instanceof IIOMetadataNode node && name.equals(node.getNodeName())) {
                return node;
            }
        }
        return null;
    }
}
