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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.CentralMapperConfig;
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
 * The location is resolved and set by the service, never mapped from a request.
 */
@Mapper(config = CentralMapperConfig.class)
public interface PersonalInventoryItemMapper {

  /**
   * Maps a {@link PersonalInventoryItem} to its response DTO; the persisted {@code
   * locationNameSnapshot} is surfaced as {@code locationName} and the location's kind is derived
   * from its city or station link without loading either.
   */
  @Mapping(target = "locationName", source = "locationNameSnapshot")
  @Mapping(target = "locationId", source = "location.id")
  @Mapping(
      target = "locationType",
      expression = "java(PersonalInventoryLocationType.of(entity.getLocation()))")
  PersonalInventoryItemResponse toResponse(PersonalInventoryItem entity);

  /**
   * Builds a new {@link PersonalInventoryItem} from the create-request. {@code ownerUserId} is
   * assigned by the service from the JWT sub claim; the location and its name snapshot are set by
   * the service after the selectable-location check, never by the client.
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "ownerUserId", ignore = true)
  @Mapping(target = "location", ignore = true)
  @Mapping(target = "locationNameSnapshot", ignore = true)
  PersonalInventoryItem toEntity(PersonalInventoryItemCreateRequest request);

  /**
   * Applies an update request to an already-managed entity. {@code id}, {@code ownerUserId}, {@code
   * version}, {@code createdAt}, the location and {@code locationNameSnapshot} are excluded:
   * version is owned by JPA optimistic locking; the location and its snapshot are set by the
   * service after the selectable-location check.
   */
  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "ownerUserId", ignore = true)
  @Mapping(target = "location", ignore = true)
  @Mapping(target = "locationNameSnapshot", ignore = true)
  void updateEntity(
      @MappingTarget PersonalInventoryItem entity, PersonalInventoryItemUpdateRequest request);
}
