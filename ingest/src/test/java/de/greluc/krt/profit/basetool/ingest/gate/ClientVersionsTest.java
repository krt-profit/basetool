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

package de.greluc.krt.profit.basetool.ingest.gate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClientVersionsTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "NULL",
      value = {
        "VerseKit/2.4.0 (+https://example.org) | NULL  | true",
        "NULL                                  | NULL  | true",
        "VerseKit/2.4.0 (+https://example.org) | 2.4.0 | true",
        "VerseKit/2.4.1                        | 2.4.0 | true",
        "VerseKit/2.10.0                       | 2.9.9 | true",
        "VerseKit/3.0.0                        | 2.99.99 | true",
        "VerseKit/2.3.9                        | 2.4.0 | false",
        "VerseKit/2.4.0-beta.1                 | 2.4.0 | false",
        "VerseKit/2.4.0+build.7                | 2.4.0 | true",
        "VerseKit/2.4                          | 2.4.0 | false",
        "Mozilla/5.0 (Windows NT 10.0)         | 2.4.0 | false",
        "NULL                                  | 2.4.0 | false",
        "VerseKit/9.9.9                        | not-a-version | false"
      })
  void meetsTheMinimum(String userAgent, String minimum, boolean expected) {
    assertThat(ClientVersions.meets(userAgent, minimum)).isEqualTo(expected);
  }

  @Test
  void numbersCompareByValueWhateverTheirLeadingZerosOrLength() {
    assertThat(ClientVersions.compareNumbers("10", "9")).isPositive();
    assertThat(ClientVersions.compareNumbers("007", "7")).isZero();
    assertThat(ClientVersions.compareNumbers("0", "000")).isZero();
    assertThat(ClientVersions.compareNumbers("999999999", "1000000000")).isNegative();
    assertThat(ClientVersions.compareNumbers("12", "13")).isNegative();
  }
}
