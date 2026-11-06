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

/** Resolves local catalogue ids retained by a verified source reconciliation. */
public interface GameItemIdentityReferences {

  /**
   * Loads the surviving item for a current or retired local id.
   *
   * @param id local item id supplied by the caller
   * @return the surviving item, empty when neither a row nor an alias exists
   */
  @NotNull
  Optional<GameItem> findById(@NotNull UUID id);

  /**
   * Loads distinct surviving items for current or retired ids in one batch.
   *
   * @param ids caller-supplied local ids
   * @return the distinct surviving items
   */
  @NotNull
  List<GameItem> findAllById(@NotNull Iterable<UUID> ids);

  /**
   * Returns a reference to the survivor of a current or retired local id.
   *
   * @param id caller-supplied local id
   * @return an alias-resolved JPA reference
   */
  @NotNull
  GameItem getReferenceById(@NotNull UUID id);

  /**
   * Returns a reference for an id already resolved from the live catalogue's own projections.
   *
   * @param id live item id from a catalogue projection, never an external request
   * @return a JPA reference without an alias lookup
   */
  @NotNull
  GameItem getCanonicalReferenceById(@NotNull UUID id);

  /**
   * Maps retired local ids to their current survivors for batched external-reference resolution.
   *
   * @param ids caller-supplied local ids
   * @return redirects only; current or unknown ids are absent
   */
  @NotNull
  Map<UUID, UUID> findLocalIdRedirects(@NotNull Collection<UUID> ids);

  /**
   * Normalizes local-id filters without dropping unknown ids or repeating a surviving identity.
   *
   * @param ids current or retained local ids in caller order
   * @return distinct surviving ids in first-seen order, retaining unknown ids
   */
  @NotNull
  List<UUID> resolveLocalIds(@NotNull Collection<UUID> ids);
}
