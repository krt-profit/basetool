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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the job-order settings out of the system key-value store as typed values. */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobOrderSettingsService {

  /** Store key of the yellow ageing threshold. */
  static final String AGE_YELLOW_KEY = "job_order.age_yellow_days";

  /** Store key of the red ageing threshold. */
  static final String AGE_RED_KEY = "job_order.age_red_days";

  /** Yellow threshold used when the stored value is absent or not a non-negative integer. */
  static final int DEFAULT_AGE_YELLOW_DAYS = 30;

  /** Red threshold used when the stored value is absent or not a non-negative integer. */
  static final int DEFAULT_AGE_RED_DAYS = 90;

  private final SystemSettings systemSettings;

  /**
   * Returns the current ageing thresholds; an absent or malformed stored value falls back to its
   * default.
   *
   * @return the thresholds in days
   */
  @Transactional(readOnly = true)
  public @NotNull JobOrderSettingsDto read() {
    return new JobOrderSettingsDto(
        days(AGE_YELLOW_KEY, DEFAULT_AGE_YELLOW_DAYS), days(AGE_RED_KEY, DEFAULT_AGE_RED_DAYS));
  }

  private int days(@NotNull String key, int fallback) {
    return systemSettings
        .getSettingValue(key)
        .map(raw -> {
          try {
            int parsed = Integer.parseInt(raw.strip());
            return parsed >= 0 ? parsed : fallback;
          } catch (NumberFormatException _) {
            log.warn("Setting {} is not an integer, using {}", key, fallback);
            return fallback;
          }
        })
        .orElse(fallback);
  }
}
