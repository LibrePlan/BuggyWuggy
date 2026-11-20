/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.token;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.forbidden;
import static org.buggywuggy.common.ApiException.notFound;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.demo.DemoMode;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.security.BuggyWuggyUserDetailsService;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal API tokens. A token works with the rights of the user who made it, as long as that
 * user is active, until its last day or until it is removed; a read-only token only reads. Only
 * a SHA-256 hash is stored: the token is random (256 bits), so a slow password hash adds nothing,
 * and the lookup by hash stays a single index read.
 */
@Service
public class ApiTokenService {

    /** Every token starts with this, so that it is recognisable (e.g. by secret scanners). */
    public static final String PREFIX = "bw_";
    static final int MAX_PER_USER = 50;
    private static final int SHOWN = PREFIX.length() + 6;
    /** last_used_at is written at most this often per token. */
    private static final Duration USE_RESOLUTION = Duration.ofMinutes(5);

    public record ApiTokenResponse(long id, String name, String prefix, boolean readOnly, Instant createdAt,
            @ApiNullable LocalDate expiresOn, @ApiNullable Instant lastUsedAt) {
    }

    /** The new token, with the secret that is shown only this once. */
    public record CreatedApiTokenResponse(ApiTokenResponse token, String secret) {
    }

    public record CreateApiTokenRequest(@NotBlank @Size(max = 100) String name, boolean readOnly,
            @ApiNullable LocalDate expiresOn) {
    }

    /** A valid token: whose it is, and whether it may only read. */
    public record TokenUse(BuggyWuggyPrincipal principal, boolean readOnly) {
    }

    private static final RowMapper<ApiTokenResponse> ROW = (rs, n) -> new ApiTokenResponse(rs.getLong("id"),
            rs.getString("name"), rs.getString("prefix"), rs.getBoolean("read_only"),
            rs.getTimestamp("created_at").toInstant(), rs.getObject("expires_on", LocalDate.class),
            rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;
    private final BuggyWuggyUserDetailsService users;
    private final DemoMode demo;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(NamedParameterJdbcTemplate jdbc, BuggyWuggyUserDetailsService users, DemoMode demo,
            Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.demo = demo;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    @Transactional(readOnly = true)
    public List<ApiTokenResponse> list(long userId) {
        return jdbc.query("select * from api_token where user_id = :user order by created_at, id",
                new MapSqlParameterSource("user", userId), ROW);
    }

    @Transactional
    public CreatedApiTokenResponse create(BuggyWuggyPrincipal user, CreateApiTokenRequest request) {
        if (demo.isDemoUser(user)) {
            throw forbidden("token.demo");
        }
        if (request.expiresOn() != null && request.expiresOn().isBefore(today())) {
            throw badRequest("token.expiry-past");
        }
        Integer count = jdbc.queryForObject("select count(*) from api_token where user_id = :user",
                new MapSqlParameterSource("user", user.id()), Integer.class);
        if (count != null && count >= MAX_PER_USER) {
            throw badRequest("token.too-many", MAX_PER_USER);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Long id = jdbc.queryForObject("""
                insert into api_token (user_id, name, token_hash, prefix, read_only, created_at, expires_on)
                values (:user, :name, :hash, :prefix, :readOnly, :at, :expires)
                returning id""",
                new MapSqlParameterSource("user", user.id()).addValue("name", request.name().strip())
                        .addValue("hash", hash(secret)).addValue("prefix", secret.substring(0, SHOWN))
                        .addValue("readOnly", request.readOnly()).addValue("at", Timestamp.from(clock.instant()))
                        .addValue("expires", request.expiresOn()),
                Long.class);
        ApiTokenResponse token = jdbc.queryForObject("select * from api_token where id = :id",
                new MapSqlParameterSource("id", id), ROW);
        return new CreatedApiTokenResponse(token, secret);
    }

    /** Removes a token of {@code userId}. */
    @Transactional
    public void delete(long userId, long tokenId) {
        int removed = jdbc.update("delete from api_token where id = :id and user_id = :user",
                new MapSqlParameterSource("id", tokenId).addValue("user", userId));
        if (removed == 0) {
            throw notFound("token.not-found");
        }
    }

    /**
     * The user of a token, when it is valid today and its user is active and allowed to log in;
     * empty for anything else, without telling why.
     */
    @Transactional
    public Optional<TokenUse> authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 200) {
            return Optional.empty();
        }
        List<Object[]> rows = jdbc.query("""
                select t.id, t.user_id, t.read_only, t.last_used_at
                from api_token t join user_account u on u.id = t.user_id
                where t.token_hash = :hash and (t.expires_on is null or t.expires_on >= :today)
                  and u.active and not u.placeholder""",
                new MapSqlParameterSource("hash", hash(secret)).addValue("today", today()),
                (rs, n) -> new Object[] {rs.getLong("id"), rs.getLong("user_id"), rs.getBoolean("read_only"),
                        rs.getTimestamp("last_used_at")});
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        BuggyWuggyPrincipal principal = users.loadForToken((Long) row[1]);
        if (demo.isDemoUser(principal)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Timestamp lastUsed = (Timestamp) row[3];
        if (lastUsed == null || lastUsed.toInstant().plus(USE_RESOLUTION).isBefore(now)) {
            jdbc.update("update api_token set last_used_at = :at where id = :id",
                    new MapSqlParameterSource("at", Timestamp.from(now)).addValue("id", row[0]));
        }
        return Optional.of(new TokenUse(principal, (Boolean) row[2]));
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
