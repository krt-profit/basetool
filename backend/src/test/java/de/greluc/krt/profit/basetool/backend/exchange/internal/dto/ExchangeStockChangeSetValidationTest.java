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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet.Quantity;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests that a stock quantity keeps the gateway schema's bounds of 0 to 10^9 (REQ-XCH-016). */
class ExchangeStockChangeSetValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void initValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeFactory() {
    if (factory != null) {
      factory.close();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.001", "12.5", "1000000000"})
  void aQuantityWithinTheSchemaBoundsPasses(String amount) {
    assertThat(validator.validate(new Quantity(new BigDecimal(amount), "SCU"))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.001", "-1", "1000000000.001", "1e12"})
  void aQuantityOutsideTheSchemaBoundsIsRefused(String amount) {
    assertThat(validator.validate(new Quantity(new BigDecimal(amount), "SCU")))
        .singleElement()
        .satisfies(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("amount"));
  }
}
