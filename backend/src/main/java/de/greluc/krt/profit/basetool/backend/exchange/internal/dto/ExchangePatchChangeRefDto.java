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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The patch change behind a change in a feed (REQ-PATCH-007): its id, with which a client takes a
 * patch change over in one step, and its current label.
 *
 * @param id the patch change's id
 * @param label its label, e.g. {@code 4.4}
 */
public record ExchangePatchChangeRefDto(@NotNull UUID id, @NotNull String label) {

  /**
   * Names a patch change with the label a page read for it.
   *
   * @param id the patch change, or {@code null} when none made the change
   * @param labels the label by patch change id, read once per page
   * @return the reference, or {@code null} when there is no patch change or no label for it
   */
  public static @Nullable ExchangePatchChangeRefDto of(
      @Nullable UUID id, @NotNull Map<UUID, String> labels) {
    if (id == null) {
      return null;
    }
    String label = labels.get(id);
    return label == null ? null : new ExchangePatchChangeRefDto(id, label);
  }
}
