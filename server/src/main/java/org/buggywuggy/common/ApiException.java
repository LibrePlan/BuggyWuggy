/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import org.springframework.http.HttpStatus;

/**
 * An error that is reported to the API client as an RFC 9457 problem detail.
 * The message is a key in the {@code i18n/messages} bundle and is translated per request.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String messageKey;
    private final transient Object[] args;

    public ApiException(HttpStatus status, String messageKey, Object... args) {
        super(messageKey);
        this.status = status;
        this.messageKey = messageKey;
        this.args = args;
    }

    public static ApiException badRequest(String messageKey, Object... args) {
        return new ApiException(HttpStatus.BAD_REQUEST, messageKey, args);
    }

    public static ApiException forbidden(String messageKey, Object... args) {
        return new ApiException(HttpStatus.FORBIDDEN, messageKey, args);
    }

    public static ApiException notFound(String messageKey, Object... args) {
        return new ApiException(HttpStatus.NOT_FOUND, messageKey, args);
    }

    public static ApiException conflict(String messageKey, Object... args) {
        return new ApiException(HttpStatus.CONFLICT, messageKey, args);
    }

    public HttpStatus status() {
        return status;
    }

    public String messageKey() {
        return messageKey;
    }

    public Object[] args() {
        return args;
    }
}
