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
 * The one lookup a write uses to put a blueprint on an entry by its id: it resolves the id like
 * {@link BlueprintRepository#findById} and refuses a blueprint the P4K retired (REQ-P4K-013).
 */
@Component
@RequiredArgsConstructor
public class SelectableBlueprints {

  private final BlueprintRepository blueprintRepository;

  /**
   * Loads the blueprint a write sets on an entry.
   *
   * @param id the caller-supplied blueprint id
   * @return the blueprint, never retired
   * @throws NotFoundException when the id names no blueprint
   * @throws RetiredBlueprintException when the P4K swept the blueprint or an admin hid it
   */
  @NotNull
  public Blueprint require(@NotNull UUID id) {
    Blueprint blueprint =
        Entities.require(blueprintRepository.findById(id), () -> "Blueprint not found: " + id);
    if (blueprint.isP4kRetired()) {
      throw new RetiredBlueprintException(id);
    }
    return blueprint;
  }
}
