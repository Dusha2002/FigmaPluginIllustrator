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
                        .param("ppi", "150"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(response).isNotEmpty();
    }

    @Test
    @DisplayName("POST /convert (SVG→PDF, версия 1.3) включает fallback OpenPDF")
    void convertSvgToPdfWithPdfVersion13TriggersFallback() throws Exception {
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
                        .param("name", "fallback_test")
                        .param("ppi", "150")
                        .param("pdfVersion", "1.3"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(response).isNotEmpty();
        // Заголовок начинается с %PDF-1.3
        String header = new String(response, 0, Math.min(response.length, 8));
        assertThat(header).contains("%PDF-1.3");
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
