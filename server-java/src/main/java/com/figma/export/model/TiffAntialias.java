package com.figma.export.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum TiffAntialias {
    NONE("none"),
    FAST("fast"),
    BALANCED("balanced"),
    BEST("best");

    private final String id;

    TiffAntialias(String id) {
        this.id = id;
    }

    @JsonValue
    public String getId() {
        return id;
    }

    public String asLookupKey() {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static TiffAntialias from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TiffAntialias option : values()) {
            if (option.id.equals(normalized)) {
                return option;
            }
        }
        throw new IllegalArgumentException("Unsupported TIFF antialias option: " + value);
    }
}
