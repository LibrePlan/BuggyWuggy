/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import static org.buggywuggy.common.ApiException.forbidden;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.buggywuggy.common.SqlLike;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserDtos.GroupRef;
import org.buggywuggy.user.UserDtos.UserRef;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finds active users by name, e.g. for search filters, or looks up given {@code ids} (also
 * inactive users, to show the names in a stored filter); and finds groups by name, e.g. for
 * project roles. Requires the global BROWSE_USERS permission.
 */
@RestController
public class UserPickerController {

    private final NamedParameterJdbcTemplate jdbc;

    public UserPickerController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/users/picker")
    public List<UserRef> picker(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        if (!user.hasAuthority(Authorities.BROWSE_USERS)) {
            throw forbidden("user.browse-permission");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        if (ids != null && !ids.isEmpty()) {
            params.put("ids", ids);
            return jdbc.query("select id, username, display_name from user_account where id in (:ids)"
                            + " order by lower(display_name), id limit :limit",
                    params, (rs, n) -> new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3)));
        }
        String filter = "";
        if (query != null && !query.isBlank()) {
            filter = """
                     and (lower(username) like :pattern escape '\\'
                          or lower(display_name) like :pattern escape '\\'
                          or lower(email) like :pattern escape '\\')""";
            params.put("pattern", SqlLike.containsPattern(query));
        }
        return jdbc.query("select id, username, display_name from user_account where active and not placeholder "
                        + filter + " order by lower(display_name), id limit :limit",
                params, (rs, n) -> new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    @GetMapping("/api/v1/groups/picker")
    public List<GroupRef> groupPicker(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        if (!user.hasAuthority(Authorities.BROWSE_USERS)) {
            throw forbidden("user.browse-permission");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        String filter = "";
        if (query != null && !query.isBlank()) {
            filter = " where lower(name) like :pattern escape '\\'";
            params.put("pattern", SqlLike.containsPattern(query));
        }
        return jdbc.query("select id, name from user_group" + filter + " order by lower(name), id limit :limit",
                params, (rs, n) -> new GroupRef(rs.getLong(1), rs.getString(2)));
    }
}
