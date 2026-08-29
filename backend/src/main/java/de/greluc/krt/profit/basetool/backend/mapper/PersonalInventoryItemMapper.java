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

import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryItem;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemUpdateRequest;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * Entity ↔ DTO mapper for {@link PersonalInventoryItem}.
 *
 * <p>The {@code ownerUserId} is intentionally never copied to/from the response DTO: it is an
 * internal isolation key (see AGENTS.md "MULTI-USER DATA ISOLATION") and must not leak to clients.
 */
@Mapper(config = CentralMapperConfig.class)
public interface PersonalInventoryItemMapper {

  /**
   * Maps a {@link PersonalInventoryItem} to its response DTO; the persisted {@code
   * locationNameSnapshot} is surfaced as {@code locationName} so the client never sees the internal
   * naming.
   */
  @Mapping(target = "locationName", source = "locationNameSnapshot")
  PersonalInventoryItemResponse toResponse(PersonalInventoryItem entity);

  /**
   * Builds a new {@link PersonalInventoryItem} from the create-request. {@code ownerUserId} is
   * assigned by the service from the JWT sub claim; the location name snapshot is set by the
   * service after a UEX lookup, never by the client.
   */
  // Note: inherited fields from AbstractEntity (id, version, createdAt, updatedAt) are
  // not part of the Lombok @Builder generated for this class and are therefore covered
  // by the global unmappedTargetPolicy = IGNORE rather than by explicit @Mapping(ignore).
  @Mapping(target = "ownerUserId", ignore = true)
  @Mapping(target = "locationNameSnapshot", ignore = true)
  PersonalInventoryItem toEntity(PersonalInventoryItemCreateRequest request);

  /**
   * Applies an update request to an already-managed entity. {@code id}, {@code ownerUserId}, {@code
   * version}, {@code createdAt} and {@code locationNameSnapshot} are excluded: version is owned by
   * JPA optimistic locking; the location name snapshot must be set by the service after a
   * successful UEX lookup.
   */
  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "ownerUserId", ignore = true)
  @Mapping(target = "locationNameSnapshot", ignore = true)
  void updateEntity(
      @MappingTarget PersonalInventoryItem entity, PersonalInventoryItemUpdateRequest request);
}
