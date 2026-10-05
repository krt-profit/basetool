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

package de.greluc.krt.profit.basetool.backend.admin.api;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The admin module's published access to the runtime system settings (plan §5.2): the raw value of
 * a setting key, and the write of a system-owned flag.
 */
public interface SystemSettings {

  /**
   * Reads the raw value of a setting.
   *
   * @param key setting key
   * @return the string value, or empty when the key is absent
   */
  @NotNull
  Optional<String> getSettingValue(@NotNull String key);

  /**
   * Stores a value under a key, creating the row when the key is absent. Joins the caller's
   * transaction; no optimistic-lock check, for system-owned keys only.
   *
   * @param key setting key
   * @param value the new value
   */
  void putSettingValue(@NotNull String key, @NotNull String value);
}
