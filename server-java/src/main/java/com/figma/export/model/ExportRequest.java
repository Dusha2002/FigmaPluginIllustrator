package com.figma.export.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public class ExportRequest {

    @NotBlank
    @Pattern(regexp = "(?i)pdf|tiff", message = "format должен быть pdf или tiff")
    private String format;

    @NotBlank
    private String name;

    @Positive
    private int ppi;

    @Min(1)
    private Integer widthPx;

    @Min(1)
    private Integer heightPx;

    private boolean tiffLzw;

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

    public int getPpi() {
        return ppi;
    }

    public void setPpi(int ppi) {
        this.ppi = ppi;
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

    public boolean isTiffLzw() {
        return tiffLzw;
    }

    public void setTiffLzw(boolean tiffLzw) {
        this.tiffLzw = tiffLzw;
    }
}
