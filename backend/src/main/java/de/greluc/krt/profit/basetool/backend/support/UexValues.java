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

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Shared normalisers for the raw values the UEX catalogue API emits, so the per-entity UEX sync
 * services stop each carrying private copies. The two 0/1-flag semantics are deliberately kept as
 * <b>separately named</b> methods — some UEX surfaces treat an absent flag as {@code false}, others
 * must preserve the {@code null}/{@code false} distinction — so collapsing them into one method
 * would silently change behaviour on one side of the fork.
 */
@Slf4j
public final class UexValues {

  private UexValues() {}

  /**
   * The crew complement UEX serves for a vehicle, split into its two bounds.
   *
   * @param min the smallest crew the vehicle can be operated with, or {@code null} when UEX carried
   *     nothing parseable
   * @param max the largest crew it supports; equal to {@code min} when UEX states a single number
   */
  public record CrewRange(@Nullable Integer min, @Nullable Integer max) {

    /** The answer for a vehicle whose {@code crew} field is absent, blank or unparseable. */
    static final CrewRange UNKNOWN = new CrewRange(null, null);
  }

  /**
   * Splits UEX's compact {@code crew} string into its min / max bounds.
   *
   * <p>UEX serves the crew complement as either a single number ({@code "1"}) or a comma-separated
   * pair ({@code "1,2"} = one to two). It does <b>not</b> serve the {@code crew_min} / {@code
   * crew_max} fields this project used to bind — they decoded to {@code null} and cleared both
   * columns on every run (REQ-DATA-015 / ADR-0148), which is why the range is derived here instead.
   *
   * <p>Anything that does not parse — a blank string, a range with a non-numeric bound, an empty
   * second bound — yields {@link CrewRange#UNKNOWN} rather than a half-filled range: a crew of "1
   * to ?" is not a fact UEX stated. Extra bounds past the second are ignored.
   *
   * @param crew the raw {@code crew} value, or {@code null}
   * @return the parsed bounds, or {@link CrewRange#UNKNOWN} when nothing parseable was carried
   */
  public static CrewRange parseCrew(@Nullable String crew) {
    if (!StringUtils.hasText(crew)) {
      return CrewRange.UNKNOWN;
    }
    // split(-1) keeps trailing empties, so "1," stays a two-bound value with an unparseable second
    // bound (-> UNKNOWN) instead of silently collapsing to the single-value form, and "," does not
    // yield an empty array whose parts[0] would throw past the NumberFormatException catch.
    String[] parts = crew.split(",", -1);
    try {
      int min = Integer.parseInt(parts[0].trim());
      int max = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : min;
      return new CrewRange(min, Math.max(min, max));
    } catch (NumberFormatException e) {
      log.debug("Non-numeric UEX crew value '{}' — leaving crew_min / crew_max null", crew);
      return CrewRange.UNKNOWN;
    }
  }

  /**
   * Normalises a UEX 0/1 integer flag into a {@link Boolean}, treating an absent flag as {@code
   * false} ("not set" ≡ off). Use this on surfaces that do not distinguish "UEX omitted the flag"
   * from "UEX carried 0".
   *
   * @param flag the UEX-style 0/1 integer, or {@code null}
   * @return {@code true} iff {@code flag} equals 1, {@code false} otherwise (including {@code
   *     null})
   */
  public static Boolean asBooleanOrFalse(@Nullable Integer flag) {
    return flag != null && flag == 1;
  }

  /**
   * Normalises a UEX 0/1 integer flag into a {@link Boolean}, preserving the distinction between
   * {@code null} (UEX did not carry the flag) and {@code false} (UEX carried 0). Use this where the
   * absent-vs-zero difference is meaningful.
   *
   * @param flag the UEX-style 0/1 integer, or {@code null}
   * @return {@code true} iff {@code flag} equals 1, {@code false} for 0, {@code null} for a {@code
   *     null} input
   */
  public static @Nullable Boolean asBooleanOrNull(@Nullable Integer flag) {
    if (flag == null) {
      return null;
    }
    return flag == 1;
  }

  /**
   * Parses a UEX-emitted UUID string, tolerating the empty/blank strings UEX returns for a large
   * fraction of rows (concepts, retired variants, flavour categories) by returning {@code null}
   * instead of throwing. A malformed non-blank value is logged at debug and likewise skipped.
   *
   * @param raw the raw UUID string from a UEX DTO, or {@code null}
   * @return the parsed UUID, or {@code null} for empty / blank / malformed input
   */
  public static @Nullable UUID parseUuid(@Nullable String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      log.debug("Skipping malformed UEX uuid '{}': {}", raw, e.getMessage());
      return null;
    }
  }
}
