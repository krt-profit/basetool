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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Chart memory follows its viewport while all movements still contribute to exact extrema. */
class BankBalanceEnvelopeTest {
  @Test
  void aHundredThousandMovementsRetainBoundedVerticesAndExactExtrema() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = from.plusSeconds(100000);
    BankBalanceEnvelope envelope = new BankBalanceEnvelope(from, to, BigDecimal.TEN);
    for (int i = 0; i < 100000; i++) {
      envelope.add(from.plusSeconds(i), i % 2 == 0 ? BigDecimal.ONE : BigDecimal.ONE.negate());
    }
    envelope.add(to, BigDecimal.valueOf(123456));
    envelope.add(to, BigDecimal.valueOf(-234567));
    assertThat(envelope.getMaximum()).isEqualByComparingTo("123466");
    assertThat(envelope.getMinimum()).isEqualByComparingTo("-111101");
    assertThat(envelope.vertices()).hasSizeLessThanOrEqualTo(4 * BankBalanceEnvelope.COLUMNS + 2);
    assertThat(envelope.vertices()).anySatisfy(vertex -> assertThat(vertex[1]).isEqualTo(123466d));
    assertThat(envelope.vertices()).anySatisfy(vertex -> assertThat(vertex[1]).isEqualTo(-111101d));
    assertThat(envelope.vertices().getFirst()[1]).isEqualTo(10d);
    assertThat(envelope.vertices().getLast()[1]).isEqualTo(-111101d);
  }
}
