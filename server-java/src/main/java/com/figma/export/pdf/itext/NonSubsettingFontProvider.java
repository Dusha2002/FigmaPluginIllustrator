package com.figma.export.pdf.itext;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.layout.font.FontInfo;
import com.itextpdf.layout.font.FontProvider;
import com.itextpdf.layout.font.FontSet;

/**
 * Расширение FontProvider, отключающее сабсеттинг для всех создаваемых PdfFont.
 */
class NonSubsettingFontProvider extends FontProvider {

    NonSubsettingFontProvider(FontSet fontSet, String defaultFontFamily) {
        super(fontSet, defaultFontFamily);
    }

    @Override
    public PdfFont getPdfFont(FontInfo fontInfo) {
        PdfFont font = super.getPdfFont(fontInfo);
        disableSubsetting(font);
        return font;
    }

    @Override
    public PdfFont getPdfFont(FontInfo fontInfo, FontSet alternativeFontSet) {
        PdfFont font = super.getPdfFont(fontInfo, alternativeFontSet);
        disableSubsetting(font);
        return font;
    }

    private void disableSubsetting(PdfFont font) {
        if (font != null) {
            font.setSubset(false);
        }
    }
}
