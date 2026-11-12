/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import java.util.List;
import org.buggywuggy.importer.ImportDtos.ConnectionResponse;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;

/**
 * One kind of tracker the wizard can import from: connects to a server and opens one of its
 * projects as a {@link Source}. Implementations only read, through {@link ReadOnlyHttp}.
 */
public interface Connector {

    /** {@code import_source.kind}, e.g. GITHUB. */
    String kind();

    /** The kind in URLs of the API, e.g. github. */
    default String slug() {
        return kind().toLowerCase(java.util.Locale.ROOT);
    }

    /** The server as stored with the source, e.g. https://github.com. */
    String baseUrl(String url);

    ConnectionResponse test(String url, String token);

    /** Repositories matching {@code query} (owner/name, a search, or empty for the token's own). */
    List<RepositoryInfo> repositories(String url, String token, String query);

    /** Opens a repository for import; fails when its issues cannot be imported. */
    Source open(String url, String token, String repository);
}
