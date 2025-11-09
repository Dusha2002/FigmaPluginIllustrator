package com.figma.export.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum TiffCompression {
    NONE("none"),
    LZW("lzw"),
    ZIP("zip"),
    DEFLATE("deflate"),
    JPEG("jpeg");

    private final String id;

    TiffCompression(String id) {
        this.id = id;
    }

    @JsonValue
    public String getId() {
        return id;
    }

    public String asLookupKey() {
        return id.toUpperCase(Locale.ROOT);
    }

    @JsonCreator
    public static TiffCompression from(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TiffCompression option : values()) {
            if (option.id.equals(normalized)) {
                return option;
            }
        }
        throw new IllegalArgumentException("Unsupported TIFF compression: " + value);
    }
}
