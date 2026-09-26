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

package de.greluc.krt.profit.basetool.backend.validation;

import java.util.UUID;

/**
 * Lookup that tells {@link ValidQuantityAmountValidator} whether a material is measured in whole
 * PIECEs, keeping the {@code validation} package free of {@code model} and {@code repository}
 * dependencies. Implemented in the service layer.
 */
public interface MaterialPieceTypeLookup {

  /**
   * Reports whether the given material is measured in whole PIECEs.
   *
   * @param materialId the material id to resolve; may be any UUID, including one that does not
   *     resolve to a material.
   * @return {@code true} iff a material with this id exists and its quantity type is PIECE; {@code
   *     false} when the material is SCU-measured or does not exist.
   */
  boolean isPieceQuantity(UUID materialId);
}
