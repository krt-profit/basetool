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

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MaterialMapperTest {

  private final MaterialMapper mapper = new MaterialMapperImpl(new MaterialCategoryMapperImpl());

  @Test
  void toDto_shouldMapScalarFieldsAndConvertIntegerFlagsToBoolean() {
    UUID id = UUID.randomUUID();
    Material entity = new Material();
    entity.setId(id);
    entity.setName("Quantanium");
    entity.setType(MaterialType.RAW);
    entity.setQuantityType(QuantityType.SCU);
    entity.setDescription("Volatile mining commodity");
    entity.setIsIllegal(1);
    entity.setIsVolatileQt(1);
    entity.setIsVolatileTime(0);
    entity.setIsManualRawMaterial(true);
    entity.setIsJobOrder(false);
    entity.setIsVisible(false);
    entity.setVersion(4L);

    MaterialDto dto = mapper.toDto(entity);

    assertNotNull(dto);
    assertEquals(id, dto.id());
    assertEquals("Quantanium", dto.name());
    assertEquals("RAW", dto.type());
    assertEquals("SCU", dto.quantityType());
    assertEquals("Volatile mining commodity", dto.description());
    assertTrue(dto.isIllegal());
    assertTrue(dto.isVolatileQt());
    assertFalse(dto.isVolatileTime());
    assertTrue(dto.isManualRawMaterial());
    assertFalse(dto.isJobOrder());
    assertFalse(dto.isVisible(), "is_visible maps through to the DTO");
    assertEquals(4L, dto.version());
  }

  @Test
  void toDto_withNullFlagIntegers_shouldMapToFalse() {
    Material entity = new Material();
    entity.setName("Iron");
    entity.setType(MaterialType.RAW);
    entity.setIsIllegal(null);
    entity.setIsVolatileQt(null);
    entity.setIsVolatileTime(null);

    MaterialDto dto = mapper.toDto(entity);

    assertFalse(dto.isIllegal());
    assertFalse(dto.isVolatileQt());
    assertFalse(dto.isVolatileTime());
  }

  @Test
  void toDto_derivesIsManualEntryTrue_whenSourceSystemsManual() {
    Material entity = new Material();
    entity.setName("Admin Special");
    entity.setSourceSystems(MaterialSourceSystem.MANUAL);

    assertTrue(
        mapper.toDto(entity).isManualEntry(),
        "source_systems=MANUAL must surface as the derived isManualEntry=true");
  }

  @Test
  void toDto_derivesIsManualEntryFalse_whenSourceSystemsNotManual() {
    for (MaterialSourceSystem nonManual :
        new MaterialSourceSystem[] {
          MaterialSourceSystem.UEX_ONLY, MaterialSourceSystem.WIKI_ONLY, MaterialSourceSystem.BOTH
        }) {
      Material entity = new Material();
      entity.setName("Catalogue Row");
      entity.setSourceSystems(nonManual);

      assertFalse(
          mapper.toDto(entity).isManualEntry(),
          "source_systems=" + nonManual + " must surface as isManualEntry=false");
    }
  }

  @Test
  void toEntity_shouldConvertBooleanFlagsToInteger1or0() {
    UUID id = UUID.randomUUID();
    MaterialDto dto =
        new MaterialDto(
            id,
            "Laranite",
            "RAW",
            "SCU",
            null,
            null,
            null,
            true,
            false,
            true,
            false,
            true,
            null,
            false,
            1L);

    Material entity = mapper.toEntity(dto);

    assertNotNull(entity);
    assertEquals(id, entity.getId());
    assertEquals("Laranite", entity.getName());
    assertEquals(MaterialType.RAW, entity.getType());
    assertEquals(QuantityType.SCU, entity.getQuantityType());
    assertEquals(1, entity.getIsIllegal());
    assertEquals(0, entity.getIsVolatileQt());
    assertEquals(1, entity.getIsVolatileTime());
    assertEquals(Boolean.FALSE, entity.getIsManualRawMaterial());
    assertEquals(Boolean.TRUE, entity.getIsJobOrder());
    assertEquals(Boolean.FALSE, entity.getIsVisible(), "is_visible maps back to the entity");
    assertEquals(1L, entity.getVersion());
  }

  @Test
  void toEntity_withNullBooleanFlags_shouldMapToInteger0() {
    MaterialDto dto =
        new MaterialDto(
            UUID.randomUUID(),
            "Tin",
            "RAW",
            "SCU",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            1L);

    Material entity = mapper.toEntity(dto);

    assertEquals(0, entity.getIsIllegal());
    assertEquals(0, entity.getIsVolatileQt());
    assertEquals(0, entity.getIsVolatileTime());
  }

  @Test
  void mapIsIllegal_integerToBoolean() {
    assertTrue(mapper.mapIsIllegal(1));
    assertFalse(mapper.mapIsIllegal(0));
    assertFalse(mapper.mapIsIllegal((Integer) null));
    assertFalse(mapper.mapIsIllegal(42), "Only 1 maps to true");
  }

  @Test
  void mapIsIllegal_booleanToInteger() {
    assertEquals(1, mapper.mapIsIllegal(Boolean.TRUE));
    assertEquals(0, mapper.mapIsIllegal(Boolean.FALSE));
    assertEquals(0, mapper.mapIsIllegal((Boolean) null));
  }

  @Test
  void stripServerManaged_shouldClearIdVersionAndForeignKeyRefs() {
    Material entity = new Material();
    entity.setId(UUID.randomUUID());
    entity.setVersion(5L);
    entity.setName("Untainted");
    entity.setRefinedMaterial(new Material());
    entity.setCategory(new MaterialCategory());

    Material stripped = MaterialMapper.stripServerManaged(entity);

    assertSame(entity, stripped);
    assertNull(stripped.getId());
    assertNull(stripped.getVersion());
    assertNull(stripped.getRefinedMaterial());
    assertNull(stripped.getCategory());
    assertEquals("Untainted", stripped.getName());
  }

  @Test
  void stripServerManaged_shouldHandleNullEntity() {
    assertNull(MaterialMapper.stripServerManaged(null));
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toEntity(null));
  }
}
