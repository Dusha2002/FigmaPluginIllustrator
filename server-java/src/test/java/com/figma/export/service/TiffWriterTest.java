package com.figma.export.service;

import com.figma.export.color.ColorProfileManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class TiffWriterTest {

    private TiffWriter writer;
    private ImageProcessingService imageProcessingService;

    @BeforeAll
    static void registerPlugins() {
        ImageIO.scanForPlugins();
    }

    @BeforeEach
    void setUp() {
        writer = new TiffWriter(new ImageResolutionMetadata());
        imageProcessingService = new ImageProcessingService(new ColorProfileManager());
    }

    @Test
    void writeSetsResolutionMetadata() throws IOException {
        BufferedImage source = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = source.createGraphics();
        try {
            g2d.setColor(Color.RED);
            g2d.fillRect(0, 0, 120, 80);
        } finally {
            g2d.dispose();
        }

        BufferedImage cmyk = imageProcessingService.convertToCmyk(source);
        int ppi = 300;
        byte[] bytes = writer.write(cmyk, ppi);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        // Проверяем структуру файла напрямую
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte byte1 = buffer.get();
        byte byte2 = buffer.get();
        
        // Определяем byte order (II = little-endian, MM = big-endian)
        if (byte1 == 0x49 && byte2 == 0x49) {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
        } else if (byte1 == 0x4D && byte2 == 0x4D) {
            buffer.order(ByteOrder.BIG_ENDIAN);
        } else {
            fail("Invalid TIFF header");
        }
        
        buffer.position(0);
        buffer.get(); // skip byte order marker
        buffer.get();
        assertEquals(42, buffer.getShort(), "TIFF magic number");
        int ifdOffset = buffer.getInt();
        assertEquals(8, ifdOffset);

        buffer.position(ifdOffset);
        int entryCount = buffer.getShort() & 0xFFFF;
        assertTrue(entryCount > 0, "IFD должен содержать теги");

        Map<Integer, TiffEntryView> entries = new HashMap<>();
        for (int i = 0; i < entryCount; i++) {
            int tag = buffer.getShort() & 0xFFFF;
            int type = buffer.getShort() & 0xFFFF;
            int count = buffer.getInt();
            long valueOrOffset = buffer.getInt() & 0xFFFFFFFFL;
            
            // Для типов SHORT (3) и LONG (4) с count=1 значение inline
            long actualValue = valueOrOffset;
            if (type == 3 && count == 1) { // SHORT
                // Значение в первых 2 байтах
                actualValue = (valueOrOffset >> (buffer.order() == ByteOrder.LITTLE_ENDIAN ? 0 : 16)) & 0xFFFF;
            } else if (type == 4 && count == 1) { // LONG
                actualValue = valueOrOffset;
            }
            
            entries.put(tag, new TiffEntryView(tag, type, count, actualValue));
        }

        // Проверяем основные теги
        assertTrue(entries.containsKey(256), "ImageWidth должен присутствовать");
        assertTrue(entries.containsKey(257), "ImageLength должен присутствовать");
        assertTrue(entries.containsKey(277), "SamplesPerPixel должен присутствовать");
        assertTrue(entries.containsKey(262), "PhotometricInterpretation должен присутствовать");
        
        assertEquals(120, entries.get(256).value, "ImageWidth");
        assertEquals(80, entries.get(257).value, "ImageLength");
        assertEquals(5, entries.get(262).value, "PhotometricInterpretation должен быть CMYK (5)");

        // Убеждаемся, что TIFF можно прочитать стандартным ImageIO
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            assertTrue(readers.hasNext(), "TIFF reader should be available");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                BufferedImage restored = reader.read(0);
                assertEquals(120, restored.getWidth());
                assertEquals(80, restored.getHeight());
            } finally {
                reader.dispose();
            }
        }
    }

    private double readRational(byte[] bytes, TiffEntryView entry) {
        assertNotNull(entry, "Ought to have entry");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        // Определяем byte order из заголовка
        byte byte1 = buffer.get(0);
        byte byte2 = buffer.get(1);
        if (byte1 == 0x49 && byte2 == 0x49) {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
        } else if (byte1 == 0x4D && byte2 == 0x4D) {
            buffer.order(ByteOrder.BIG_ENDIAN);
        }

        buffer.position((int) entry.value);
        long numerator = buffer.getInt() & 0xFFFFFFFFL;
        long denominator = buffer.getInt() & 0xFFFFFFFFL;
        assertTrue(denominator != 0, "Denominator must be non-zero");
        return (double) numerator / (double) denominator;
    }

    private static final class TiffEntryView {
        final int tag;
        final int type;
        final int count;
        final long value;

        TiffEntryView(int tag, int type, int count, long value) {
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.value = value;
        }
    }
}
