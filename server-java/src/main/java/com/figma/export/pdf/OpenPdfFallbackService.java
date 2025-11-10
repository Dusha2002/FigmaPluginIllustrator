package com.figma.export.pdf;

import com.figma.export.exception.ConversionException;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfImportedPage;
import com.lowagie.text.pdf.PdfReader;
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
            Document document = new Document(reader.getPageSizeWithRotation(1));
            PdfCopy copy = new PdfCopy(document, output);
            copy.setPdfVersion(pdfVersion);
            document.open();

            int pages = reader.getNumberOfPages();
            for (int i = 1; i <= pages; i++) {
                PdfImportedPage page = copy.getImportedPage(reader, i);
                copy.addPage(page);
            }

            copy.freeReader(reader);
            reader.close();
            document.close();

            byte[] rewritten = output.toByteArray();
            if (logger.isInfoEnabled()) {
                String header = extractHeader(rewritten);
                logger.info("OpenPDF fallback завершён: targetVersion={}, size={} bytes, header={}",
                        String.format(Locale.ROOT, "%.1f", parseVersionFloat(targetVersion)), rewritten.length, header);
            }
            return rewritten;
        } catch (IOException | DocumentException ex) {
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

    private String extractHeader(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length < 8) {
            return "<short>";
        }
        return new String(pdfBytes, 0, 8);
    }
}
