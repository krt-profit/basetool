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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.validation.MaterialPieceTypeLookup;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Service-layer implementation of {@link MaterialPieceTypeLookup} for the quantity-amount
 * constraint validator, keeping the {@code validation} package free of repository dependencies.
 */
@Service
@RequiredArgsConstructor
public class MaterialPieceTypeLookupService implements MaterialPieceTypeLookup {

  private final MaterialRepository materialRepository;

  /**
   * Resolves the material by id and reports whether its quantity type is {@link
   * QuantityType#PIECE}.
   *
   * @param materialId the material id to resolve; an id that does not resolve yields {@code false}.
   * @return {@code true} iff the material exists and is PIECE-measured.
   */
  @Override
  public boolean isPieceQuantity(UUID materialId) {
    return materialRepository
        .findById(materialId)
        .map(material -> material.getQuantityType() == QuantityType.PIECE)
        .orElse(false);
  }
}
