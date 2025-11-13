package com.figma.export.pdf.itext;

import com.figma.export.color.ColorProfile;
import com.figma.export.color.ColorProfileManager;
import com.figma.export.exception.ConversionException;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.exceptions.PdfException;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfOutputIntent;
import com.itextpdf.kernel.pdf.PdfVersion;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.layout.font.FontProvider;
import com.itextpdf.layout.font.FontSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Загружает ресурсы (ICC-профили, шрифты) и предоставляет базовые настройки для iText 7.
 */
@Component
public class ITextPdfResourceFactory {

    private static final Logger logger = LoggerFactory.getLogger(ITextPdfResourceFactory.class);

    private static final String FONT_RESOURCE_LOCATION = "classpath*:fonts/**/*.";
    private static final String COLOR_PROFILE_REGISTRY = "http://www.color.org";
    private static final PdfVersion DEFAULT_PDF_VERSION = PdfVersion.PDF_1_6;

    private final ColorProfileManager colorProfileManager;
    private final ResourcePatternResolver resourcePatternResolver;
    private final List<Resource> embeddedFontResources;

    public ITextPdfResourceFactory(ColorProfileManager colorProfileManager,
                                   ResourcePatternResolver resourcePatternResolver) {
        this.colorProfileManager = colorProfileManager;
        this.resourcePatternResolver = resourcePatternResolver;
        this.embeddedFontResources = loadEmbeddedFontResources();
        if (embeddedFontResources.isEmpty()) {
            logger.info("Встроенные шрифты для iText не найдены. Будут использоваться стандартные и системные гарнитуры.");
        } else {
            logger.info("Обнаружено {} шрифтов для iText в classpath.", embeddedFontResources.size());
        }
    }

    /**
     * Создаёт {@link WriterProperties} с заданной версией PDF.
     */
    public WriterProperties createWriterProperties(String requestedVersion) {
        WriterProperties properties = new WriterProperties();
        properties.setPdfVersion(resolvePdfVersion(requestedVersion));
        properties.useSmartMode();
        properties.setFullCompressionMode(true);
        return properties;
    }

    /**
     * Создаёт {@link FontProvider} с зарегистрированными стандартными, системными и встроенными шрифтами.
     */
    public FontProvider createFontProvider() {
        FontSet fontSet = new FontSet();
        String defaultFamily = null;
        for (Resource resource : embeddedFontResources) {
            String displayName = safeName(resource);
            FontProgram fontProgram = loadFontProgram(resource, displayName);
            if (fontProgram == null) {
                continue;
            }

            if (!registerFontProgram(fontSet, fontProgram, displayName)) {
                continue;
            }
            if (defaultFamily == null) {
                defaultFamily = fontProgram.getFontNames().getFontName();
            }
        }

        String fallbackFamily = defaultFamily != null ? defaultFamily : "Helvetica";
        FontProvider provider = new FontProvider(fontSet, fallbackFamily);

        provider.addStandardPdfFonts();
        provider.addSystemFonts();
        if (provider.getFontSet().isEmpty()) {
            logger.warn("FontProvider не содержит шрифтов после инициализации. SVG текст может быть не встроен.");
        }
        return provider;
    }

    /**
     * Добавляет в документ {@link PdfOutputIntent} на основе выбранного ICC-профиля.
     */
    public void applyOutputIntent(PdfDocument document, ColorProfile profile) {
        if (document == null) {
            throw new IllegalArgumentException("document не должен быть null");
        }
        if (profile == null) {
            logger.debug("ICC-профиль не задан, PdfOutputIntent не будет добавлен.");
            return;
        }
        try (InputStream profileStream = new ByteArrayInputStream(profile.getIccBytes())) {
            String description = profile.getDescription() != null ? profile.getDescription() : profile.getDisplayName();
            PdfOutputIntent outputIntent = new PdfOutputIntent(
                    profile.getOutputConditionIdentifier(),
                    profile.getOutputCondition(),
                    COLOR_PROFILE_REGISTRY,
                    description,
                    profileStream
            );
            document.addOutputIntent(outputIntent);
        } catch (IOException ex) {
            throw new ConversionException("Не удалось встроить ICC-профиль в PDF документ.", ex);
        }
    }

    public ColorProfile getDefaultProfile() {
        return colorProfileManager.getDefaultProfile();
    }

    public ColorProfile getProfileOrDefault(String profileId) {
        return colorProfileManager.getProfileOrDefault(profileId);
    }

    private PdfVersion resolvePdfVersion(String explicitVersion) {
        if (explicitVersion == null || explicitVersion.isBlank()) {
            return DEFAULT_PDF_VERSION;
        }
        String normalized = explicitVersion.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1.3" -> PdfVersion.PDF_1_3;
            case "1.4" -> PdfVersion.PDF_1_4;
            case "1.5" -> PdfVersion.PDF_1_5;
            case "1.6" -> PdfVersion.PDF_1_6;
            case "1.7" -> PdfVersion.PDF_1_7;
            default -> {
                logger.warn("Неподдерживаемая версия PDF '{}'. Используется версия по умолчанию {}.", explicitVersion, DEFAULT_PDF_VERSION);
                yield DEFAULT_PDF_VERSION;
            }
        };
    }

    private List<Resource> loadEmbeddedFontResources() {
        List<Resource> resources = new ArrayList<>();
        for (String pattern : fontPatterns()) {
            try {
                Resource[] found = resourcePatternResolver.getResources(pattern);
                for (Resource resource : found) {
                    if (!resource.isReadable()) {
                        continue;
                    }
                    String name = resource.getFilename();
                    if (name == null) {
                        continue;
                    }
                    String lowerName = name.toLowerCase(Locale.ROOT);
                    if (!(lowerName.endsWith(".ttf") || lowerName.endsWith(".otf"))) {
                        continue;
                    }
                    resources.add(resource);
                }
            } catch (IOException ex) {
                logger.warn("Не удалось загрузить шрифты по шаблону '{}'.", pattern, ex);
            }
        }
        return Collections.unmodifiableList(resources);
    }

    private List<String> fontPatterns() {
        return List.of(
                FONT_RESOURCE_LOCATION + "ttf",
                FONT_RESOURCE_LOCATION + "otf"
        );
    }

    private FontProgram loadFontProgram(Resource resource, String displayName) {
        try (InputStream inputStream = resource.getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            return FontProgramFactory.createFont(bytes);
        } catch (com.itextpdf.io.exceptions.IOException ex) {
            logger.warn("Шрифт '{}' пропущен: {}", displayName, ex.getMessage());
            return null;
        } catch (IOException ex) {
            logger.warn("Не удалось прочитать шрифт '{}'.", displayName, ex);
            return null;
        }
    }

    private boolean registerFontProgram(FontSet fontSet, FontProgram fontProgram, String displayName) {
        try {
            PdfFontFactory.createFont(fontProgram, PdfEncodings.IDENTITY_H);
        } catch (PdfException | com.itextpdf.io.exceptions.IOException fontEx) {
            logger.warn("Шрифт '{}' пропущен: {}", displayName, fontEx.getMessage());
            return false;
        }

        String fontName = fontProgram.getFontNames().getFontName();
        if (!isValidName(fontName)) {
            logger.warn("Пропущен шрифт '{}': некорректное имя '{}'.", displayName, fontName);
            return false;
        }

        fontSet.addFont(fontProgram, PdfEncodings.IDENTITY_H, fontName);
        if (fontProgram.getFontNames().getFullName() != null) {
            for (String[] full : fontProgram.getFontNames().getFullName()) {
                if (full != null && full.length > 0 && isValidName(full[0])) {
                    fontSet.addFont(fontProgram, PdfEncodings.IDENTITY_H, full[0]);
                }
            }
        }
        return true;
    }

    private boolean isValidName(String name) {
        return name != null && name.chars().anyMatch(Character::isLetter);
    }

    private String safeName(Resource resource) {
        return Objects.requireNonNullElse(resource.getFilename(), resource.getDescription());
    }
}
