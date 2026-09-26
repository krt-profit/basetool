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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Validates the shared-secret strength invariant on {@link DiscordSpiPrecheckProperties}: a blank
 * secret stays valid (it disables the account-existence endpoint), while a configured secret must
 * be at least 32 characters so the sole credential guarding the {@code permitAll},
 * rate-limiter-exempt endpoint cannot be a short, online-guessable value.
 */
class DiscordSpiPrecheckPropertiesTest {

  private ValidatorFactory factory;
  private Validator validator;

  @BeforeEach
  void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterEach
  void tearDown() {
    factory.close();
  }

  @Test
  void blankSecret_isValid_becauseItDisablesTheEndpoint() {
    DiscordSpiPrecheckProperties props = new DiscordSpiPrecheckProperties("");

    assertThat(validator.validate(props)).isEmpty();
  }

  @Test
  void shortSecret_isRejected() {
    DiscordSpiPrecheckProperties props = new DiscordSpiPrecheckProperties("too-short-secret");

    assertThat(validator.validate(props)).isNotEmpty();
  }

  @Test
  void secretOfAtLeastThirtyTwoChars_isValid() {
    DiscordSpiPrecheckProperties props = new DiscordSpiPrecheckProperties("x".repeat(32));

    assertThat(validator.validate(props)).isEmpty();
  }

  @Test
  void theSecretIsUnsetByDefaultAndNeverPrinted() {
    DiscordSpiPrecheckProperties unset =
        BoundProperties.defaults(DiscordSpiPrecheckProperties.class);
    DiscordSpiPrecheckProperties set = new DiscordSpiPrecheckProperties("x".repeat(32));

    assertThat(unset.sharedSecret()).isEmpty();
    assertThat(set.toString()).doesNotContain("x".repeat(32)).contains("<redacted>");
  }
}
