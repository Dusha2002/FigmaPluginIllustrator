package com.figma.export.font;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static com.figma.export.pdf.itext.FontEmbeddingUtil.ensureEmbeddable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontLoadingTest {

    private static final Logger logger = LoggerFactory.getLogger(FontLoadingTest.class);

    @Test
    void jostRegularFontLoadsSuccessfully() throws Exception {
        Path fontPath = Path.of("src/main/resources/fonts/Jost-Regular.ttf");
        assertTrue(Files.exists(fontPath), () -> "Font file not found: " + fontPath.toAbsolutePath());

        byte[] original = Files.readAllBytes(fontPath);
        short originalFsType = readFsType(original);
        logger.info("Original fsType={}, embeddable={}", Short.toUnsignedInt(originalFsType), originalFsType == 0);

        byte[] patched = ensureEmbeddable(original, fontPath.getFileName().toString(), logger);
        short patchedFsType = readFsType(patched);
        logger.info("Patched fsType={}", Short.toUnsignedInt(patchedFsType));

        assertEquals(0, patchedFsType, () -> "fsType was not reset: " + Short.toUnsignedInt(patchedFsType));
    }

    private short readFsType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int numTables = Short.toUnsignedInt(buffer.getShort(4));
        int entryOffset = 12;
        final int entrySize = 16;
        final int OS2_TAG = 0x4F532F32;
        for (int i = 0; i < numTables; i++) {
            int offset = entryOffset + i * entrySize;
            if (offset + entrySize > bytes.length) {
                break;
            }
            int tag = buffer.getInt(offset);
            if (tag == OS2_TAG) {
                int tableOffset = buffer.getInt(offset + 8);
                int fsTypeOffset = tableOffset + 8;
                if (fsTypeOffset >= 0 && fsTypeOffset + 2 <= bytes.length) {
                    return buffer.getShort(fsTypeOffset);
                }
                break;
            }
        }
        return 0;
    }
}
