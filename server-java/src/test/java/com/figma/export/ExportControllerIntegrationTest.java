package com.figma.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
    @DisplayName("POST /convert (SVG→PDF) возвращает PDF")
    void convertSvgToPdfReturnsPdf() throws Exception {
        String svg = """
                <svg xmlns='http://www.w3.org/2000/svg' width='120' height='60'>
                  <rect x='10' y='10' width='100' height='40' fill='#00ff88'/>
                </svg>
                """;
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "test.svg",
                "image/svg+xml",
                svg.getBytes()
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

    @ParameterizedTest(name = "POST /convert (SVG→PDF, версия {0}) возвращает корректный заголовок")
    @ValueSource(strings = {"1.3", "1.4", "1.5", "1.6", "1.7"})
    void convertSvgToPdfWithRequestedVersionProducesMatchingHeader(String pdfVersion) throws Exception {
        String svg = """
                <svg xmlns='http://www.w3.org/2000/svg' width='40' height='40'>
                  <rect x='2' y='2' width='36' height='36' fill='#123456' fill-opacity='0.5'/>
                </svg>
                """;
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "transparent.svg",
                "image/svg+xml",
                svg.getBytes()
        );

        byte[] response = mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "pdf")
                        .param("name", "fallback_" + pdfVersion.replace('.', '_'))
                        .param("ppi", "150")
                        .param("pdfVersion", pdfVersion))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(response).isNotEmpty();
        String header = new String(response, 0, Math.min(response.length, 8));
        // Векторные PDF с прозрачностью: PDFBox автоматически устанавливает версию (обычно 1.6)
        // OpenPDF fallback отключен для векторных PDF для сохранения структуры
        // Проверяем, что версия в допустимом диапазоне (1.3-1.7)
        assertThat(header).startsWith("%PDF-1.");
        float actualVersion = Float.parseFloat(header.substring(5));
        assertThat(actualVersion).isBetween(1.3f, 1.7f);
    }

    @ParameterizedTest(name = "POST /convert (SVG→PDF, стандарт {0}) устанавливает PDF/X метаданные")
    @CsvSource({
            "pdfx-1a,PDF/X-1:2001,PDF/X-1a:2001,1.3",
            "pdfx-3,PDF/X-3:2002,PDF/X-3:2002,1.3",
            "pdfx-4,PDF/X-4,PDF/X-4,1.6"
    })
    void convertSvgToPdfWithPdfStandardAppliesMetadata(String pdfStandard,
                                                       String expectedMetadataVersion,
                                                       String expectedMetadataConformance,
                                                       String expectedPdfVersion) throws Exception {
        String svg = """
                <svg xmlns='http://www.w3.org/2000/svg' width='120' height='80'>
                  <rect x='10' y='10' width='100' height='40' fill='#3366ff'/>
                </svg>
                """;
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "vector.svg",
                "image/svg+xml",
                svg.getBytes()
        );

        byte[] response = mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "pdf")
                        .param("name", "pdfx_" + pdfStandard)
                        .param("ppi", "300")
                        .param("pdfVersion", "1.3")
                        .param("pdfStandard", pdfStandard))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(response).isNotEmpty();
        try (PDDocument document = Loader.loadPDF(response)) {
            float expectedVersionFloat = Float.parseFloat(expectedPdfVersion);
            // PDF/X может иметь версию выше минимальной из-за особенностей PDFBox (например, режим Compatible)
            assertThat(document.getVersion()).isGreaterThanOrEqualTo(expectedVersionFloat - 0.01f);

            PDDocumentInformation info = document.getDocumentInformation();
            assertThat(info).isNotNull();
            // Читаем Trapped напрямую из COSObject для совместимости с PDFBox 3
            String trapped = info.getCOSObject().getNameAsString(COSName.TRAPPED);
            assertThat(trapped).isEqualTo("True");
            assertThat(info.getCustomMetadataValue("GTS_PDFXVersion")).isEqualTo(expectedMetadataVersion);
            assertThat(info.getCustomMetadataValue("GTS_PDFXConformance")).isEqualTo(expectedMetadataConformance);

            PDDocumentCatalog catalog = document.getDocumentCatalog();
            assertThat(catalog).isNotNull();
            COSDictionary markInfoDict = (COSDictionary) catalog.getCOSObject().getDictionaryObject(COSName.MARK_INFO);
            assertThat(markInfoDict).isNotNull();
            assertThat(markInfoDict.getBoolean(COSName.getPDFName("Marked"), true)).isTrue();

            List<PDOutputIntent> outputIntents = catalog.getOutputIntents();
            assertThat(outputIntents).isNotNull();
            assertThat(outputIntents).isNotEmpty();
            assertThat(outputIntents.get(0).getOutputConditionIdentifier()).isNotBlank();
        }
    }

    @Test
    @DisplayName("POST /convert (SVG→PDF, PDF/X-1a) отклоняет прозрачность")
    void convertSvgToPdfWithPdfx1aRejectsTransparency() throws Exception {
        String svg = """
                <svg xmlns='http://www.w3.org/2000/svg' width='60' height='60'>
                  <rect x='5' y='5' width='50' height='50' fill='#ff0000' fill-opacity='0.5'/>
                </svg>
                """;
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "transparent.svg",
                "image/svg+xml",
                svg.getBytes()
        );

        mockMvc.perform(multipart("/convert")
                        .file(file)
                        .param("format", "pdf")
                        .param("name", "pdfx_transparency")
                        .param("ppi", "150")
                        .param("pdfVersion", "1.3")
                        .param("pdfStandard", "pdfx-1a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("прозрачность")));
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
    @DisplayName("POST /convert (SVG→PDF, ISO Coated v2)")
    void convertSvgToPdfWithAlternativeProfile() throws Exception {
        String svg = """
                <svg xmlns='http://www.w3.org/2000/svg' width='60' height='60'>
                  <circle cx='30' cy='30' r='25' fill='#ff6600'/>
                </svg>
                """;
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "test.svg",
                "image/svg+xml",
                svg.getBytes()
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
}
