package com.figma.export.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

@Service
public class TiffWriter {

    static {
        ImageIO.setUseCache(false);
        ImageIO.scanForPlugins();
    }

    private static final Logger logger = LoggerFactory.getLogger(TiffWriter.class);

    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;
    private static final int TYPE_UNDEFINED = 7;

    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_BITS_PER_SAMPLE = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_PHOTOMETRIC_INTERPRETATION = 262;
    private static final int TAG_STRIP_OFFSETS = 273;
    private static final int TAG_SAMPLES_PER_PIXEL = 277;
    private static final int TAG_ROWS_PER_STRIP = 278;
    private static final int TAG_STRIP_BYTE_COUNTS = 279;
    private static final int TAG_X_RESOLUTION = 282;
    private static final int TAG_Y_RESOLUTION = 283;
    private static final int TAG_PLANAR_CONFIGURATION = 284;
    private static final int TAG_RESOLUTION_UNIT = 296;
    private static final int TAG_INK_SET = 332;
    private static final int TAG_NUMBER_OF_INKS = 334;
    private static final int TAG_EXTRA_SAMPLES = 338;
    private static final int TAG_ICC_PROFILE = 34675;

    private static final int INK_SET_PROCESS = 1;
    private static final int RESOLUTION_UNIT_INCH = 2;

    public byte[] write(BufferedImage image, int ppi) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        long startNs = System.nanoTime();
        if (logger.isInfoEnabled()) {
            logger.info("TIFF write start: size={}x{}, ppi={}", image.getWidth(), image.getHeight(), ppi);
        }
        
        byte[] result = writeDirectTiff(image, ppi);
        
        if (logger.isInfoEnabled()) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            logger.info("TIFF write finished: bytes={}, time={} мс", result.length, elapsedMs);
        }
        return result;
    }
    
    private byte[] writeDirectTiff(BufferedImage image, int ppi) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();

        byte[] imageData = extractImageData(image);
        byte[] iccProfile = extractIccProfile(image);

        List<TiffEntry> entries = new ArrayList<>();
        entries.add(TiffEntry.inline(TAG_IMAGE_WIDTH, TYPE_LONG, 1, width));
        entries.add(TiffEntry.inline(TAG_IMAGE_LENGTH, TYPE_LONG, 1, height));
        entries.add(TiffEntry.withData(TAG_BITS_PER_SAMPLE, TYPE_SHORT, 4, shortsToBytes(8, 8, 8, 8)));
        entries.add(TiffEntry.inline(TAG_COMPRESSION, TYPE_SHORT, 1, 1));
        entries.add(TiffEntry.inline(TAG_PHOTOMETRIC_INTERPRETATION, TYPE_SHORT, 1, 5));
        TiffEntry stripOffsets = TiffEntry.inline(TAG_STRIP_OFFSETS, TYPE_LONG, 1, 0);
        entries.add(stripOffsets);
        entries.add(TiffEntry.inline(TAG_SAMPLES_PER_PIXEL, TYPE_SHORT, 1, 4));
        entries.add(TiffEntry.inline(TAG_ROWS_PER_STRIP, TYPE_LONG, 1, height));
        entries.add(TiffEntry.inline(TAG_STRIP_BYTE_COUNTS, TYPE_LONG, 1, imageData.length));
        entries.add(TiffEntry.withData(TAG_X_RESOLUTION, TYPE_RATIONAL, 1, rationalToBytes(ppi, 1)));
        entries.add(TiffEntry.withData(TAG_Y_RESOLUTION, TYPE_RATIONAL, 1, rationalToBytes(ppi, 1)));
        entries.add(TiffEntry.inline(TAG_PLANAR_CONFIGURATION, TYPE_SHORT, 1, 1));
        entries.add(TiffEntry.inline(TAG_RESOLUTION_UNIT, TYPE_SHORT, 1, RESOLUTION_UNIT_INCH));
        entries.add(TiffEntry.inline(TAG_INK_SET, TYPE_SHORT, 1, INK_SET_PROCESS));
        entries.add(TiffEntry.inline(TAG_NUMBER_OF_INKS, TYPE_SHORT, 1, 4));
        entries.add(TiffEntry.inline(TAG_EXTRA_SAMPLES, TYPE_SHORT, 1, 0));

        if (iccProfile != null && iccProfile.length > 0) {
            entries.add(TiffEntry.withData(TAG_ICC_PROFILE, TYPE_UNDEFINED, iccProfile.length, iccProfile));
        }

        int entryCount = entries.size();
        int ifdSize = 2 + entryCount * 12 + 4;
        int extrasOffset = 8 + ifdSize;

        int extrasLength = 0;
        for (TiffEntry entry : entries) {
            if (entry.hasData()) {
                entry.value = extrasOffset + extrasLength;
                extrasLength += entry.data.length;
                if ((entry.data.length & 1) != 0) {
                    extrasLength += 1;
                }
            }
        }

        int imageOffset = extrasOffset + extrasLength;
        stripOffsets.value = imageOffset;

        int totalSize = imageOffset + imageData.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put((byte) 0x49);
        buffer.put((byte) 0x49);
        buffer.putShort((short) 42);
        buffer.putInt(8);

        buffer.putShort((short) entryCount);
        for (TiffEntry entry : entries) {
            buffer.putShort((short) entry.tag);
            buffer.putShort((short) entry.type);
            buffer.putInt(entry.count);
            buffer.putInt((int) entry.value);
        }
        buffer.putInt(0);

        for (TiffEntry entry : entries) {
            if (entry.hasData()) {
                buffer.put(entry.data);
                if ((entry.data.length & 1) != 0) {
                    buffer.put((byte) 0);
                }
            }
        }

        buffer.put(imageData);

        return buffer.array();
    }

    private static byte[] extractImageData(BufferedImage image) {
        DataBuffer dataBuffer = image.getRaster().getDataBuffer();
        if (dataBuffer instanceof DataBufferByte dataBufferByte) {
            return dataBufferByte.getData().clone();
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int numBands = image.getRaster().getNumBands();
        byte[] data = new byte[width * height * numBands];
        int[] pixel = new int[numBands];
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.getRaster().getPixel(x, y, pixel);
                for (int band = 0; band < numBands; band++) {
                    data[index++] = (byte) pixel[band];
                }
            }
        }
        return data;
    }

    private static byte[] extractIccProfile(BufferedImage image) {
        ColorSpace colorSpace = image.getColorModel().getColorSpace();
        if (colorSpace instanceof ICC_ColorSpace iccColorSpace) {
            ICC_Profile profile = iccColorSpace.getProfile();
            if (profile != null) {
                return profile.getData();
            }
        }
        return null;
    }

    private static byte[] shortsToBytes(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    private static byte[] rationalToBytes(int numerator, int denominator) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(numerator);
        buffer.putInt(denominator);
        return buffer.array();
    }

    private static final class TiffEntry {
        final int tag;
        final int type;
        final int count;
        long value;
        byte[] data;

        private TiffEntry(int tag, int type, int count, long value, byte[] data) {
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.value = value;
            this.data = data;
        }

        static TiffEntry inline(int tag, int type, int count, long value) {
            return new TiffEntry(tag, type, count, value, null);
        }

        static TiffEntry withData(int tag, int type, int count, byte[] data) {
            return new TiffEntry(tag, type, count, 0, data);
        }

        boolean hasData() {
            return data != null;
        }
    }
}
