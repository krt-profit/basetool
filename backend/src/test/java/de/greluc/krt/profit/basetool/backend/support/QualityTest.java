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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierWriteDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryGoodDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Quality} and the {@code @QualityValue} constraint (REQ-DATA-023). */
class QualityTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  @Test
  void clamp_keepsInRangeValues_andBoundsTheRest() {
    assertThat(Quality.clamp(650)).isEqualTo(650);
    assertThat(Quality.clamp(1200)).isEqualTo(1000);
    assertThat(Quality.clamp(-5)).isZero();
    assertThat(Quality.clamp((Integer) null)).isNull();
    assertThat(Quality.clamp(1000.5)).isEqualTo(1000.0);
  }

  @Test
  void clampImported_storesAnOutOfRangeImportAtTheBound() {
    assertThat(Quality.clampImported(1200, "test field")).isEqualTo(1000);
    assertThat(Quality.clampImported(1001.0, "test field")).isEqualTo(1000.0);
    assertThat(Quality.clampImported(900, "test field")).isEqualTo(900);
  }

  @Test
  void orMin_readsNullAsZero() {
    assertThat(Quality.orMin(null)).isZero();
    assertThat(Quality.orMin(700)).isEqualTo(700);
  }

  @Test
  void qualityValue_accepts1000_andRefuses1001AndNegative() {
    assertThat(tierViolations(1000)).isEmpty();
    assertThat(tierViolations(0)).isEmpty();
    assertThat(tierViolations(1001)).hasSize(1);
    assertThat(tierViolations(-1)).hasSize(1);
  }

  @Test
  void qualityValue_bindsOnARefineryGoodToo() {
    RefineryGoodDto tooHigh = new RefineryGoodDto(null, null, 1, null, 1, 1001, null);
    assertThat(validator.validate(tooHigh))
        .anyMatch(v -> "quality".equals(v.getPropertyPath().toString()));
  }

  private static Set<ConstraintViolation<QualityTierWriteDto>> tierViolations(int floor) {
    return validator.validate(new QualityTierWriteDto("T", floor, "t", "t", 0, true, null));
  }
}
