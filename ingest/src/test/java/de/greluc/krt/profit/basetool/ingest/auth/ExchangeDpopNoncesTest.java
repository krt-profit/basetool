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

package de.greluc.krt.profit.basetool.ingest.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ExchangeDpopNoncesTest {

  private static final Instant START = Instant.parse("2026-09-27T12:00:00Z");
  private static final byte[] KEY = new byte[32];

  @Test
  void aNonceHoldsForItsWindowAndTheNext() {
    String nonce = at(START).current();

    assertThat(at(START).isValid(nonce)).isTrue();
    assertThat(at(START.plus(ExchangeDpopNonces.WINDOW)).isValid(nonce)).isTrue();
    assertThat(at(START.plus(ExchangeDpopNonces.WINDOW.multipliedBy(2))).isValid(nonce)).isFalse();
  }

  @Test
  void aNonceFromTheFutureIsRefused() {
    String later = at(START.plus(ExchangeDpopNonces.WINDOW)).current();

    assertThat(at(START).isValid(later)).isFalse();
  }

  @Test
  void anotherKeysNonceIsRefused() {
    byte[] other = Arrays.copyOf(KEY, KEY.length);
    other[0] = 1;
    String foreign = new ExchangeDpopNonces(Clock.fixed(START, ZoneOffset.UTC), other).current();

    assertThat(at(START).isValid(foreign)).isFalse();
  }

  @Test
  void malformedNoncesAreRefused() {
    ExchangeDpopNonces nonces = at(START);
    String valid = nonces.current();

    assertThat(nonces.isValid(null)).isFalse();
    assertThat(nonces.isValid(" ")).isFalse();
    assertThat(nonces.isValid("!!not-base64!!")).isFalse();
    assertThat(nonces.isValid("AAAA")).isFalse();
    assertThat(nonces.isValid(valid.substring(0, valid.length() - 2))).isFalse();
  }

  /**
   * Creates the nonces at a fixed instant with the test key.
   *
   * @param instant the instant
   * @return the nonces
   */
  private static ExchangeDpopNonces at(Instant instant) {
    return new ExchangeDpopNonces(Clock.fixed(instant, ZoneOffset.UTC), KEY);
  }
}
