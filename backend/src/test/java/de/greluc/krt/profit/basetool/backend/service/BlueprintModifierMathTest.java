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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintModifierSegment;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementModifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BlueprintModifierMath} — the server-side mirror of the frontend modifier
 * slider math and the REQ-INV-048 no-degradation floor.
 */
class BlueprintModifierMathTest {

  /**
   * Builds a simple linear modifier over the full band.
   *
   * @param betterWhen the better-when direction
   * @param atMin the multiplier at quality 0
   * @param atMax the multiplier at quality 1000
   * @return the modifier
   */
  private static BlueprintRequirementModifier linear(
      String betterWhen, double atMin, double atMax) {
    BlueprintRequirementModifier modifier = new BlueprintRequirementModifier();
    modifier.setBetterWhen(betterWhen);
    modifier.setQualityMin(0.0);
    modifier.setQualityMax(1000.0);
    modifier.setModifierAtMinQuality(atMin);
    modifier.setModifierAtMaxQuality(atMax);
    return modifier;
  }

  @Test
  void computeModifierValue_interpolatesLinearlyAcrossTheBand() {
    BlueprintRequirementModifier modifier = linear("higher", 0.95, 1.05);

    assertEquals(0.95, BlueprintModifierMath.computeModifierValue(modifier, 0), 1e-9);
    assertEquals(1.0, BlueprintModifierMath.computeModifierValue(modifier, 500), 1e-9);
    assertEquals(1.05, BlueprintModifierMath.computeModifierValue(modifier, 1000), 1e-9);
  }

  @Test
  void computeModifierValue_returnsNullWhenEndpointsMissing() {
    BlueprintRequirementModifier modifier = new BlueprintRequirementModifier();
    modifier.setBetterWhen("higher");

    assertNull(BlueprintModifierMath.computeModifierValue(modifier, 500));
  }

  @Test
  void computeModifierValue_holdsConstantWithinSteppedSegment() {
    BlueprintRequirementModifier modifier = new BlueprintRequirementModifier();
    modifier.setBetterWhen("higher");
    modifier.setValueRangeType("linear_integer_additive");
    BlueprintModifierSegment segment = new BlueprintModifierSegment();
    segment.setOrderIndex(0);
    segment.setQualityMin(0.0);
    segment.setQualityMax(1000.0);
    segment.setModifierAtStart(1.2);
    segment.setModifierAtEnd(1.4);
    modifier.addSegment(segment);

    assertEquals(1.2, BlueprintModifierMath.computeModifierValue(modifier, 0), 1e-9);
    assertEquals(1.2, BlueprintModifierMath.computeModifierValue(modifier, 999), 1e-9);
  }

  @Test
  void computeModifierValue_returnsNullForUnderspecifiedLinearSegment() {
    BlueprintRequirementModifier modifier = new BlueprintRequirementModifier();
    modifier.setBetterWhen("higher");
    modifier.setValueRangeType("linear");
    BlueprintModifierSegment segment = new BlueprintModifierSegment();
    segment.setOrderIndex(0);
    segment.setQualityMin(0.0);
    segment.setQualityMax(1000.0);
    segment.setModifierAtStart(0.9);
    modifier.addSegment(segment);

    assertNull(BlueprintModifierMath.computeModifierValue(modifier, 500));
  }

  @Test
  void isDegrading_higherStatWorsensBelowNeutral() {
    BlueprintRequirementModifier modifier = linear("higher", 0.95, 1.05);

    assertTrue(BlueprintModifierMath.isDegrading(modifier, 0));
    assertFalse(BlueprintModifierMath.isDegrading(modifier, 500));
    assertFalse(BlueprintModifierMath.isDegrading(modifier, 1000));
  }

  @Test
  void isDegrading_lowerStatWorsensAboveNeutral() {
    BlueprintRequirementModifier modifier = linear("lower", 1.05, 0.95);

    assertTrue(BlueprintModifierMath.isDegrading(modifier, 0));
    assertFalse(BlueprintModifierMath.isDegrading(modifier, 1000));
  }

  @Test
  void isDegrading_neutralDirectionNeverDegrades() {
    BlueprintRequirementModifier modifier = linear("neutral", 0.5, 0.5);

    assertFalse(BlueprintModifierMath.isDegrading(modifier, 0));
  }

  @Test
  void noDegradationFloor_isTheNeutralCrossoverForAHigherStat() {
    BlueprintRequirementModifier modifier = linear("higher", 0.95, 1.05);

    assertEquals(500, BlueprintModifierMath.noDegradationFloor(List.of(modifier)));
  }

  @Test
  void noDegradationFloor_takesTheStrictestModifier() {
    BlueprintRequirementModifier mild = linear("higher", 0.99, 1.01);
    BlueprintRequirementModifier strict = linear("higher", 0.6, 1.4);

    assertEquals(500, BlueprintModifierMath.noDegradationFloor(List.of(mild, strict)));
  }

  @Test
  void noDegradationFloor_ignoresAModifierThatWorsensAcrossTheWholeBand() {
    BlueprintRequirementModifier alwaysBad = linear("higher", 0.7, 0.9);

    assertEquals(0, BlueprintModifierMath.noDegradationFloor(List.of(alwaysBad)));
  }

  @Test
  void noDegradationFloor_isZeroWhenNoModifiers() {
    assertEquals(0, BlueprintModifierMath.noDegradationFloor(List.of()));
  }
}
