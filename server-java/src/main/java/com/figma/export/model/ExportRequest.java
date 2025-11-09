package com.figma.export.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public class ExportRequest {

    private static final String DEFAULT_COLOR_PROFILE = "coated_fogra39";

    @NotBlank
    @Pattern(regexp = "(?i)pdf|tiff", message = "format должен быть pdf или tiff")
    private String format;

    @NotBlank
    private String name;

    @Positive
    private int dpi;

    @NotBlank
    @Pattern(regexp = "\\d+(?:\\.\\d+)?", message = "pdfVersion должен быть числом")
    private String pdfVersion = "1.4";

    @NotBlank
    @Pattern(regexp = "(?i)none|pdf/x-1a:2001|pdf/x-3:2002|pdf/x-3:2003|pdf/x-4:2008", message = "pdfStandard содержит неподдерживаемое значение")
    private String pdfStandard = "none";

    @NotBlank
    @Pattern(regexp = "(?i)coated_fogra39|iso_coated_v2|us_web_coated_swop", message = "pdfColorProfile содержит неподдерживаемое значение")
    private String pdfColorProfile = DEFAULT_COLOR_PROFILE;

    @NotBlank
    @Pattern(regexp = "(?i)none|lzw|zip|deflate|jpeg", message = "tiffCompression содержит неподдерживаемое значение")
    private String tiffCompression = "none";

    @NotBlank
    @Pattern(regexp = "(?i)none|fast|balanced|best", message = "tiffAntialias содержит неподдерживаемое значение")
    private String tiffAntialias = "none";

    @Min(1)
    private int tiffDpi;

    @Min(1)
    private Integer widthPx;

    @Min(1)
    private Integer heightPx;

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getDpi() {
        return dpi;
    }

    public void setDpi(int dpi) {
        this.dpi = dpi;
    }

    public String getPdfVersion() {
        return pdfVersion;
    }

    public void setPdfVersion(String pdfVersion) {
        this.pdfVersion = pdfVersion;
    }

    public String getPdfStandard() {
        return pdfStandard;
    }

    public void setPdfStandard(String pdfStandard) {
        this.pdfStandard = pdfStandard;
    }

    public String getPdfColorProfile() {
        return pdfColorProfile;
    }

    public void setPdfColorProfile(String pdfColorProfile) {
        this.pdfColorProfile = pdfColorProfile;
    }

    public String getTiffCompression() {
        return tiffCompression;
    }

    public void setTiffCompression(String tiffCompression) {
        this.tiffCompression = tiffCompression;
    }

    public String getTiffAntialias() {
        return tiffAntialias;
    }

    public void setTiffAntialias(String tiffAntialias) {
        this.tiffAntialias = tiffAntialias;
    }

    public int getTiffDpi() {
        return tiffDpi;
    }

    public void setTiffDpi(int tiffDpi) {
        this.tiffDpi = tiffDpi;
    }

    public Integer getWidthPx() {
        return widthPx;
    }

    public void setWidthPx(Integer widthPx) {
        this.widthPx = widthPx;
    }

    public Integer getHeightPx() {
        return heightPx;
    }

    public void setHeightPx(Integer heightPx) {
        this.heightPx = heightPx;
    }
}
