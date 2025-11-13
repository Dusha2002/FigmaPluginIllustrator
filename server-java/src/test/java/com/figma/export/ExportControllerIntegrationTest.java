package com.figma.export;

import com.figma.export.analysis.PdfAnalysisService;
import com.figma.export.analysis.PdfAnalysisService.PdfAnalysisResult;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

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

    @Autowired
    private PdfAnalysisService pdfAnalysisService;

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

        PdfAnalysisResult analysis = pdfAnalysisService.analyze(response);
        assertThat(analysis.fonts())
                .withFailMessage("Векторный PDF должен содержать встроенный шрифт")
                .isNotEmpty();
        assertThat(analysis.hasDeviceCmykImages()).isFalse();
        assertThat(analysis.hasDeviceRgbImages()).isFalse();
        assertThat(analysis.hasDeviceCmykVectors())
                .withFailMessage("Векторный PDF должен использовать CMYK-команды для заливок/обводок")
                .isTrue();
        assertThat(analysis.hasDeviceRgbVectors())
                .withFailMessage("Векторный PDF не должен содержать DeviceRGB операторов")
                .isFalse();
    }

    @Test
    @DisplayName("SVG с режимом outline конвертирует текст в кривые")
    void svgExportWithOutlineTextModeConvertsTextToPaths() throws Exception {
        byte[] svgBytes = ("<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'>" +
                "<rect x='10' y='10' width='180' height='180' fill='rgb(255,0,0)'/>" +
                "<text x='100' y='110' font-size='24' text-anchor='middle'>Vector</text>" +
                "</svg>").getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "vector_outline.svg",
                "image/svg+xml",
                svgBytes
        );

        byte[] response = mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "pdf")
                        .param("name", "vector_outline")
                        .param("ppi", "150")
                        .param("svgTextMode", "outline"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        PdfAnalysisResult analysis = pdfAnalysisService.analyze(response);
        assertThat(analysis.fonts())
                .withFailMessage("PDF с режимом outline не должен содержать встроенных шрифтов")
                .isEmpty();
        assertThat(analysis.hasDeviceCmykImages()).isFalse();
        assertThat(analysis.hasDeviceRgbImages()).isFalse();
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

        PdfAnalysisResult analysis = pdfAnalysisService.analyze(response);
        assertThat(analysis.hasDeviceCmykImages()).isTrue();
        assertThat(analysis.hasDeviceRgbVectors()).isFalse();
        assertThat(analysis.hasDeviceCmykVectors()).isFalse();
    }

    @Test
    @DisplayName("SVG с солидными заливками конвертируется без DeviceRGB операторов")
    void svgExportDoesNotContainDeviceRgbOperators() throws Exception {
        byte[] svgBytes = ("<svg xmlns='http://www.w3.org/2000/svg' width='120' height='120'>" +
                "<rect x='0' y='0' width='120' height='120' fill='rgb(0,128,255)'/>" +
                "<circle cx='60' cy='60' r='30' stroke='rgb(255,128,0)' stroke-width='10' fill='none'/>" +
                "</svg>").getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "vector_cmyk.svg",
                "image/svg+xml",
                svgBytes
        );

        byte[] response = mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "pdf")
                        .param("name", "vector_cmyk")
                        .param("ppi", "150"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        PdfAnalysisResult analysis = pdfAnalysisService.analyze(response);
        assertThat(analysis.hasDeviceRgbVectors())
                .withFailMessage("Ожидалось отсутствие DeviceRGB операторов в векторном контенте")
                .isFalse();
        assertThat(analysis.hasDeviceCmykVectors())
                .withFailMessage("Ожидалось наличие CMYK операторов в векторном контенте")
                .isTrue();
        assertThat(analysis.hasDeviceRgbImages()).isFalse();
    }

    private byte[] createSamplePdf(String text) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PdfDocument pdfDocument = new PdfDocument(new PdfWriter(output));
             Document document = new Document(pdfDocument)) {
            document.add(new Paragraph(text));
        }
        return output.toByteArray();
    }
}
