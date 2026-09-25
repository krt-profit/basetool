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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.mapper.MaterialCategoryMapper;
import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.service.MaterialCategoryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-method unit tests for {@link MaterialCategoryController}. Coverage before this file was 0%
 * on every endpoint. Asserts the controller-to- service delegation and the mapper round-trip on the
 * write endpoints.
 */
@ExtendWith(MockitoExtension.class)
class MaterialCategoryControllerTest {

  @Mock private MaterialCategoryService service;
  @Mock private MaterialCategoryMapper mapper;

  @InjectMocks private MaterialCategoryController controller;

  @Test
  void getAll_returnsServiceListMappedToDtos() {
    MaterialCategory minerals = new MaterialCategory();
    MaterialCategory gases = new MaterialCategory();
    MaterialCategoryDto mineralsDto = new MaterialCategoryDto(UUID.randomUUID(), "Mineral", 1L);
    MaterialCategoryDto gasesDto = new MaterialCategoryDto(UUID.randomUUID(), "Gas", 1L);

    when(service.findAll()).thenReturn(List.of(minerals, gases));
    when(mapper.toDto(minerals)).thenReturn(mineralsDto);
    when(mapper.toDto(gases)).thenReturn(gasesDto);

    List<MaterialCategoryDto> result = controller.getAll();

    assertEquals(2, result.size());
    assertSame(mineralsDto, result.get(0));
    assertSame(gasesDto, result.get(1));
  }

  @Test
  void getById_delegatesAndMaps() {
    UUID id = UUID.randomUUID();
    MaterialCategory entity = new MaterialCategory();
    MaterialCategoryDto dto = new MaterialCategoryDto(id, "Mineral", 1L);

    when(service.findById(id)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(dto);

    MaterialCategoryDto result = controller.getById(id);

    assertSame(dto, result);
  }

  @Test
  void create_roundTripsDtoToEntityViaMapperAndBack() {
    MaterialCategoryDto request = new MaterialCategoryDto(null, "NewCat", null);
    MaterialCategory entity = new MaterialCategory();
    MaterialCategory saved = new MaterialCategory();
    MaterialCategoryDto persisted = new MaterialCategoryDto(UUID.randomUUID(), "NewCat", 1L);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.create(entity)).thenReturn(saved);
    when(mapper.toDto(saved)).thenReturn(persisted);

    MaterialCategoryDto result = controller.create(request);

    assertSame(persisted, result);
    verify(service).create(entity);
  }

  @Test
  void update_passesIdAndMappedEntityToService() {
    UUID id = UUID.randomUUID();
    MaterialCategoryDto request = new MaterialCategoryDto(id, "Renamed", 3L);
    MaterialCategory entity = new MaterialCategory();
    MaterialCategory updated = new MaterialCategory();
    MaterialCategoryDto persisted = new MaterialCategoryDto(id, "Renamed", 4L);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.update(id, entity)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(persisted);

    MaterialCategoryDto result = controller.update(id, request);

    assertSame(persisted, result);
    verify(service).update(id, entity);
  }

  @Test
  void delete_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.delete(id);

    verify(service).delete(id);
    verifyNoMoreInteractions(service, mapper);
  }
}
