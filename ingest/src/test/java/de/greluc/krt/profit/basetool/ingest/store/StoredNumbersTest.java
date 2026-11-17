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

package de.greluc.krt.profit.basetool.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The whole numbers the gateway reads back from Redis strings. */
class StoredNumbersTest {

  @Test
  void aWholeNumberIsRead() {
    assertThat(StoredNumbers.parse("4096")).isEqualTo(4096L);
    assertThat(StoredNumbers.parse("-1")).isEqualTo(-1L);
  }

  @Test
  void aMissingKeyOrAnythingElseReadsAsNoNumber() {
    assertThat(StoredNumbers.parse(null)).isNull();
    assertThat(StoredNumbers.parse("")).isNull();
    assertThat(StoredNumbers.parse("12.5")).isNull();
    assertThat(StoredNumbers.parse("garbage")).isNull();
  }
}
