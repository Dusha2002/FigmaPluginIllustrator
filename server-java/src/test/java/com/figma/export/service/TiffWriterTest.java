package com.figma.export.service;

import com.figma.export.model.TiffCompression;
import com.twelvemonkeys.imageio.plugins.tiff.TIFFField;
import com.twelvemonkeys.imageio.plugins.tiff.TIFFImageMetadata;
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
        byte[] bytes = writer.write(source, TiffCompression.LZW, 300);

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
                            assertEquals(300d, 25.4 / horizontalPixelSize, 10.0);
                            assertEquals(300d, 25.4 / verticalPixelSize, 10.0);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                }

                double nativeX = readNativePpi(metadata, 282);
                double nativeY = readNativePpi(metadata, 283);
                assertEquals(300d, nativeX, 0.5);
                assertEquals(300d, nativeY, 0.5);
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

    private double readNativePpi(IIOMetadata metadata, int tagNumber) {
        if (metadata instanceof TIFFImageMetadata tiffMetadata) {
            TIFFField field = tiffMetadata.getTIFFField(tagNumber);
            if (field != null && field.getCount() > 0) {
                return field.getAsDouble(0);
            }
        }

        String[] formats = metadata.getMetadataFormatNames();
        if (formats == null) {
            return Double.NaN;
        }
        for (String format : formats) {
            if (format == null || !format.toLowerCase().contains("tiff")) {
                continue;
            }
            try {
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                IIOMetadataNode ifd = getChild(root, "TIFFIFD");
                if (ifd == null) {
                    continue;
                }
                for (int i = 0; i < ifd.getLength(); i++) {
                    if (ifd.item(i) instanceof IIOMetadataNode field && "TIFFField".equals(field.getNodeName())) {
                        if (Integer.toString(tagNumber).equals(field.getAttribute("number"))) {
                            IIOMetadataNode rationals = getChild(field, "TIFFRationals");
                            IIOMetadataNode rational = getChild(rationals, "TIFFRational");
                            if (rational != null) {
                                double numerator = Double.parseDouble(rational.getAttribute("numerator"));
                                double denominator = Double.parseDouble(rational.getAttribute("denominator"));
                                if (denominator != 0) {
                                    return numerator / denominator;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return Double.NaN;
    }
}
