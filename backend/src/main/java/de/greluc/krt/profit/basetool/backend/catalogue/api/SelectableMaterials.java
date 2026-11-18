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

import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The one lookup a book-in uses to put a material on a stock entry: it resolves the id like {@link
 * MaterialRepository#findById} and refuses a material that is not {@linkplain
 * Material#isSelectable() selectable}, one that is invisible, that the P4K retired or that is
 * hidden as a commodity no game source knows (REQ-P4K-013, REQ-P4K-075).
 */
@Component
@RequiredArgsConstructor
public class SelectableMaterials {

  private final MaterialRepository materialRepository;

  /**
   * Loads the material a book-in sets on a stock entry.
   *
   * @param id the caller-supplied current or merged material id
   * @return the material the id names now, always selectable
   * @throws NotFoundException when the id names no material
   * @throws RetiredMaterialException when the material is invisible, the P4K swept it, an admin hid
   *     it through a review case, or it is hidden as a commodity no game source knows
   */
  @NotNull
  public Material require(@NotNull UUID id) {
    Material material = Entities.require(materialRepository.findById(id), "Material not found");
    if (!material.isSelectable()) {
      throw new RetiredMaterialException(material.getId());
    }
    return material;
  }
}
