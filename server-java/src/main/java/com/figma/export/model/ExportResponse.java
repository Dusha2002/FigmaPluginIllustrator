package com.figma.export.model;

import org.springframework.http.ContentDisposition;

public record ExportResponse(
        byte[] payload,
        String contentType,
        ContentDisposition contentDisposition
) {
}
