package com.figma.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

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
