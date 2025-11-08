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

@RestController
@RequestMapping("/convert")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> convert(
            @RequestPart("image") MultipartFile file,
            @Valid @ModelAttribute ExportRequest request
    ) {
        ExportResponse response = exportService.convert(file, request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(response.contentType()));
        headers.setContentDisposition(response.contentDisposition());
        return ResponseEntity
                .ok()
                .headers(headers)
                .body(response.payload());
    }
}
