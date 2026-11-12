/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.attachment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.buggywuggy.attachment.AttachmentDtos.AttachmentResponse;
import org.buggywuggy.attachment.AttachmentDtos.AttachmentSettingsResponse;
import org.buggywuggy.attachment.AttachmentService.Download;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AttachmentController {

    private final AttachmentService attachments;
    private final AttachmentSettingsResponse settings;

    public AttachmentController(AttachmentService attachments,
            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size}") DataSize maxRequestSize) {
        this.attachments = attachments;
        this.settings = new AttachmentSettingsResponse(maxFileSize.toBytes(), maxRequestSize.toBytes());
    }

    @GetMapping("/api/v1/attachments/settings")
    public AttachmentSettingsResponse settings() {
        return settings;
    }

    @GetMapping("/api/v1/issues/{idOrKey}/attachments")
    public List<AttachmentResponse> list(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return attachments.list(user, idOrKey);
    }

    /** Uploads one or more files (multipart form field {@code file}, repeated for several files). */
    @PostMapping(path = "/api/v1/issues/{idOrKey}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<AttachmentResponse> upload(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @RequestPart("file") List<MultipartFile> files) {
        return attachments.upload(user, idOrKey, files);
    }

    @DeleteMapping("/api/v1/issues/{idOrKey}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long attachmentId) {
        attachments.delete(user, idOrKey, attachmentId);
    }

    /**
     * The file. Previewable images are shown inline; all other files are downloads. Uploaded
     * content is never interpreted by the browser as part of BuggyWuggy: no MIME sniffing, and a
     * sandbox policy that blocks scripts even if a file is opened directly.
     */
    @GetMapping("/api/v1/attachments/{attachmentId}/content")
    public ResponseEntity<Resource> content(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable long attachmentId) {
        Download download = attachments.download(user, attachmentId);
        AttachmentResponse attachment = download.attachment();
        ContentDisposition disposition = (attachment.previewable()
                ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(attachment.fileName(), StandardCharsets.UTF_8).build();
        MediaType type = attachment.previewable()
                ? MediaType.parseMediaType(attachment.mediaType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(attachment.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .body(download.content());
    }
}
