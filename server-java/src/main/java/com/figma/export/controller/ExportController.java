package com.figma.export.controller;

import com.figma.export.model.ExportRequest;
import com.figma.export.model.ExportResponse;
import com.figma.export.service.ExportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/convert")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> convert(
            @RequestPart(value = "image", required = false) MultipartFile file,
            @RequestPart(value = "images", required = false) List<MultipartFile> files,
            @Valid @ModelAttribute ExportRequest request,
            jakarta.servlet.http.HttpServletRequest servletRequest
    ) {
        // Обработка параметров widthPx_N и heightPx_N для множественных файлов
        if (files != null && !files.isEmpty()) {
            for (int i = 0; i < files.size(); i++) {
                String widthParam = servletRequest.getParameter("widthPx_" + i);
                String heightParam = servletRequest.getParameter("heightPx_" + i);
                
                if (widthParam != null && !widthParam.isEmpty()) {
                    try {
                        request.setWidthPx(i, Integer.parseInt(widthParam));
                    } catch (NumberFormatException e) {
                        // Игнорируем некорректные значения
                    }
                }
                
                if (heightParam != null && !heightParam.isEmpty()) {
                    try {
                        request.setHeightPx(i, Integer.parseInt(heightParam));
                    } catch (NumberFormatException e) {
                        // Игнорируем некорректные значения
                    }
                }
            }
        }
        
        ExportResponse response;
        if (files != null && !files.isEmpty()) {
            // Множественные файлы для объединения в один PDF
            response = exportService.convertMultiple(files, request);
        } else if (file != null) {
            // Одиночный файл
            response = exportService.convert(file, request);
        } else {
            throw new IllegalArgumentException("Не предоставлен ни один файл для конвертации");
        }
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(response.contentType()));
        headers.setContentDisposition(response.contentDisposition());
        return ResponseEntity
                .ok()
                .headers(headers)
                .body(response.payload());
    }
}
