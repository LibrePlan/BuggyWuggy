/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/** A QR code as SVG, made on the server so the secret never leaves BuggyWuggy. */
final class QrCode {

    private QrCode() {
    }

    static String svg(String text) {
        BitMatrix matrix;
        try {
            // Size 0: one unit per module, with the quiet zone of 4 modules the standard asks for.
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.MARGIN, 4,
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalArgumentException(e);
        }
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (matrix.get(x, y)) {
                    int run = 1;
                    while (x + run < width && matrix.get(x + run, y)) {
                        run++;
                    }
                    path.append('M').append(x).append(' ').append(y).append('h').append(run).append("v1h-").append(run).append('z');
                    x += run - 1;
                }
            }
        }
        return """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges">\
                <rect width="100%%" height="100%%" fill="#fff"/><path fill="#000" d="%s"/></svg>"""
                .formatted(width, height, path);
    }
}
