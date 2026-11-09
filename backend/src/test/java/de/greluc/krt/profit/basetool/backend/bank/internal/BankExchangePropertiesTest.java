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

import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** The open-request cap of the bank on the exchange is 20 unless configured (REQ-BXCH-006). */
class BankExchangePropertiesTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void theOpenRequestCapIsTwentyByDefault() {
    assertThat(BoundProperties.defaults(BankExchangeProperties.class).openRequestCap())
        .isEqualTo(20);
  }

  @Test
  void theOpenRequestCapCanBeConfigured() {
    assertThat(
            BoundProperties.bind(BankExchangeProperties.class, "open-request-cap", 5)
                .openRequestCap())
        .isEqualTo(5);
  }

  @Test
  void aCapBelowOneOrAboveTheCeilingIsRefused() {
    assertThat(validator.validate(new BankExchangeProperties(0))).isNotEmpty();
    assertThat(validator.validate(new BankExchangeProperties(1001))).isNotEmpty();
    assertThat(validator.validate(new BankExchangeProperties(1))).isEmpty();
  }
}
