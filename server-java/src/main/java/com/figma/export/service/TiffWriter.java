package com.figma.export.service;

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
import java.util.Iterator;

@Service
public class TiffWriter {

    static {
        ImageIO.setUseCache(false);
        ImageIO.scanForPlugins();
    }

    private static final Logger logger = LoggerFactory.getLogger(TiffWriter.class);

    private final ImageResolutionMetadata resolutionMetadata;

    public TiffWriter(ImageResolutionMetadata resolutionMetadata) {
        this.resolutionMetadata = resolutionMetadata;
    }

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
        
        // Получаем CMYK данные
        byte[] imageData = extractImageData(image);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(8 + 12 * 20 + imageData.length + 4);
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        
        // TIFF Header
        buffer.put((byte) 0x49); // Little endian
        buffer.put((byte) 0x49);
        buffer.putShort((short) 42); // TIFF magic
        buffer.putInt(8); // Offset to first IFD
        
        // IFD
        int ifdOffset = 8;
        buffer.putShort((short) 14); // Number of directory entries
        
        // ImageWidth (256)
        writeIFDEntry(buffer, 256, 4, 1, width);
        // ImageLength (257)
        writeIFDEntry(buffer, 257, 4, 1, height);
        // BitsPerSample (258) - 4 samples of 8 bits
        int bpsOffset = ifdOffset + 2 + 14 * 12 + 4;
        writeIFDEntry(buffer, 258, 3, 4, bpsOffset);
        // Compression (259) - no compression
        writeIFDEntry(buffer, 259, 3, 1, 1);
        // PhotometricInterpretation (262) - CMYK = 5
        writeIFDEntry(buffer, 262, 3, 1, 5);
        // StripOffsets (273)
        int stripOffset = bpsOffset + 8 + 8;
        writeIFDEntry(buffer, 273, 4, 1, stripOffset);
        // SamplesPerPixel (277)
        writeIFDEntry(buffer, 277, 3, 1, 4);
        // RowsPerStrip (278)
        writeIFDEntry(buffer, 278, 4, 1, height);
        // StripByteCounts (279)
        writeIFDEntry(buffer, 279, 4, 1, imageData.length);
        // XResolution (282)
        int xresOffset = bpsOffset + 8;
        writeIFDEntry(buffer, 282, 5, 1, xresOffset);
        // YResolution (283)
        int yresOffset = xresOffset + 8;
        writeIFDEntry(buffer, 283, 5, 1, yresOffset);
        // PlanarConfiguration (284) - chunky
        writeIFDEntry(buffer, 284, 3, 1, 1);
        // ResolutionUnit (296) - inches = 2
        writeIFDEntry(buffer, 296, 3, 1, 2);
        // ExtraSamples (338) - none
        writeIFDEntry(buffer, 338, 3, 1, 0);
        
        // Next IFD offset (0 = no more)
        buffer.putInt(0);
        
        // BitsPerSample values
        buffer.putShort((short) 8);
        buffer.putShort((short) 8);
        buffer.putShort((short) 8);
        buffer.putShort((short) 8);
        
        // XResolution rational
        buffer.putInt(ppi);
        buffer.putInt(1);
        
        // YResolution rational
        buffer.putInt(ppi);
        buffer.putInt(1);
        
        // Write to output
        baos.write(buffer.array(), 0, buffer.position());
        baos.write(imageData);
        
        return baos.toByteArray();
    }
    
    private void writeIFDEntry(java.nio.ByteBuffer buffer, int tag, int type, int count, int value) {
        buffer.putShort((short) tag);
        buffer.putShort((short) type);
        buffer.putInt(count);
        buffer.putInt(value);
    }
    
    private byte[] extractImageData(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        java.awt.image.Raster raster = image.getRaster();
        java.awt.image.DataBuffer dataBuffer = raster.getDataBuffer();
        
        if (dataBuffer instanceof java.awt.image.DataBufferByte) {
            return ((java.awt.image.DataBufferByte) dataBuffer).getData();
        }
        
        // Fallback: extract manually
        int numBands = raster.getNumBands();
        byte[] data = new byte[width * height * numBands];
        int[] pixel = new int[numBands];
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.getPixel(x, y, pixel);
                for (int b = 0; b < numBands; b++) {
                    data[index++] = (byte) pixel[b];
                }
            }
        }
        return data;
    }

    private ImageWriter selectWriter(ImageTypeSpecifier typeSpecifier) {
        ImageWriter preferred = null;
        ImageWriter fallback = null;
        Iterator<ImageWriter> writers = ImageIO.getImageWriters(typeSpecifier, "tiff");
        while (writers.hasNext()) {
            ImageWriter candidate = writers.next();
            String className = candidate.getClass().getName();
            if (className.startsWith("com.twelvemonkeys.imageio.plugins.tiff")) {
                preferred = candidate;
                break;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        if (preferred != null) {
            return preferred;
        }
        return fallback;
    }
}
