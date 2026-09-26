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

import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintModifierSegment;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementModifier;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Stat-modifier curve math for the blueprint craftability calculation, mirroring the frontend
 * {@code computeModifierValue}.
 *
 * <p>A modifier maps an ingredient quality to a stat multiplier; below {@code 1.0} worsens a
 * higher-is-better stat, above {@code 1.0} a lower-is-better one (REQ-INV-048).
 */
final class BlueprintModifierMath {

  /** Lowest representable ingredient quality. */
  static final int MIN_QUALITY = 0;

  /** Highest representable ingredient quality. */
  static final int MAX_QUALITY = 1000;

  /** Neutral multiplier: a value on the wrong side of this worsens the stat. */
  private static final double NEUTRAL = 1.0d;

  /** Float tolerance so a multiplier of exactly {@code 1.0} never reads as degrading. */
  private static final double EPS = 1e-9d;

  private BlueprintModifierMath() {}

  /**
   * Computes the stat multiplier a modifier applies at the given ingredient quality.
   *
   * @param modifier the stat modifier
   * @param quality the ingredient quality to evaluate at
   * @return the multiplier, or {@code null} when the curve has no usable endpoints
   */
  @Nullable
  static Double computeModifierValue(
      @NotNull BlueprintRequirementModifier modifier, double quality) {
    List<BlueprintModifierSegment> segments = modifier.getSegments();
    if (segments != null && !segments.isEmpty()) {
      boolean stepped =
          !"linear"
              .equalsIgnoreCase(
                  modifier.getValueRangeType() == null ? "linear" : modifier.getValueRangeType());
      for (int i = 0; i < segments.size(); i++) {
        BlueprintModifierSegment segment = segments.get(i);
        Double a = segment.getQualityMin();
        Double b = segment.getQualityMax();
        Double vs = segment.getModifierAtStart();
        Double ve = segment.getModifierAtEnd();
        if (a == null || b == null) {
          continue;
        }
        if (quality <= b || i == segments.size() - 1) {
          if (stepped) {
            return vs == null ? ve : vs;
          }
          if (vs == null || ve == null) {
            return null;
          }
          double t = b.equals(a) ? 0.0d : clamp01((quality - a) / (b - a));
          return lerp(vs, ve, t);
        }
      }
      return null;
    }
    Double qmin = modifier.getQualityMin();
    Double qmax = modifier.getQualityMax();
    Double vmin = modifier.getModifierAtMinQuality();
    Double vmax = modifier.getModifierAtMaxQuality();
    if (qmin != null && qmax != null && vmin != null && vmax != null) {
      double t = qmax.equals(qmin) ? 0.0d : clamp01((quality - qmin) / (qmax - qmin));
      return lerp(vmin, vmax, t);
    }
    return null;
  }

  /**
   * Tests whether a modifier worsens its stat at the given quality. A neutral or unknown direction,
   * or an underspecified curve, never degrades.
   *
   * @param modifier the stat modifier
   * @param quality the ingredient quality to evaluate at
   * @return {@code true} if the modifier worsens its stat at this quality
   */
  static boolean isDegrading(@NotNull BlueprintRequirementModifier modifier, double quality) {
    Double value = computeModifierValue(modifier, quality);
    if (value == null) {
      return false;
    }
    String betterWhen = modifier.getBetterWhen();
    if ("higher".equalsIgnoreCase(betterWhen)) {
      return value < NEUTRAL - EPS;
    }
    if ("lower".equalsIgnoreCase(betterWhen)) {
      return value > NEUTRAL + EPS;
    }
    return false;
  }

  /**
   * Returns the lowest quality (0..1000) at which none of a slot's modifiers worsen their stat
   * (REQ-INV-048). Modifiers that worsen across the entire band are ignored.
   *
   * @param modifiers the slot's stat modifiers (may be empty)
   * @return the lowest non-degrading quality, or {@code 0} when nothing constrains it
   */
  static int noDegradationFloor(@NotNull List<BlueprintRequirementModifier> modifiers) {
    int floor = MIN_QUALITY;
    for (BlueprintRequirementModifier modifier : modifiers) {
      floor = Math.max(floor, modifierFloor(modifier));
    }
    return floor;
  }

  /**
   * Lowest quality (0..1000) at which a single modifier stops worsening its stat, or {@code 0} when
   * it either never worsens or worsens across the whole band (both mean "imposes no floor").
   *
   * @param modifier the stat modifier
   * @return the per-modifier no-degradation floor
   */
  private static int modifierFloor(@NotNull BlueprintRequirementModifier modifier) {
    for (int q = MIN_QUALITY; q <= MAX_QUALITY; q++) {
      if (!isDegrading(modifier, q)) {
        return q;
      }
    }
    return MIN_QUALITY;
  }

  /**
   * Clamps a value to the unit interval.
   *
   * @param t the value
   * @return {@code t} clamped to {@code [0, 1]}
   */
  private static double clamp01(double t) {
    return t < 0.0d ? 0.0d : Math.min(t, 1.0d);
  }

  /**
   * Linearly interpolates between two values.
   *
   * @param a the start value
   * @param b the end value
   * @param t the unit-interval position
   * @return {@code a + (b - a) * t}
   */
  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
