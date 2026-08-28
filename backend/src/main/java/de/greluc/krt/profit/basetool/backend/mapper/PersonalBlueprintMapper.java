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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Entity → DTO mapper for {@link PersonalBlueprint}.
 *
 * <p>The {@code ownerUserId} is never copied into the response — it is the internal isolation key
 * and must not leak to clients (see the multi-user data isolation rule). The optional output-item
 * association is flattened to its id; writes are applied field-by-field in the service (only {@code
 * acquiredAt} / {@code note} are mutable), so no entity-write mapping is needed here.
 *
 * <p>{@code removable} is not derivable from the entity alone — it depends on whether the product
 * is in the admin-managed default set (REQ-INV-016) — so the service computes it (from the cached
 * default-key set) and passes it in.
 */
@Mapper(config = CentralMapperConfig.class)
public interface PersonalBlueprintMapper {

  /**
   * Maps an owned blueprint to its response DTO, flattening the optional {@code outputItem}
   * association to {@code outputItemId} ({@code null} when unresolved) and copying the
   * service-computed {@code removable} flag.
   *
   * @param entity the owned blueprint
   * @param removable whether the owner may delete the entry ({@code false} for a default blueprint)
   * @return the response DTO
   */
  @Mapping(target = "outputItemId", source = "entity.outputItem.id")
  @Mapping(target = "removable", source = "removable")
  PersonalBlueprintResponse toResponse(PersonalBlueprint entity, boolean removable);
}
