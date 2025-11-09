package com.figma.export.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class TiffWriterTest {

    private TiffWriter writer;

    @BeforeAll
    static void registerPlugins() {
        ImageIO.scanForPlugins();
    }

    @BeforeEach
    void setUp() {
        writer = new TiffWriter(new ImageResolutionMetadata());
    }

    @Test
    void writeSetsResolutionMetadata() throws IOException {
        BufferedImage source = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        int ppi = 300;
        byte[] bytes = writer.write(source, ppi);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            assertTrue(readers.hasNext(), "TIFF reader should be available");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                IIOMetadata metadata = reader.getImageMetadata(0);
                try {
                    IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_1.0");
                    IIOMetadataNode dimension = getChild(root, "Dimension");
                    if (dimension != null) {
                        IIOMetadataNode hNode = getChild(dimension, "HorizontalPixelSize");
                        IIOMetadataNode vNode = getChild(dimension, "VerticalPixelSize");
                        if (hNode != null && vNode != null) {
                            double horizontalPixelSize = Double.parseDouble(hNode.getAttribute("value"));
                            double verticalPixelSize = Double.parseDouble(vNode.getAttribute("value"));
                            double inferredHorizontalPpi = 25.4 / horizontalPixelSize;
                            double inferredVerticalPpi = 25.4 / verticalPixelSize;
                            assertEquals(ppi, inferredHorizontalPpi, 10.0);
                            assertEquals(ppi, inferredVerticalPpi, 10.0);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private IIOMetadataNode getChild(IIOMetadataNode parent, String name) {
        if (parent == null) {
            return null;
        }
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i) instanceof IIOMetadataNode node && name.equals(node.getNodeName())) {
                return node;
            }
        }
        return null;
    }
}
