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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves the id of a material merged into another to the material that stays (REQ-DATA-046), so
 * an id a client still holds keeps working.
 */
public interface MaterialIdentityReferences {

  /**
   * Loads the material of a current or merged id.
   *
   * @param id the material id the caller holds
   * @return the material, or the one it was merged into; empty when neither exists
   */
  @NotNull
  Optional<Material> findById(@NotNull UUID id);

  /**
   * Tells whether a current or merged id names a material.
   *
   * @param id the material id the caller holds
   * @return whether the material, or the one it was merged into, exists
   */
  boolean existsById(@NotNull UUID id);

  /**
   * Loads the distinct materials of current or merged ids in one query.
   *
   * @param ids the material ids the caller holds
   * @return the distinct materials they name
   */
  @NotNull
  List<Material> findAllById(@NotNull Iterable<UUID> ids);

  /**
   * Returns a reference to the material of a current or merged id.
   *
   * @param id the material id the caller holds
   * @return a reference to the material the id names now
   */
  @NotNull
  Material getReferenceById(@NotNull UUID id);

  /**
   * Maps the merged ids among the given ones to the materials they were merged into.
   *
   * @param ids the material ids the caller holds
   * @return the merged ids only; a current or unknown id is absent
   */
  @NotNull
  Map<UUID, UUID> findLocalIdRedirects(@NotNull Collection<UUID> ids);
}
