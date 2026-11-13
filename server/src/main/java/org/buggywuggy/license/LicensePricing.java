/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static org.buggywuggy.common.ApiException.badRequest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import org.buggywuggy.common.ApiNullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The price of a license per country: {@link #PRICE_PER_USER_EUR} in the Netherlands, and
 * elsewhere in proportion to the price of a Big Mac (The Economist's Big Mac index, bundled in
 * resources/license): price = 7 € × Big Mac in the country (local currency) / Big Mac in the
 * Netherlands (€), in the local currency. Euro countries without a price of their own use the
 * euro area's. Without a price (or without the index) the factor is 1: 7 € per user.
 */
@Component
public class LicensePricing {

    /** The price per user per month in the Netherlands, in euros. */
    public static final BigDecimal PRICE_PER_USER_EUR = new BigDecimal("7.00");

    static final String REFERENCE = "NLD";
    static final String EURO_AREA = "EUZ";

    private static final Logger log = LoggerFactory.getLogger(LicensePricing.class);

    public record Country(String code, String name, String dialCode) {
    }

    /** The price of a Big Mac in one country, in its own currency. */
    record BigMac(String currency, BigDecimal localPrice, LocalDate date) {
    }

    /**
     * A price for {@code users} users: {@code pricePerUserMonth} and {@code yearlyTotal} in
     * {@code currency}. {@code bigMacPrice} / {@code referenceBigMacPrice}: the Big Mac prices
     * the price is based on (null when the factor is 1).
     */
    public record Quote(String countryCode, String countryName, String currency, String currencyName,
            BigDecimal pricePerUserMonth, int users, BigDecimal yearlyTotal, BigDecimal basePricePerUserEur,
            @ApiNullable BigDecimal bigMacPrice, @ApiNullable BigDecimal referenceBigMacPrice,
            @ApiNullable LocalDate indexDate) {
    }

    private final List<Country> countries;
    private final Map<String, Country> byCode = new HashMap<>();
    private final Map<String, BigMac> index;

    @Autowired
    public LicensePricing(JsonMapper json) {
        this(json, "/license/big-mac-source-data-v2.csv");
    }

    LicensePricing(JsonMapper json, String indexResource) {
        this.countries = loadCountries(json);
        countries.forEach(c -> byCode.put(c.code(), c));
        this.index = loadIndex(indexResource);
    }

    public List<Country> countries() {
        return countries;
    }

    public Optional<Country> country(String code) {
        return Optional.ofNullable(code == null ? null : byCode.get(code.strip().toUpperCase(Locale.ROOT)));
    }

    public Quote quote(String countryCode, int users) {
        Country country = country(countryCode).orElseThrow(() -> badRequest("license.country-invalid", countryCode));
        BigMac reference = index.get(REFERENCE);
        BigMac local = bigMacOf(country.code());
        if (reference == null || local == null) {
            return quote(country, "EUR", PRICE_PER_USER_EUR, users, null, null, null);
        }
        BigDecimal price = PRICE_PER_USER_EUR.multiply(local.localPrice())
                .divide(reference.localPrice(), 10, RoundingMode.HALF_UP);
        return quote(country, local.currency(), price, users, local.localPrice(), reference.localPrice(), local.date());
    }

    private BigMac bigMacOf(String code) {
        String iso3;
        try {
            iso3 = Locale.of("", code).getISO3Country();
        } catch (MissingResourceException e) {
            return null;
        }
        BigMac own = index.get(iso3);
        if (own != null) {
            return own;
        }
        return "EUR".equals(currencyOf(code)) ? index.get(EURO_AREA) : null;
    }

    private static String currencyOf(String code) {
        try {
            Currency currency = Currency.getInstance(Locale.of("", code));
            return currency == null ? null : currency.getCurrencyCode();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Quote quote(Country country, String currencyCode, BigDecimal price, int users,
            BigDecimal bigMac, BigDecimal reference, LocalDate date) {
        int digits = 2;
        String name = currencyCode;
        try {
            Currency currency = Currency.getInstance(currencyCode);
            digits = Math.max(currency.getDefaultFractionDigits(), 0);
            name = currency.getDisplayName(Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            // An unknown code: keep it as its name.
        }
        BigDecimal perUser = price.setScale(digits, RoundingMode.HALF_UP);
        BigDecimal yearly = perUser.multiply(BigDecimal.valueOf(users)).multiply(BigDecimal.valueOf(12));
        return new Quote(country.code(), country.name(), currencyCode, name, perUser, users, yearly,
                PRICE_PER_USER_EUR, bigMac, reference, date);
    }

    private static List<Country> loadCountries(JsonMapper json) {
        try (InputStream in = LicensePricing.class.getResourceAsStream("/license/country-codes.json")) {
            List<Country> countries = new ArrayList<>();
            for (JsonNode node : json.readTree(in)) {
                countries.add(new Country(node.path("code").asString().strip().toUpperCase(Locale.ROOT),
                        node.path("name").asString().strip(), node.path("dial_code").asString().strip()));
            }
            countries.sort(Comparator.comparing(c -> c.name().toLowerCase(Locale.ROOT)));
            return List.copyOf(countries);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read license/country-codes.json", e);
        }
    }

    /** The Big Mac prices of the latest date in the index, per ISO 3166 alpha-3 code; empty when missing. */
    static Map<String, BigMac> loadIndex(String resource) {
        Map<String, BigMac> latest = new HashMap<>();
        InputStream in = LicensePricing.class.getResourceAsStream(resource);
        if (in == null) {
            log.warn("No Big Mac index ({}): license prices are 7 € per user everywhere", resource);
            return latest;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> header = List.of(reader.readLine().split(","));
            int iso = header.indexOf("iso_a3");
            int currency = header.indexOf("currency_code");
            int price = header.indexOf("local_price");
            int date = header.indexOf("date");
            Map<String, BigMac> all = new HashMap<>();
            LocalDate newest = null;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] fields = line.split(",", -1);
                if (fields.length != header.size() || fields[price].isBlank()) {
                    continue;
                }
                BigMac row = new BigMac(fields[currency], new BigDecimal(fields[price]), LocalDate.parse(fields[date]));
                if (row.localPrice().signum() <= 0) {
                    continue;
                }
                BigMac known = all.get(fields[iso]);
                if (known == null || row.date().isAfter(known.date())) {
                    all.put(fields[iso], row);
                }
                newest = newest == null || row.date().isAfter(newest) ? row.date() : newest;
            }
            // Only the latest survey: an old price in another currency would be misleading.
            for (Map.Entry<String, BigMac> e : all.entrySet()) {
                if (e.getValue().date().equals(newest)) {
                    latest.put(e.getKey(), e.getValue());
                }
            }
            return latest;
        } catch (Exception e) {
            log.warn("Cannot read the Big Mac index ({}): license prices are 7 € per user everywhere", resource, e);
            return Map.of();
        }
    }
}
