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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the refinery settings out of the system key-value store as typed values. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefinerySettingsService {

  /** Store key of the unit-to-SCU rounding mode. */
  static final String ROUNDING_MODE_KEY = "refinery.rounding.mode";

  /** Rounding mode used when the stored value is absent or names no {@link RoundingMode}. */
  static final String DEFAULT_ROUNDING_MODE = RoundingMode.UP.name();

  private final SystemSettings systemSettings;

  /**
   * Returns the current refinery settings; an absent or unknown stored rounding mode falls back to
   * {@code UP}.
   *
   * @return the refinery settings
   */
  @Transactional(readOnly = true)
  public @NotNull RefinerySettingsDto read() {
    String mode =
        systemSettings
            .getSettingValue(ROUNDING_MODE_KEY)
            .map(String::strip)
            .filter(RefinerySettingsService::isRoundingMode)
            .orElse(DEFAULT_ROUNDING_MODE);
    return new RefinerySettingsDto(mode);
  }

  private static boolean isRoundingMode(@NotNull String name) {
    try {
      RoundingMode.valueOf(name);
      return true;
    } catch (IllegalArgumentException _) {
      log.warn(
          "Setting {} names no rounding mode, using {}", ROUNDING_MODE_KEY, DEFAULT_ROUNDING_MODE);
      return false;
    }
  }
}
