package com.figma.export.pdf;

import com.figma.export.exception.ConversionException;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

@Service
public class OpenPdfFallbackService {

    private static final Logger logger = LoggerFactory.getLogger(OpenPdfFallbackService.class);

    public byte[] rewritePdf(byte[] sourcePdf, String targetVersion) {
        char pdfVersion = resolveVersion(targetVersion);
        try (ByteArrayInputStream input = new ByteArrayInputStream(sourcePdf);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            PdfReader reader = new PdfReader(input);
            PdfStamper stamper = new PdfStamper(reader, output, pdfVersion);
            stamper.getWriter().setPdfVersion(pdfVersion);
            stamper.close();
            reader.close();

            byte[] rewritten = output.toByteArray();
            if (logger.isInfoEnabled()) {
                logger.info("OpenPDF fallback завершён: targetVersion={}, size={} bytes",
                        String.format(Locale.ROOT, "%.1f", parseVersionFloat(targetVersion)), rewritten.length);
            }
            return rewritten;
        } catch (IOException | com.lowagie.text.DocumentException ex) {
            throw new ConversionException("Не удалось пересобрать PDF через OpenPDF.", ex);
        }
    }

    private char resolveVersion(String version) {
        if (version == null) {
            return PdfWriter.VERSION_1_6;
        }
        switch (version) {
            case "1.3":
                return PdfWriter.VERSION_1_3;
            case "1.4":
                return PdfWriter.VERSION_1_4;
            case "1.5":
                return PdfWriter.VERSION_1_5;
            case "1.7":
                return PdfWriter.VERSION_1_7;
            case "1.6":
            default:
                return PdfWriter.VERSION_1_6;
        }
    }

    private float parseVersionFloat(String version) {
        if (version == null) {
            return 1.6f;
        }
        try {
            return Float.parseFloat(version);
        } catch (NumberFormatException ex) {
            return 1.6f;
        }
    }
}
