package com.figma.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

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
                        .param("dpi", "150")
                        .param("pdfVersion", "1.4")
                        .param("pdfStandard", "none")
                        .param("pdfColorProfile", "coated_fogra39")
                        .param("tiffCompression", "none")
                        .param("tiffAntialias", "none")
                        .param("tiffDpi", "300"))
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
                        .param("dpi", "96")
                        .param("pdfVersion", "1.4")
                        .param("pdfStandard", "none")
                        .param("pdfColorProfile", "coated_fogra39")
                        .param("tiffCompression", "none")
                        .param("tiffAntialias", "none")
                        .param("tiffDpi", "300"))
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
                        .param("dpi", "300")
                        .param("pdfVersion", "1.6")
                        .param("pdfStandard", "PDF/X-4:2008")
                        .param("pdfColorProfile", "iso_coated_v2")
                        .param("tiffCompression", "none")
                        .param("tiffAntialias", "balanced")
                        .param("tiffDpi", "300"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(response).isNotEmpty();
    }
}
