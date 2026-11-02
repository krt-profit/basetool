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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Reacts to a material being deleted, inside the transaction that deletes it; implemented by the
 * inventory module, whose minimum stock levels go with the material (REQ-INV-068). Every
 * implementation joins the caller's transaction ({@code MANDATORY}).
 */
@ObserverSpi
public interface MaterialDeletionObserver {

  /**
   * A material is about to be deleted; whatever hangs on it alone goes first.
   *
   * @param materialId the material to be deleted
   * @return the number of dependants removed
   */
  int beforeMaterialDelete(@NotNull UUID materialId);
}
