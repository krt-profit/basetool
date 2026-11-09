/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.UriComponentsBuilder;

/** The bounds of a bank read are RFC 3339 date-times whose UTC instant has a four-digit year. */
class BankQueryTest {

  private static final String ACCOUNT = "11111111-2222-4333-8444-555555555555";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "+10000-01-01T00:00:00Z",
        "-0001-01-01T00:00:00Z",
        "-4714-11-24T00:00:00Z",
        "9999-12-31T23:00:00-05:00",
        "0000-01-01T00:30:00+01:00"
      })
  void aBoundOutsideTheFourDigitYearsIsMalformed(String bound) {
    BankQuery query = BankQuery.of(ACCOUNT, bound, null, false);

    assertThat(query.violations())
        .extracting(violation -> violation.pointer())
        .containsExactly("/from");
    assertThat(query.bounds().from()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0000-01-01T00:00:00Z",
        "9999-12-31T23:59:59.999999999Z",
        "9999-12-31T18:00:00-05:00",
        "2026-11-01T00:00:00-01:00"
      })
  void aBoundWithinTheFourDigitYearsIsRelayedAsAFourDigitUtcInstant(String bound) {
    BankQuery query = BankQuery.of(ACCOUNT, null, bound, false);
    UriComponentsBuilder target = UriComponentsBuilder.fromPath("/bookings");

    query.bounds().addTo(target);

    assertThat(query.violations()).isEmpty();
    assertThat(target.build().getQueryParams().getFirst(BankQuery.TO)).matches("\\d{4}-.*Z");
  }

  @Test
  void theOffsetIsNormalisedToUtc() {
    BankQuery query = BankQuery.of(ACCOUNT, "2026-11-01T00:00:00-01:00", null, false);

    assertThat(query.bounds().from()).isEqualTo(Instant.parse("2026-11-01T01:00:00Z"));
  }
}
