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

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code krt.refinery-import.*} — tuning knobs of the refinery
 * screenshot import's material-matching fuzzy stage (#434, plan §7.3). Externalized so the accept
 * threshold can be re-calibrated against the Phase 0 golden set without a code change.
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "krt.refinery-import")
public class RefineryImportProperties {

  /**
   * Minimum {@code BlueprintFuzzyMatcher} score at which a fuzzy candidate is auto-applied to the
   * draft (still flagged {@code LOW_CONFIDENCE_MATERIAL} so the user verifies it). Candidates below
   * this score leave the row unmatched and appear only as ranked suggestions. The conservative 0.9
   * default follows plan §7.3 — a wrong silent pick costs more than an extra manual assignment.
   */
  @NotNull
  @DecimalMin("0.5")
  @DecimalMax("1.0")
  private Double fuzzyAcceptThreshold = 0.9;

  /**
   * Minimum {@code BlueprintFuzzyMatcher} score at which the nearest refining method is accepted for
   * the draft. Deliberately lower than {@link #fuzzyAcceptThreshold}: refining methods are a CLOSED
   * set of nine very distinct UEX names, so a mis-read snaps unambiguously to its intended method —
   * the VLM's known autocorrect {@code "DINYX SOLVATION"} → {@code "Dinyx Solventation"} scores
   * ~0.83 while every other method stays below ~0.36, and non-method text (e.g. a stray panel label)
   * peaks near 0.4. The 0.6 default sits comfortably between that noise ceiling and the mis-read
   * floor. An open-catalogue material match, by contrast, must clear the stricter 0.9 bar.
   */
  @NotNull
  @DecimalMin("0.5")
  @DecimalMax("1.0")
  private Double methodFuzzyAcceptThreshold = 0.6;

  /**
   * Minimum score for a candidate to be offered as a suggestion at all (the pick-list floor, far
   * below {@link #fuzzyAcceptThreshold}). Mirrors {@code BlueprintFuzzyMatcher.DEFAULT_THRESHOLD}.
   */
  @NotNull
  @DecimalMin("0.0")
  @DecimalMax("1.0")
  private Double suggestionFloor = 0.5;

  /** Maximum number of ranked suggestions attached to a single unmatched/low-confidence issue. */
  @NotNull
  @Min(1)
  @Max(20)
  private Integer suggestionLimit = 5;
}
