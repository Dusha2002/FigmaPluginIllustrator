package com.figma.export.pdf;

import de.rototor.pdfbox.graphics2d.IPdfBoxGraphics2DColor;
import de.rototor.pdfbox.graphics2d.IPdfBoxGraphics2DColorMapper;
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2DColorMapper;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;

import java.awt.Color;
import java.awt.color.ICC_ColorSpace;
import java.util.Objects;

/**
 * Преобразует все RGB-цвета в CMYK с использованием заданного ICC-профиля,
 * чтобы сохранить векторные объекты при экспорте PDF.
 */
public class CmykPdfColorMapper extends PdfBoxGraphics2DColorMapper {

    private final ICC_ColorSpace colorSpace;

    public CmykPdfColorMapper(ICC_ColorSpace colorSpace) {
        this.colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
    }

    @Override
    public PDColor mapColor(Color color, IColorMapperEnv env) {
        if (color == null) {
            return new PDColor(new float[]{0f, 0f, 0f, 0f}, PDDeviceCMYK.INSTANCE);
        }
        if (color instanceof IPdfBoxGraphics2DColor pdfColor) {
            return pdfColor.toPDColor();
        }
        if (color.getClass().getSimpleName().equals("CMYKColor")) {
            return super.mapColor(color, env);
        }
        float[] rgb = new float[]{
                color.getRed() / 255f,
                color.getGreen() / 255f,
                color.getBlue() / 255f
        };
        float[] cmyk = colorSpace.fromRGB(rgb);
        clampUnitInterval(cmyk);
        ensurePureBlack(color, cmyk);
        return new PDColor(cmyk, PDDeviceCMYK.INSTANCE);
    }

    private void clampUnitInterval(float[] values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] < 0f) {
                values[i] = 0f;
            } else if (values[i] > 1f) {
                values[i] = 1f;
            }
        }
    }

    private void ensurePureBlack(Color color, float[] cmyk) {
        if (cmyk.length != 4) {
            return;
        }
        if (color.getRed() == color.getGreen() && color.getGreen() == color.getBlue()) {
            // Нейтральный оттенок серого — повысим долю K и занулим CMY, чтобы избежать смещения.
            float luminance = color.getRed() / 255f;
            if (luminance <= 0.02f) {
                cmyk[0] = 0f;
                cmyk[1] = 0f;
                cmyk[2] = 0f;
                cmyk[3] = 1f;
            }
        }
    }
}
