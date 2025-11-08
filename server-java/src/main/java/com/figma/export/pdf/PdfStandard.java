package com.figma.export.pdf;

import java.util.Arrays;
import java.util.Locale;

public enum PdfStandard {
    NONE("none", null, null, null),
    PDFX_1A_2001("PDF/X-1a:2001", "1.3", "PDF/X-1:2001", "PDF/X-1a:2001"),
    PDFX_3_2002("PDF/X-3:2002", "1.3", "PDF/X-3:2002", "PDF/X-3:2002"),
    PDFX_3_2003("PDF/X-3:2003", "1.3", "PDF/X-3:2003", "PDF/X-3:2003"),
    PDFX_4_2008("PDF/X-4:2008", "1.6", "PDF/X-4", "PDF/X-4:2008");

    private final String id;
    private final String minimumVersion;
    private final String pdfxIdentifier;
    private final String conformance;

    PdfStandard(String id, String minimumVersion, String pdfxIdentifier, String conformance) {
        this.id = id;
        this.minimumVersion = minimumVersion;
        this.pdfxIdentifier = pdfxIdentifier;
        this.conformance = conformance;
    }

    public String getId() {
        return id;
    }

    public String getMinimumVersion() {
        return minimumVersion;
    }

    public String getPdfxIdentifier() {
        return pdfxIdentifier;
    }

    public String getConformance() {
        return conformance;
    }

    public boolean isPdfx() {
        return this != NONE;
    }

    public String ensureVersion(String requestedVersion) {
        String requested = normalizeVersion(requestedVersion);
        if (minimumVersion == null) {
            return requested;
        }
        double requestedValue = parseVersion(requested);
        double minimumValue = parseVersion(minimumVersion);
        if (Double.isNaN(requestedValue) || requestedValue < minimumValue) {
            return minimumVersion;
        }
        return requested;
    }

    private String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return minimumVersion != null ? minimumVersion : "1.4";
        }
        return version.trim();
    }

    private double parseVersion(String version) {
        try {
            return Double.parseDouble(version);
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    public static PdfStandard fromName(String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(standard -> standard.id.toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(NONE);
    }
}
