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
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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
    public NonSubsettingFontProvider createFontProvider() {
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
        NonSubsettingFontProvider provider = new NonSubsettingFontProvider(fontSet, fallbackFamily);

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
            byte[] bytes = FontEmbeddingUtil.ensureEmbeddable(inputStream.readAllBytes(), displayName, logger);
            FontProgram fontProgram = FontProgramFactory.createFont(bytes);
            return fontProgram;
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
            PdfFont pdfFont = PdfFontFactory.createFont(fontProgram, PdfEncodings.IDENTITY_H);
            pdfFont.setSubset(false);
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

        Set<String> aliases = collectFontAliases(fontProgram);
        for (String alias : aliases) {
            fontSet.addFont(fontProgram, PdfEncodings.IDENTITY_H, alias);
        }
        if (logger.isDebugEnabled()) {
            if (!aliases.isEmpty()) {
                logger.debug("Font '{}' registered with aliases: {}", displayName, aliases);
            } else {
                logger.debug("Font '{}' registered without additional aliases", displayName);
            }
        }
        return true;
    }

    private boolean isValidName(String name) {
        return name != null && name.chars().anyMatch(Character::isLetter);
    }

    private Set<String> collectFontAliases(FontProgram fontProgram) {
        Set<String> aliases = new LinkedHashSet<>();
        String fontName = fontProgram.getFontNames().getFontName();
        addSimpleVariants(aliases, fontName);
        String[][] fullNames = fontProgram.getFontNames().getFullName();
        if (fullNames != null) {
            for (String[] full : fullNames) {
                if (full != null && full.length > 0 && isValidName(full[0])) {
                    aliases.add(full[0]);
                    addSimpleVariants(aliases, full[0]);
                }
            }
        }
        aliases.remove(fontName);
        return aliases;
    }

    private void addSimpleVariants(Set<String> aliases, String name) {
        if (name == null) {
            return;
        }
        String trimmed = name.trim();
        if (!isValidName(trimmed)) {
            return;
        }
        aliases.add(trimmed);
        aliases.add(trimmed.toLowerCase(Locale.ROOT));
        aliases.add(trimmed.toUpperCase(Locale.ROOT));

        String baseName = removeStyleSuffix(trimmed);
        if (isValidName(baseName)) {
            aliases.add(baseName);
            aliases.add(baseName.toLowerCase(Locale.ROOT));
            aliases.add(baseName.toUpperCase(Locale.ROOT));
        }

        int hyphenIndex = trimmed.indexOf('-');
        if (hyphenIndex > 0) {
            String prefix = trimmed.substring(0, hyphenIndex).trim();
            if (isValidName(prefix)) {
                aliases.add(prefix);
                aliases.add(prefix.toLowerCase(Locale.ROOT));
                aliases.add(prefix.toUpperCase(Locale.ROOT));
            }
        }

        int spaceIndex = trimmed.indexOf(' ');
        if (spaceIndex > 0) {
            String prefix = trimmed.substring(0, spaceIndex).trim();
            if (isValidName(prefix)) {
                aliases.add(prefix);
                aliases.add(prefix.toLowerCase(Locale.ROOT));
                aliases.add(prefix.toUpperCase(Locale.ROOT));
            }
        }
    }

    private String removeStyleSuffix(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace > 0) {
            String suffix = trimmed.substring(lastSpace + 1);
            if (isStyleDescriptor(suffix)) {
                return trimmed.substring(0, lastSpace);
            }
        }
        int lastHyphen = trimmed.lastIndexOf('-');
        if (lastHyphen > 0) {
            String suffix = trimmed.substring(lastHyphen + 1);
            if (isStyleDescriptor(suffix)) {
                return trimmed.substring(0, lastHyphen);
            }
        }
        return trimmed;
    }

    private boolean isStyleDescriptor(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("(regular|italic|oblique|bold|semibold|medium|light|thin|extrathin|extralight|black|heavy|book|roman|condensed|expanded)");
    }

    private String safeName(Resource resource) {
        return Objects.requireNonNullElse(resource.getFilename(), resource.getDescription());
    }
}
