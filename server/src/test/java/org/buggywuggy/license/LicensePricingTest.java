/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.buggywuggy.common.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LicensePricingTest {

    private final LicensePricing pricing = new LicensePricing(JsonMapper.builder().build());

    @Test
    void scalesThePriceByTheBigMacIndex() {
        // 7 € in the Netherlands, where a Big Mac costs 6.26 € (July 2026).
        var nl = pricing.quote("NL", 10);
        assertThat(nl.currency()).isEqualTo("EUR");
        assertThat(nl.currencyName()).isEqualTo("Euro");
        assertThat(nl.pricePerUserMonth()).isEqualByComparingTo("7.00");
        assertThat(nl.yearlyTotal()).isEqualByComparingTo("840.00");

        // Japan: 7 × 500 / 6.26 = 559.1 yen, without decimals.
        var jp = pricing.quote("jp", 3);
        assertThat(jp.currency()).isEqualTo("JPY");
        assertThat(jp.currencyName()).isEqualTo("Japanese Yen");
        assertThat(jp.pricePerUserMonth()).isEqualByComparingTo("559");
        assertThat(jp.yearlyTotal()).isEqualByComparingTo(new BigDecimal(559 * 3 * 12));
        assertThat(jp.bigMacPrice()).isEqualByComparingTo("500");
        assertThat(jp.referenceBigMacPrice()).isEqualByComparingTo("6.26");

        // Belgium has a price of its own (5.39 €); Monaco uses the euro area's (6.19 €).
        assertThat(pricing.quote("BE", 1).pricePerUserMonth()).isEqualByComparingTo("6.03");
        assertThat(pricing.quote("MC", 1).pricePerUserMonth()).isEqualByComparingTo("6.92");
    }

    @Test
    void usesFactorOneWithoutAPrice() {
        var af = pricing.quote("AF", 2);
        assertThat(af.currency()).isEqualTo("EUR");
        assertThat(af.pricePerUserMonth()).isEqualByComparingTo("7.00");
        assertThat(af.bigMacPrice()).isNull();

        var withoutIndex = new LicensePricing(JsonMapper.builder().build(), "/license/missing.csv");
        assertThat(withoutIndex.quote("JP", 1).pricePerUserMonth()).isEqualByComparingTo("7.00");
        assertThat(withoutIndex.quote("JP", 1).currency()).isEqualTo("EUR");
    }

    @Test
    void listsCountriesWithDialCodes() {
        assertThat(pricing.countries()).hasSizeGreaterThan(200);
        assertThat(pricing.country("nl")).get().satisfies(c -> {
            assertThat(c.name()).isEqualTo("Netherlands");
            assertThat(c.dialCode()).isEqualTo("+31");
        });
        assertThatThrownBy(() -> pricing.quote("XX", 1)).isInstanceOf(ApiException.class);
    }
}
