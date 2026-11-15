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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Names the patch changes the order history shows as the cause of a shortage or a cancelled Bank
 * attempt (REQ-PATCH-044, REQ-ORDERS-064). Implemented by the patch-change module; a corrected
 * label reads as corrected.
 */
public interface JobOrderPatchLabels {

  /**
   * Looks up the current labels of the given patch changes in one read.
   *
   * @param ids the patch changes
   * @return the label by id; an unknown id is absent
   */
  @NotNull
  Map<UUID, String> labels(@NotNull Collection<UUID> ids);
}
