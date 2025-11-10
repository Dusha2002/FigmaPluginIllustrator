package com.figma.export.pdf;

import java.util.Locale;

public enum PdfStandard {
    NONE("none", null, null, null, null),
    PDFX_1A_2001("pdfx-1a", "PDF/X-1:2001", "PDF/X-1a:2001", 1.3f, 1.3f),
    PDFX_3_2002("pdfx-3", "PDF/X-3:2002", "PDF/X-3:2002", 1.3f, 1.3f),
    PDFX_4("pdfx-4", "PDF/X-4", "PDF/X-4", 1.6f, null);

    private final String requestValue;
    private final String pdfxVersion;
    private final String pdfxConformance;
    private final Float exactPdfVersion;
    private final Float minPdfVersion;

    PdfStandard(String requestValue,
                String pdfxVersion,
                String pdfxConformance,
                Float exactPdfVersion,
                Float minPdfVersion) {
        this.requestValue = requestValue;
        this.pdfxVersion = pdfxVersion;
        this.pdfxConformance = pdfxConformance;
        this.exactPdfVersion = exactPdfVersion;
        this.minPdfVersion = minPdfVersion;
    }

    public static PdfStandard fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PdfStandard standard : values()) {
            if (standard.requestValue.equals(normalized)) {
                return standard;
            }
        }
        return NONE;
    }

    public float resolvePdfVersion(float requestedVersion) {
        if (exactPdfVersion != null) {
            return exactPdfVersion;
        }
        if (minPdfVersion != null) {
            return Math.max(minPdfVersion, requestedVersion);
        }
        return requestedVersion;
    }

    public String formatPdfVersion(float requestedVersion) {
        float resolved = resolvePdfVersion(requestedVersion);
        return String.format(Locale.ROOT, "%.1f", resolved);
    }

    public String getPdfxVersion() {
        return pdfxVersion;
    }

    public String getPdfxConformance() {
        return pdfxConformance;
    }

    public boolean isPdfx() {
        return this != NONE;
    }

    public boolean forbidsTransparency() {
        return this == PDFX_1A_2001 || this == PDFX_3_2002;
    }
}
