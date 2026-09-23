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

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import org.jetbrains.annotations.NotNull;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Material entities and DTOs. */
@Mapper(config = CentralMapperConfig.class, uses = MaterialCategoryMapper.class)
public interface MaterialMapper {
  /**
   * Maps a {@link Material} entity to its DTO. UEX-style {@code Integer} 0/1 flags ({@code
   * isIllegal}, {@code isVolatileQt}, {@code isVolatileTime}) are normalised to {@code Boolean} for
   * the client. {@code isManualEntry} is derived from {@code sourceSystems == MANUAL} (R9 Step 1):
   * the legacy {@code is_manual_entry} column was dropped in R9 Step 4 — the canonical provenance
   * lives in {@code source_systems} (V116 backfill).
   */
  @Mapping(
      target = "isIllegal",
      expression = "java(entity.getIsIllegal() != null && entity.getIsIllegal() == 1)")
  @Mapping(
      target = "isVolatileQt",
      expression = "java(entity.getIsVolatileQt() != null && entity.getIsVolatileQt() == 1)")
  @Mapping(
      target = "isVolatileTime",
      expression = "java(entity.getIsVolatileTime() != null && entity.getIsVolatileTime() == 1)")
  @Mapping(
      target = "isManualEntry",
      expression =
          "java(entity.getSourceSystems()"
              + " == de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem.MANUAL)")
  MaterialDto toDto(Material entity);

  /**
   * Builds a new {@link Material} entity from the DTO. Boolean flags are converted back to
   * UEX-style {@code Integer} 0/1 storage.
   *
   * <p>{@link MaterialDto} is the admin-editable subset, and its only consumer ({@code
   * MaterialService.updateMaterial}) copies exactly that subset onto the managed row. Every other
   * {@link Material} column — the UEX, SC Wiki and P4K catalogue data and the timestamps — is owned
   * by the sync jobs, so this method maps nothing by default ({@code ignoreByDefault}) and names
   * each field it does carry. A new {@link Material} column therefore stays unset here until
   * someone decides it belongs to the admin edit.
   */
  @BeanMapping(ignoreByDefault = true)
  @Mapping(target = "id", source = "id")
  @Mapping(target = "version", source = "version")
  @Mapping(target = "name", source = "name")
  @Mapping(target = "type", source = "type")
  @Mapping(target = "quantityType", source = "quantityType")
  @Mapping(target = "description", source = "description")
  @Mapping(target = "refinedMaterial", source = "refinedMaterial")
  @Mapping(target = "category", source = "category")
  @Mapping(target = "isManualRawMaterial", source = "isManualRawMaterial")
  @Mapping(target = "isJobOrder", source = "isJobOrder")
  @Mapping(target = "isVisible", source = "isVisible")
  @Mapping(
      target = "isIllegal",
      expression = "java(dto.isIllegal() != null && dto.isIllegal() ? 1 : 0)")
  @Mapping(
      target = "isVolatileQt",
      expression = "java(dto.isVolatileQt() != null && dto.isVolatileQt() ? 1 : 0)")
  @Mapping(
      target = "isVolatileTime",
      expression = "java(dto.isVolatileTime() != null && dto.isVolatileTime() ? 1 : 0)")
  Material toEntity(MaterialDto dto);

  /**
   * Strips server-managed fields and body-supplied foreign-key references from a freshly mapped
   * entity for the POST/create flow, so a client cannot pre-set them (mass-assignment /
   * over-posting). {@code id} stays null so JPA performs an INSERT instead of a merge against an
   * existing row; {@code version} is left to the persistence provider; {@code refinedMaterial} and
   * {@code category} are not accepted through the request body here. A future create flow that
   * needs them should look the ids up via the service layer.
   *
   * <p>Declared as a static helper rather than a default mapping method so MapStruct does not
   * consider it a candidate for nested {@code MaterialDto -> Material} mappings inside other
   * mappers.
   */
  static Material stripServerManaged(Material entity) {
    if (entity != null) {
      entity.setId(null);
      entity.setVersion(null);
      entity.setRefinedMaterial(null);
      entity.setCategory(null);
    }
    return entity;
  }

  /** MapStruct default - converts a UEX-style 0/1 {@code Integer} to a nullable {@code Boolean}. */
  default Boolean mapIsIllegal(Integer value) {
    return value != null && value == 1;
  }

  /** MapStruct default - converts a {@code Boolean} back to a UEX-style 0/1 {@code Integer}. */
  @NotNull
  default Integer mapIsIllegal(Boolean value) {
    return value != null && value ? 1 : 0;
  }
}
