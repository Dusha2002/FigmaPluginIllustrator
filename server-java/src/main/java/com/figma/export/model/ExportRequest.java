package com.figma.export.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.HashMap;
import java.util.Map;

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

    @Pattern(regexp = "(?i)standard|supersample|texthint", message = "tiffQuality должен быть standard, supersample или texthint")
    private String tiffQuality;

    @Pattern(regexp = "(?i)embed|outline", message = "svgTextMode должен быть embed или outline")
    private String svgTextMode;
    
    private boolean batch;
    
    private String zipName;
    
    // Метаданные для множественных файлов (индекс -> ширина/высота)
    private Map<Integer, Integer> widthPxMap = new HashMap<>();
    private Map<Integer, Integer> heightPxMap = new HashMap<>();

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

    public String getTiffQuality() {
        return tiffQuality;
    }

    public void setTiffQuality(String tiffQuality) {
        this.tiffQuality = tiffQuality;
    }

    public String getSvgTextMode() {
        return svgTextMode;
    }

    public void setSvgTextMode(String svgTextMode) {
        this.svgTextMode = svgTextMode;
    }

    public Map<Integer, Integer> getWidthPxMap() {
        return widthPxMap;
    }

    public void setWidthPxMap(Map<Integer, Integer> widthPxMap) {
        this.widthPxMap = widthPxMap;
    }

    public Map<Integer, Integer> getHeightPxMap() {
        return heightPxMap;
    }

    public void setHeightPxMap(Map<Integer, Integer> heightPxMap) {
        this.heightPxMap = heightPxMap;
    }
    
    // Вспомогательные методы для работы с параметрами по индексу
    public void setWidthPx(int index, Integer width) {
        if (width != null && width > 0) {
            widthPxMap.put(index, width);
        }
    }
    
    public void setHeightPx(int index, Integer height) {
        if (height != null && height > 0) {
            heightPxMap.put(index, height);
        }
    }
    
    public Integer getWidthPx(int index) {
        return widthPxMap.get(index);
    }
    
    public Integer getHeightPx(int index) {
        return heightPxMap.get(index);
    }

    public boolean isSvgTextAsOutlines() {
        return svgTextMode != null && svgTextMode.equalsIgnoreCase("outline");
    }

    public boolean isBatch() {
        return batch;
    }

    public void setBatch(boolean batch) {
        this.batch = batch;
    }

    public String getZipName() {
        return zipName;
    }

    public void setZipName(String zipName) {
        this.zipName = zipName;
    }
}
