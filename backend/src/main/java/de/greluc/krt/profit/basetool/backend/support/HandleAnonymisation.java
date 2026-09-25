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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The sentinel that replaces a handle snapshot an Art. 17 request has erased (REQ-SEC-062).
 *
 * <p>A sentinel keeps the {@code NOT NULL} handle columns valid (REQ-AUDIT-001). Human-facing
 * surfaces render it as {@code general.anonymisedHandle}; machine-readable exports keep the raw
 * token. The frontend's {@code HandleDisplay} mirrors the constant.
 */
public final class HandleAnonymisation {

  /**
   * The stored replacement for an erased handle snapshot; members cannot choose it as a name (see
   * {@link #isReserved(String)}).
   */
  public static final String SENTINEL = "#ANONYMISED#";

  /** Not instantiable. */
  private HandleAnonymisation() {}

  /**
   * Whether a name a member chose for themselves would collide with the erasure sentinel, compared
   * case-insensitively after trimming.
   *
   * @param name the candidate display name or username, possibly {@code null}
   * @return {@code true} when it must be rejected
   */
  @Contract(value = "null -> false", pure = true)
  public static boolean isReserved(@Nullable String name) {
    return name != null && SENTINEL.equalsIgnoreCase(name.trim());
  }

  /**
   * Tells whether a handle snapshot has been erased on request.
   *
   * @param handle the stored handle snapshot, possibly {@code null}
   * @return {@code true} when the value is the erasure sentinel
   */
  @Contract(value = "null -> false", pure = true)
  public static boolean isAnonymised(@Nullable String handle) {
    return SENTINEL.equals(handle);
  }

  /**
   * Renders a handle for human-facing backend output such as the PDF reports. Machine-readable
   * exports keep the raw token.
   *
   * @param handle the stored handle snapshot, possibly {@code null}
   * @param anonymisedLabel the resolved {@code general.anonymisedHandle} label
   * @return the label when the value is the erasure sentinel, otherwise the handle unchanged
   */
  @Contract(pure = true)
  public static @Nullable String humanise(
      @Nullable String handle, @NotNull String anonymisedLabel) {
    return SENTINEL.equals(handle) ? anonymisedLabel : handle;
  }
}
