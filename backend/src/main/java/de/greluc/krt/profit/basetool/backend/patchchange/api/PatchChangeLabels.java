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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Reads the current labels of patch changes by id, for the feeds that name the patch change behind
 * a change (REQ-PATCH-007). A corrected label reads as corrected.
 */
public interface PatchChangeLabels {

  /**
   * Looks up the labels of the given patch changes in one read.
   *
   * @param ids the patch changes
   * @return the label by id; an unknown id is absent
   */
  @NotNull
  Map<UUID, String> labels(@NotNull Collection<UUID> ids);
}
