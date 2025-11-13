package com.figma.export.analysis;

import com.figma.export.exception.ConversionException;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfBoolean;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfNumber;
import com.itextpdf.kernel.pdf.PdfObject;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfResources;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Утилита для инспекции сгенерированных PDF-документов на базе iText 7.
 * Используется для внутренней диагностики и регрессионных проверок.
 */
@Component
public class PdfAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(PdfAnalysisService.class);
    private static final Pattern RGB_FILL_PATTERN = Pattern.compile("([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+rg", Pattern.MULTILINE);
    private static final Pattern RGB_STROKE_PATTERN = Pattern.compile("([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+RG", Pattern.MULTILINE);
    private static final Pattern CMYK_FILL_PATTERN = Pattern.compile("([0-9]*\\.?[0-9]+\\s+){4}k", Pattern.MULTILINE);
    private static final Pattern CMYK_STROKE_PATTERN = Pattern.compile("([0-9]*\\.?[0-9]+\\s+){4}K", Pattern.MULTILINE);

    public PdfAnalysisResult analyze(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("pdfBytes не должны быть пустыми");
        }
        try (PdfDocument pdfDocument = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdfBytes)))) {
            int pageCount = pdfDocument.getNumberOfPages();
            Set<String> fonts = new TreeSet<>();
            boolean hasDeviceCmykImages = false;
            boolean hasDeviceRgbImages = false;
            boolean hasDeviceCmykVectors = false;
            boolean hasDeviceRgbVectors = false;
            boolean hasTransparency = false;

            for (int pageIndex = 1; pageIndex <= pageCount; pageIndex++) {
                PdfPage page = pdfDocument.getPage(pageIndex);
                PdfResources resources = page.getResources();
                collectFonts(resources, fonts);
                ImageInspectionResult imageResult = inspectImages(resources);
                hasDeviceCmykImages = hasDeviceCmykImages || imageResult.hasDeviceCmyk;
                hasDeviceRgbImages = hasDeviceRgbImages || imageResult.hasDeviceRgb;
                hasTransparency = hasTransparency || imageResult.hasTransparency;

                VectorInspectionResult vectorResult = inspectVectors(page, resources);
                hasDeviceCmykVectors = hasDeviceCmykVectors || vectorResult.hasDeviceCmyk;
                hasDeviceRgbVectors = hasDeviceRgbVectors || vectorResult.hasDeviceRgb;

                PdfDictionary pageDict = page.getPdfObject();
                PdfDictionary pageGroup = pageDict.getAsDictionary(PdfName.Group);
                if (pageGroup != null && PdfName.Transparency.equals(pageGroup.getAsName(PdfName.S))) {
                    hasTransparency = true;
                }
            }

            return new PdfAnalysisResult(
                    pageCount,
                    List.copyOf(fonts),
                    hasDeviceCmykImages,
                    hasDeviceRgbImages,
                    hasDeviceCmykVectors,
                    hasDeviceRgbVectors,
                    hasTransparency
            );
        } catch (IOException ex) {
            logger.error("Не удалось прочитать PDF для анализа", ex);
            throw new ConversionException("Не удалось прочитать PDF для анализа", ex);
        }
    }

    private void collectFonts(PdfResources resources, Set<String> fonts) {
        if (resources == null) {
            return;
        }
        Set<PdfName> fontNames = resources.getResourceNames(PdfName.Font);
        if (fontNames != null) {
            for (PdfName fontName : fontNames) {
                PdfObject fontObject = resources.getResourceObject(PdfName.Font, fontName);
                if (!(fontObject instanceof PdfDictionary fontDict)) {
                    continue;
                }
                PdfName baseFont = fontDict.getAsName(PdfName.BaseFont);
                if (baseFont != null) {
                    fonts.add(baseFont.getValue());
                }
            }
        }

        Set<PdfName> xObjectNames = resources.getResourceNames(PdfName.XObject);
        if (xObjectNames != null) {
            for (PdfName xObjectName : xObjectNames) {
                PdfObject xObject = resources.getResourceObject(PdfName.XObject, xObjectName);
                if (xObject instanceof PdfStream stream && PdfName.Form.equals(stream.getAsName(PdfName.Subtype))) {
                    PdfDictionary formResourcesDict = stream.getAsDictionary(PdfName.Resources);
                    if (formResourcesDict != null) {
                        collectFonts(new PdfResources(formResourcesDict), fonts);
                    }
                }
            }
        }
    }

    private ImageInspectionResult inspectImages(PdfResources resources) {
        if (resources == null) {
            return ImageInspectionResult.EMPTY;
        }
        Set<PdfName> xObjectNames = resources.getResourceNames(PdfName.XObject);
        if (xObjectNames == null) {
            return ImageInspectionResult.EMPTY;
        }
        boolean hasCmyk = false;
        boolean hasRgb = false;
        boolean hasTransparency = false;

        for (PdfName xObjectName : xObjectNames) {
            PdfObject xObject = resources.getResourceObject(PdfName.XObject, xObjectName);
            if (!(xObject instanceof PdfStream stream)) {
                continue;
            }
            PdfName subType = stream.getAsName(PdfName.Subtype);
            if (PdfName.Image.equals(subType)) {
                PdfImageXObject image = new PdfImageXObject(stream);
                ColorSpaceType colorType = detectColorSpace(image.getPdfObject().get(PdfName.ColorSpace), resources);
                if (colorType == ColorSpaceType.CMYK) {
                    hasCmyk = true;
                } else if (colorType == ColorSpaceType.RGB) {
                    hasRgb = true;
                }
                if (hasImageTransparency(stream)) {
                    hasTransparency = true;
                }
            } else if (PdfName.Form.equals(subType)) {
                PdfDictionary formResourcesDict = stream.getAsDictionary(PdfName.Resources);
                if (formResourcesDict != null) {
                    PdfResources formResources = new PdfResources(formResourcesDict);
                    ImageInspectionResult nested = inspectImages(formResources);
                    hasCmyk = hasCmyk || nested.hasDeviceCmyk;
                    hasRgb = hasRgb || nested.hasDeviceRgb;
                    hasTransparency = hasTransparency || nested.hasTransparency;
                }
                PdfDictionary group = stream.getAsDictionary(PdfName.Group);
                if (group != null && PdfName.Transparency.equals(group.getAsName(PdfName.S))) {
                    hasTransparency = true;
                }
            }
        }
        return new ImageInspectionResult(hasCmyk, hasRgb, hasTransparency);
    }

    private VectorInspectionResult inspectVectors(PdfPage page, PdfResources resources) {
        if (page == null) {
            return VectorInspectionResult.EMPTY;
        }
        boolean hasCmyk = false;
        boolean hasRgb = false;
        Set<PdfStream> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int streamCount = page.getContentStreamCount();
        for (int i = 0; i < streamCount; i++) {
            PdfStream stream = page.getContentStream(i);
            VectorInspectionResult result = inspectVectorStream(stream, resources, visited);
            hasCmyk = hasCmyk || result.hasDeviceCmyk;
            hasRgb = hasRgb || result.hasDeviceRgb;
        }
        return new VectorInspectionResult(hasCmyk, hasRgb);
    }

    private VectorInspectionResult inspectVectorStream(PdfStream stream, PdfResources inheritedResources, Set<PdfStream> visited) {
        if (stream == null || !visited.add(stream)) {
            return VectorInspectionResult.EMPTY;
        }

        VectorInspectionResult result = analyzeVectorContent(stream.getBytes());
        boolean hasCmyk = result.hasDeviceCmyk;
        boolean hasRgb = result.hasDeviceRgb;

        PdfResources effectiveResources = inheritedResources;
        PdfDictionary localResourcesDict = stream.getAsDictionary(PdfName.Resources);
        if (localResourcesDict != null) {
            effectiveResources = new PdfResources(localResourcesDict);
        }

        if (effectiveResources != null) {
            Set<PdfName> xObjectNames = effectiveResources.getResourceNames(PdfName.XObject);
            if (xObjectNames != null) {
                for (PdfName name : xObjectNames) {
                    PdfObject xObject = effectiveResources.getResourceObject(PdfName.XObject, name);
                    if (xObject instanceof PdfStream xStream) {
                        PdfName subtype = xStream.getAsName(PdfName.Subtype);
                        if (PdfName.Form.equals(subtype)) {
                            PdfDictionary nestedResourcesDict = xStream.getAsDictionary(PdfName.Resources);
                            PdfResources nestedResources = nestedResourcesDict != null ? new PdfResources(nestedResourcesDict) : null;
                            VectorInspectionResult nested = inspectVectorStream(xStream, nestedResources, visited);
                            hasCmyk = hasCmyk || nested.hasDeviceCmyk;
                            hasRgb = hasRgb || nested.hasDeviceRgb;
                        }
                    }
                }
            }
        }

        return new VectorInspectionResult(hasCmyk, hasRgb);
    }

    private VectorInspectionResult analyzeVectorContent(byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            return VectorInspectionResult.EMPTY;
        }
        String content = new String(contentBytes, StandardCharsets.ISO_8859_1);
        boolean hasRgb = RGB_FILL_PATTERN.matcher(content).find() || RGB_STROKE_PATTERN.matcher(content).find();
        boolean hasCmyk = CMYK_FILL_PATTERN.matcher(content).find() || CMYK_STROKE_PATTERN.matcher(content).find();
        return new VectorInspectionResult(hasCmyk, hasRgb);
    }

    private boolean hasImageTransparency(PdfStream stream) {
        if (stream.containsKey(PdfName.SMask)) {
            PdfObject smask = stream.get(PdfName.SMask);
            if (!(smask instanceof PdfNumber)) {
                // SMask может быть потоком или словарём — это означает наличие прозрачности
                return true;
            }
            PdfNumber smaskNumber = (PdfNumber) smask;
            if (smaskNumber.intValue() == 1) {
                return true;
            }
        }
        PdfBoolean imageMask = stream.getAsBoolean(PdfName.ImageMask);
        if (imageMask != null && !imageMask.getValue()) {
            PdfNumber softMask = stream.getAsNumber(PdfName.SMaskInData);
            if (softMask != null && softMask.intValue() == 1) {
                return true;
            }
        }
        return false;
    }

    private ColorSpaceType detectColorSpace(PdfObject colorSpaceObject, PdfResources contextResources) {
        if (colorSpaceObject == null) {
            return ColorSpaceType.UNKNOWN;
        }

        PdfObject resolved = colorSpaceObject;
        if (colorSpaceObject instanceof PdfName name) {
            if (PdfName.DeviceCMYK.equals(name)) {
                return ColorSpaceType.CMYK;
            }
            if (PdfName.DeviceRGB.equals(name)) {
                return ColorSpaceType.RGB;
            }
            PdfObject defined = contextResources != null ? contextResources.getResourceObject(PdfName.ColorSpace, name) : null;
            if (defined != null) {
                resolved = defined;
            } else {
                return ColorSpaceType.UNKNOWN;
            }
        }

        if (resolved instanceof PdfArray array && array.size() > 0) {
            PdfObject first = array.get(0);
            if (first instanceof PdfName name) {
                if (PdfName.DeviceCMYK.equals(name)) {
                    return ColorSpaceType.CMYK;
                }
                if (PdfName.DeviceRGB.equals(name)) {
                    return ColorSpaceType.RGB;
                }
                if (PdfName.ICCBased.equals(name) && array.size() > 1) {
                    PdfObject profileObject = array.get(1);
                    PdfStream profileStream = null;
                    if (profileObject instanceof PdfStream stream) {
                        profileStream = stream;
                    } else if (profileObject != null && profileObject.isIndirectReference()) {
                        PdfObject dereferenced = profileObject.getIndirectReference().getRefersTo();
                        if (dereferenced instanceof PdfStream stream) {
                            profileStream = stream;
                        }
                    }
                    if (profileStream != null) {
                        PdfNumber components = profileStream.getAsNumber(PdfName.N);
                        if (components != null) {
                            int n = components.intValue();
                            if (n == 4) {
                                return ColorSpaceType.CMYK;
                            } else if (n == 3) {
                                return ColorSpaceType.RGB;
                            }
                        }
                    }
                }
            }
        }

        return ColorSpaceType.UNKNOWN;
    }

    private enum ColorSpaceType {
        CMYK,
        RGB,
        UNKNOWN
    }

    public record PdfAnalysisResult(int pageCount,
                                    List<String> fonts,
                                    boolean hasDeviceCmykImages,
                                    boolean hasDeviceRgbImages,
                                    boolean hasDeviceCmykVectors,
                                    boolean hasDeviceRgbVectors,
                                    boolean hasTransparency) {
    }

    private record ImageInspectionResult(boolean hasDeviceCmyk, boolean hasDeviceRgb, boolean hasTransparency) {
        private static final ImageInspectionResult EMPTY = new ImageInspectionResult(false, false, false);
    }

    private record VectorInspectionResult(boolean hasDeviceCmyk, boolean hasDeviceRgb) {
        private static final VectorInspectionResult EMPTY = new VectorInspectionResult(false, false);
    }
}
