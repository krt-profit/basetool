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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Member-facing catalogue identity, its typed, attributed Wiki enrichment and the shops whose known
 * item lists name it.
 *
 * @param id the item
 * @param name the item's name
 * @param gameUuid the item's game asset identity, or {@code null}
 * @param className the item's engine class, or {@code null}
 * @param wikiMetadata the Wiki snapshot, or {@code null}
 * @param availableAt the shops whose known item list names the item (REQ-P4K-044); empty means
 *     unknown, never "sold nowhere"
 */
@DtoMirror
public record ItemCatalogueDetailsDto(
    UUID id,
    String name,
    @Nullable UUID gameUuid,
    @Nullable String className,
    @Nullable ItemCatalogueMetadata wikiMetadata,
    @Unmodifiable List<ItemShopAvailabilityDto> availableAt) {

  /**
   * Copies the shop list; an absent one reads as empty.
   *
   * @param id the item
   * @param name the item's name
   * @param gameUuid the item's game asset identity
   * @param className the item's engine class
   * @param wikiMetadata the Wiki snapshot
   * @param availableAt the shops, or {@code null} from a backend without them
   */
  public ItemCatalogueDetailsDto {
    availableAt = availableAt == null ? List.of() : List.copyOf(availableAt);
  }
}
