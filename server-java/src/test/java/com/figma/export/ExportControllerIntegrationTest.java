package com.figma.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExportControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /convert (PDF) возвращает PDF")
    void convertPdfReturnsPdf() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "test.pdf",
                "application/pdf",
                createSamplePdf("Test export")
        );

        byte[] response = mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "pdf")
                        .param("name", "test_export")
                        .param("ppi", "150"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(response).isNotEmpty();
    }

    @Test
    @DisplayName("SVG экспортируется в PDF как вектор")
    void svgExportKeepsVectorContent() throws Exception {
        byte[] svgBytes = ("<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'>" +
                "<rect x='10' y='10' width='180' height='180' fill='rgb(255,0,0)'/>" +
                "<text x='100' y='110' font-size='24' text-anchor='middle'>Vector</text>" +
                "</svg>").getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "vector.svg",
                "image/svg+xml",
                svgBytes
        );

        byte[] response = mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "pdf")
                        .param("name", "vector_export")
                        .param("ppi", "150"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        try (PDDocument document = Loader.loadPDF(response)) {
            PDPage page = document.getPage(0);
            boolean vectorCommandsFound = false;
            try (var contentStream = page.getContents()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                contentStream.transferTo(buffer);
                String content = buffer.toString(StandardCharsets.ISO_8859_1);
                vectorCommandsFound = content.contains("re") && content.contains("S");
            }

            var resources = page.getResources();
            var xObjectNames = resources.getXObjectNames();
            assertThat(xObjectNames).isNotEmpty();
            for (COSName name : xObjectNames) {
                var xObject = resources.getXObject(name);
                if (xObject instanceof PDFormXObject form) {
                    try (var formStream = form.getContentStream().createInputStream()) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        formStream.transferTo(buffer);
                        String formContent = buffer.toString(StandardCharsets.ISO_8859_1);
                        if (formContent.contains(" re") || formContent.contains(" m") || formContent.contains(" l") || formContent.contains(" S")) {
                            vectorCommandsFound = true;
                        }
                    }
                }
            }

            assertThat(vectorCommandsFound)
                    .withFailMessage("Векторные операторы re/S не найдены ни на странице, ни в Form XObject")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("POST /convert с неподдерживаемым форматом возвращает 400")
    void convertWithUnsupportedFormatReturnsBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "sample.svg",
                "image/svg+xml",
                "<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes()
        );

        mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "gif")
                        .param("name", "bad")
                        .param("ppi", "96"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("формат")));
    }

    @Test
    @DisplayName("POST /convert (PDF, ISO Coated v2)")
    void convertPdfWithAlternativeProfile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "test.pdf",
                "application/pdf",
                createSamplePdf("ISO profile")
        );

        byte[] response = mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "pdf")
                        .param("name", "iso_profile")
                        .param("ppi", "300"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(response).isNotEmpty();
    }

    @Test
    @DisplayName("PNG конвертируется в PDF с CMYK ColorSpace")
    void pngConvertedToPdfUsesCmykImage() throws Exception {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                image.setRGB(x, y, new Color(0, 128, 255, 255).getRGB());
            }
        }
        ByteArrayOutputStream pngOutput = new ByteArrayOutputStream();
        ImageIO.write(image, "png", pngOutput);

        MockMultipartFile file = new MockMultipartFile(
                "image",
                "sample.png",
                "image/png",
                pngOutput.toByteArray()
        );

        byte[] response = mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "pdf")
                        .param("name", "cmyk_image")
                        .param("ppi", "300"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        try (PDDocument document = Loader.loadPDF(response)) {
            PDPage page = document.getPage(0);
            var resources = page.getResources();
            boolean foundCmykImage = false;
            for (COSName name : resources.getXObjectNames()) {
                var xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject imageObject) {
                    PDColorSpace colorSpace = imageObject.getColorSpace();
                    assertThat(colorSpace.getName()).contains("DeviceCMYK");
                    foundCmykImage = true;
                }
            }
            assertThat(foundCmykImage).isTrue();
        }
    }

    private byte[] createSamplePdf(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(font, 14);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(text);
                contentStream.endText();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }
}
