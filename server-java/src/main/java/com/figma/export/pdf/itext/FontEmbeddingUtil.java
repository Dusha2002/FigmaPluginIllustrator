package com.figma.export.pdf.itext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

public final class FontEmbeddingUtil {

    private static final Logger fallbackLogger = LoggerFactory.getLogger(FontEmbeddingUtil.class);

    private FontEmbeddingUtil() {
    }

    public static byte[] ensureEmbeddable(byte[] fontBytes, String fileName, Logger logger) {
        if (fontBytes == null || fontBytes.length < 12) {
            return fontBytes;
        }
        Logger effectiveLogger = logger != null ? logger : fallbackLogger;
        String lowerName = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        if (!lowerName.endsWith(".ttf")) {
            return fontBytes;
        }

        byte[] patched = fontBytes.clone();
        ByteBuffer buffer = ByteBuffer.wrap(patched).order(ByteOrder.BIG_ENDIAN);
        int numTables = readUnsignedShort(buffer, 4);
        final int tableDirOffset = 12;
        final int entrySize = 16;
        final int OS2_TAG = 0x4F532F32; // 'O''S''/''2'
        final int HEAD_TAG = 0x68656164; // 'head'

        boolean modified = false;
        boolean os2Found = false;
        int headEntryOffset = -1;

        for (int i = 0; i < numTables; i++) {
            int entryOffset = tableDirOffset + i * entrySize;
            if (entryOffset + entrySize > patched.length) {
                break;
            }
            int tag = buffer.getInt(entryOffset);
            if (tag == OS2_TAG) {
                os2Found = true;
                int tableOffset = buffer.getInt(entryOffset + 8);
                int tableLength = buffer.getInt(entryOffset + 12);
                int fsTypeOffset = tableOffset + 8;
                if (fsTypeOffset >= 0 && fsTypeOffset + 2 <= patched.length) {
                    short fsType = buffer.getShort(fsTypeOffset);
                    if (fsType != 0) {
                        buffer.putShort(fsTypeOffset, (short) 0);
                        long os2Checksum = calculateTableChecksum(patched, tableOffset, tableLength);
                        buffer.putInt(entryOffset + 4, (int) os2Checksum);
                        modified = true;
                        effectiveLogger.debug("fsType for '{}' reset from {} to 0 (embedding allowed)", fileName, Short.toUnsignedInt(fsType));
                    } else {
                        effectiveLogger.debug("fsType for '{}' уже 0, корректировка не требуется", fileName);
                    }
                } else {
                    effectiveLogger.warn("Не удалось обновить fsType для '{}': offset {} вне диапазона", fileName, fsTypeOffset);
                }
            } else if (tag == HEAD_TAG) {
                headEntryOffset = entryOffset;
            }
        }

        if (!os2Found) {
            effectiveLogger.debug("Таблица OS/2 не найдена в '{}', fsType изменить нельзя", fileName);
        }

        if (!modified) {
            return fontBytes;
        }

        if (headEntryOffset < 0) {
            effectiveLogger.warn("Таблица 'head' не найдена в '{}', невозможно обновить checkSumAdjustment", fileName);
            return patched;
        }

        int headOffset = buffer.getInt(headEntryOffset + 8);
        if (headOffset < 0 || headOffset + 12 > patched.length) {
            effectiveLogger.warn("Некорректный offset таблицы 'head' в '{}': {}", fileName, headOffset);
            return patched;
        }

        // Сбросить предыдущее значение и пересчитать общую сумму.
        buffer.putInt(headOffset + 8, 0);
        long fontChecksum = calculateTableChecksum(patched, 0, patched.length);
        long checkSumAdjustment = 0xB1B0AFBAL - fontChecksum;
        buffer.putInt(headOffset + 8, (int) checkSumAdjustment);
        effectiveLogger.debug("checkSumAdjustment for '{}' set to 0x{}", fileName, Long.toHexString(checkSumAdjustment & 0xFFFFFFFFL));

        return patched;
    }

    private static int readUnsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static long calculateTableChecksum(byte[] bytes, int offset, int length) {
        int nLongs = (length + 3) / 4;
        long sum = 0L;
        for (int i = 0; i < nLongs; i++) {
            int pos = offset + i * 4;
            long value = readUInt32(bytes, pos);
            sum = (sum + value) & 0xFFFFFFFFL;
        }
        return sum & 0xFFFFFFFFL;
    }

    private static long readUInt32(byte[] bytes, int offset) {
        int b0 = offset < bytes.length ? bytes[offset] & 0xFF : 0;
        int b1 = offset + 1 < bytes.length ? bytes[offset + 1] & 0xFF : 0;
        int b2 = offset + 2 < bytes.length ? bytes[offset + 2] & 0xFF : 0;
        int b3 = offset + 3 < bytes.length ? bytes[offset + 3] & 0xFF : 0;
        return ((long) b0 << 24) | ((long) b1 << 16) | ((long) b2 << 8) | b3;
    }
}
