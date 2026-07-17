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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.MaterialService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MaterialControllerTest {

  @Mock private MaterialService materialService;

  @Mock private MaterialMapper materialMapper;

  @InjectMocks private MaterialController materialController;

  @Test
  void getJobOrderMaterials_ShouldReturnOnlyJobOrderMaterials() {
    // Given
    Material mat = new Material();
    mat.setId(UUID.randomUUID());
    mat.setName("Agricium");
    mat.setType(MaterialType.RAW);
    mat.setIsJobOrder(true);

    MaterialDto dto =
        new MaterialDto(
            mat.getId(),
            "Agricium",
            "RAW",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            false,
            true,
            0L);

    when(materialService.getAllJobOrderMaterials()).thenReturn(List.of(mat));
    when(materialMapper.toDto(mat)).thenReturn(dto);

    // When
    List<MaterialDto> result = materialController.getJobOrderMaterials();

    // Then
    assertEquals(1, result.size());
    assertEquals("Agricium", result.get(0).name());
    assertEquals(true, result.get(0).isJobOrder());
  }

  @Test
  void getAllMaterials_byDefault_returnsVisibleOnly() {
    // Given a trading caller (no includeHidden)
    Material mat = new Material();
    mat.setId(UUID.randomUUID());
    mat.setName("Agricium");
    when(materialService.getVisibleMaterials(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(mat)));
    when(materialMapper.toDto(mat)).thenReturn(minimalDto(mat.getId(), "Agricium", true));

    // When
    PageResponse<MaterialDto> result =
        materialController.getAllMaterials(false, false, 0, 10, null);

    // Then — the leak fix: trading callers go through the is_visible-filtered query
    assertEquals(1, result.content().size());
    verify(materialService).getVisibleMaterials(any(Pageable.class));
    verify(materialService, never()).getAllMaterials(any(Pageable.class));
  }

  @Test
  void getAllMaterials_includeHidden_returnsFullCatalogForAdmin() {
    // Given the admin catalog (includeHidden=true)
    Material hidden = new Material();
    hidden.setId(UUID.randomUUID());
    hidden.setName("Ace Interceptor Helmet");
    when(materialService.getAllMaterials(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(hidden)));
    when(materialMapper.toDto(hidden))
        .thenReturn(minimalDto(hidden.getId(), "Ace Interceptor Helmet", false));

    // When
    PageResponse<MaterialDto> result = materialController.getAllMaterials(false, true, 0, 10, null);

    // Then — admins still see hidden rows so they can review/unhide them (§4.3)
    assertEquals(1, result.content().size());
    verify(materialService).getAllMaterials(any(Pageable.class));
    verify(materialService, never()).getVisibleMaterials(any(Pageable.class));
  }

  @Test
  void getMaterialMatrixItems_relaysFilterParamsToService() {
    // Given the four optional server-side filters (ADR-0105, REQ-UI-014)
    when(materialService.getMatrixItems(
            eq(List.of("Aluminum")),
            eq(List.of("Stanton")),
            eq(Boolean.TRUE),
            eq(null),
            any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    // When
    PageResponse<MaterialMatrixItemDto> result =
        materialController.getMaterialMatrixItems(
            0, 100000, null, List.of("Aluminum"), List.of("Stanton"), true, null);

    // Then — the controller is a thin pass-through: each filter reaches the service verbatim.
    assertEquals(0, result.content().size());
    verify(materialService)
        .getMatrixItems(
            eq(List.of("Aluminum")),
            eq(List.of("Stanton")),
            eq(Boolean.TRUE),
            eq(null),
            any(Pageable.class));
  }

  @Test
  void getMaterialMatrixItems_withoutFilters_passesNullsForEveryDimension() {
    // Given a bare /matrix call — every filter absent means the full matrix
    when(materialService.getMatrixItems(
            eq(null), eq(null), eq(null), eq(null), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    // When
    PageResponse<MaterialMatrixItemDto> result =
        materialController.getMaterialMatrixItems(null, null, null, null, null, null, null);

    // Then
    assertEquals(0, result.content().size());
    verify(materialService)
        .getMatrixItems(eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
  }

  private static MaterialDto minimalDto(UUID id, String name, boolean visible) {
    return new MaterialDto(
        id, name, "RAW", null, null, null, null, null, null, null, null, null, null, visible, 0L);
  }

  @Test
  void createMaterial_delegatesToService_andReturnsMappedDto() {
    // Given a minimal create payload (POST /api/v1/materials)
    MaterialCreateDto request =
        new MaterialCreateDto(
            "Raw Ouratite", "RAW", "SCU", "manual", null, null, true, false, false, false, false);
    Material persisted = new Material();
    persisted.setId(UUID.randomUUID());
    persisted.setName("Raw Ouratite");

    MaterialDto responseDto =
        new MaterialDto(
            persisted.getId(),
            "Raw Ouratite",
            "RAW",
            "SCU",
            "manual",
            null,
            null,
            false,
            false,
            false,
            true,
            false,
            true,
            true,
            0L);

    when(materialService.createMaterial(request)).thenReturn(persisted);
    when(materialMapper.toDto(persisted)).thenReturn(responseDto);

    // When
    MaterialDto result = materialController.createMaterial(request);

    // Then — the controller is a thin pass-through; the service does the heavy lifting
    assertEquals(responseDto, result);
    assertEquals(true, result.isManualEntry(), "Server-stamped audit flag is propagated");
  }
}
