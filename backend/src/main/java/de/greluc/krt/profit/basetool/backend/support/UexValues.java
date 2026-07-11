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
