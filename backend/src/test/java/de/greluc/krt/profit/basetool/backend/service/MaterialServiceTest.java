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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.MaterialCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {

  @Mock private MaterialRepository materialRepository;

  @Mock private MaterialPriceRepository materialPriceRepository;

  @Mock private MaterialCategoryRepository materialCategoryRepository;

  @InjectMocks private MaterialService materialService;

  @Test
  void findAllReference_returnsTheCompleteUnboundedProjection() {
    // Given
    MaterialReferenceDto ref =
        new MaterialReferenceDto(UUID.randomUUID(), "Agricium", QuantityType.SCU);
    when(materialRepository.findAllReference()).thenReturn(List.of(ref));

    // When
    List<MaterialReferenceDto> result = materialService.findAllReference();

    // Then
    assertEquals(List.of(ref), result);
    verify(materialRepository).findAllReference();
  }

  @Test
  void searchPicker_escapesTheFragmentAndForwardsTheFilterFlags() {
    // Given
    Material agricium = new Material();
    agricium.setName("Agricium 100%");
    PageRequest pageable = PageRequest.of(0, 25);
    when(materialRepository.searchPicker("100\\%", true, MaterialType.RAW, false, pageable))
        .thenReturn(new PageImpl<>(List.of(agricium)));

    // When
    Page<Material> result = materialService.searchPicker("100%", true, false, pageable);

    // Then
    assertEquals(List.of(agricium), result.getContent());
    verify(materialRepository).searchPicker("100\\%", true, MaterialType.RAW, false, pageable);
  }

  @Test
  void searchPicker_passesNullThroughAsTheNoFilterMarkerAndRawOnlyFlag() {
    // Given
    PageRequest pageable = PageRequest.of(0, 25);
    when(materialRepository.searchPicker(null, false, MaterialType.RAW, true, pageable))
        .thenReturn(new PageImpl<>(List.of()));

    // When
    materialService.searchPicker(null, false, true, pageable);

    // Then
    verify(materialRepository).searchPicker(null, false, MaterialType.RAW, true, pageable);
  }

  @Test
  void getMaterialPriceOverview_ShouldReturnPageOfOverviews() {
    // Arrange
    String nameFilter = "Gold";
    PageRequest pageRequest = PageRequest.of(0, 10);
    MaterialPriceOverviewDto dto =
        new MaterialPriceOverviewDto(
            UUID.randomUUID(),
            "Gold",
            new de.greluc.krt.profit.basetool.backend.model.dto.MaterialCategoryDto(
                UUID.randomUUID(), "Mineral", 0L),
            false,
            false,
            false,
            new BigDecimal("5.0"),
            new BigDecimal("7.0"));
    Page<MaterialPriceOverviewDto> expectedPage = new PageImpl<>(List.of(dto));

    when(materialRepository.getMaterialPriceOverview(nameFilter, pageRequest))
        .thenReturn(expectedPage);

    // Act
    Page<MaterialPriceOverviewDto> result =
        materialService.getMaterialPriceOverview(nameFilter, pageRequest);

    // Assert
    assertEquals(1, result.getTotalElements());
    assertEquals("Gold", result.getContent().get(0).name());
    assertEquals(new BigDecimal("5.0"), result.getContent().get(0).minPriceBuy());
  }

  @Test
  void getMaterialPrices_ShouldReturnPageOfPrices() {
    // Arrange
    UUID materialId = UUID.randomUUID();
    PageRequest pageRequest = PageRequest.of(0, 10);
    MaterialPriceDto dto =
        new MaterialPriceDto(
            UUID.randomUUID(),
            "Area18",
            new BigDecimal("5.0"),
            new BigDecimal("7.0"),
            100,
            200,
            true,
            true);
    Page<MaterialPriceDto> expectedPage = new PageImpl<>(List.of(dto));

    when(materialPriceRepository.findPricesByMaterialId(materialId, pageRequest))
        .thenReturn(expectedPage);

    // Act
    Page<MaterialPriceDto> result = materialService.getMaterialPrices(materialId, pageRequest);

    // Assert
    assertEquals(1, result.getTotalElements());
    assertEquals("Area18", result.getContent().get(0).terminalName());
    assertEquals(new BigDecimal("5.0"), result.getContent().get(0).priceBuy());
  }

  @Test
  void getMatrixItems_passesFilterArgumentsThrough() {
    // Arrange — a concrete selection must reach the repository verbatim (ADR-0105, REQ-UI-014).
    PageRequest pageRequest = PageRequest.of(0, 100000);
    Page<MaterialMatrixItemDto> expected = new PageImpl<>(List.of());
    when(materialPriceRepository.findMatrixItems(
            List.of("Aluminum"), List.of("Stanton"), Boolean.TRUE, null, pageRequest))
        .thenReturn(expected);

    // Act
    Page<MaterialMatrixItemDto> result =
        materialService.getMatrixItems(
            List.of("Aluminum"), List.of("Stanton"), Boolean.TRUE, null, pageRequest);

    // Assert
    assertSame(expected, result);
    verify(materialPriceRepository)
        .findMatrixItems(List.of("Aluminum"), List.of("Stanton"), Boolean.TRUE, null, pageRequest);
  }

  @Test
  void getMatrixItems_normalisesEmptyCollectionsToNull() {
    // Arrange — an empty IN selection must become null so the repository's `:param IS NULL OR
    // x IN :param` idiom never emits an invalid `IN ()` (ADR-0105).
    PageRequest pageRequest = PageRequest.of(0, 100000);
    when(materialPriceRepository.findMatrixItems(null, null, null, null, pageRequest))
        .thenReturn(new PageImpl<>(List.of()));

    // Act
    materialService.getMatrixItems(List.of(), List.of(), null, null, pageRequest);

    // Assert — both empty lists were collapsed to null; the boolean nulls pass through unchanged.
    verify(materialPriceRepository).findMatrixItems(null, null, null, null, pageRequest);
  }

  @Test
  void getAllJobOrderMaterials_ShouldReturnOnlyJobOrderMaterials() {
    // Given
    Material mat1 = new Material();
    mat1.setId(UUID.randomUUID());
    mat1.setName("Agricium");
    mat1.setIsJobOrder(true);

    Material mat2 = new Material();
    mat2.setId(UUID.randomUUID());
    mat2.setName("Bexalite");
    mat2.setIsJobOrder(true);

    when(materialRepository.findAllByIsJobOrderTrueAndIsVisibleTrueOrderByNameAsc())
        .thenReturn(List.of(mat1, mat2));

    // When
    List<Material> result = materialService.getAllJobOrderMaterials();

    // Then
    assertEquals(2, result.size());
    assertEquals("Agricium", result.get(0).getName());
    assertEquals("Bexalite", result.get(1).getName());
    verify(materialRepository).findAllByIsJobOrderTrueAndIsVisibleTrueOrderByNameAsc();
  }

  @Test
  void getVisibleMaterials_delegatesToVisibleOnlyRepositoryQuery() {
    // Given
    PageRequest pageRequest = PageRequest.of(0, 10);
    Material visible = new Material();
    visible.setId(UUID.randomUUID());
    visible.setName("Agricium");
    Page<Material> expected = new PageImpl<>(List.of(visible));
    when(materialRepository.findByIsVisibleTrue(pageRequest)).thenReturn(expected);

    // When
    Page<Material> result = materialService.getVisibleMaterials(pageRequest);

    // Then — the trading catalog path must go through the is_visible-filtered query, never findAll
    assertEquals(1, result.getTotalElements());
    assertEquals("Agricium", result.getContent().get(0).getName());
    verify(materialRepository).findByIsVisibleTrue(pageRequest);
    verify(materialRepository, never()).findAll(any(PageRequest.class));
  }

  // ─── updateMaterial visibility toggle ──────────────────────────────────

  @Test
  void updateMaterial_appliesVisibilityToggle() {
    // Given an existing visible material and an update flipping it to hidden
    UUID id = UUID.randomUUID();
    Material existing = new Material();
    existing.setId(id);
    existing.setName("Bluemoon Fungus");
    existing.setType(MaterialType.RAW);
    existing.setIsVisible(true);
    existing.setVersion(3L);

    Material update = new Material();
    update.setName("Bluemoon Fungus");
    update.setType(MaterialType.RAW);
    update.setIsVisible(false);
    update.setVersion(3L);

    when(materialRepository.findById(id)).thenReturn(Optional.of(existing));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    Material saved = materialService.updateMaterial(id, update);

    // Then
    assertEquals(Boolean.FALSE, saved.getIsVisible(), "admin can hide a reviewed commodity");
  }

  @Test
  void updateMaterial_nullVisibility_keepsExistingValue() {
    // Given an existing hidden material and an update DTO that omits isVisible (null)
    UUID id = UUID.randomUUID();
    Material existing = new Material();
    existing.setId(id);
    existing.setName("Ace Interceptor Helmet");
    existing.setType(MaterialType.NO_REFINE);
    existing.setIsVisible(false);
    existing.setVersion(1L);

    Material update = new Material();
    update.setName("Ace Interceptor Helmet");
    update.setType(MaterialType.NO_REFINE);
    update.setIsVisible(null); // unrelated edit (e.g. category) must not null the NOT NULL column
    update.setVersion(1L);

    when(materialRepository.findById(id)).thenReturn(Optional.of(existing));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    Material saved = materialService.updateMaterial(id, update);

    // Then — the null-guard preserves the stored visibility
    assertEquals(Boolean.FALSE, saved.getIsVisible(), "null isVisible must not overwrite the row");
  }

  // ─── createMaterial ─────────────────────────────────────────────────────

  @Test
  void createMaterial_setsSourceSystemsManual_andPersists() {
    // Given a minimal payload without category/refined references
    MaterialCreateDto dto =
        new MaterialCreateDto(
            "Raw Ouratite", "RAW", "SCU", "manual", null, null, true, false, false, false, false);
    when(materialRepository.save(any(Material.class)))
        .thenAnswer(
            inv -> {
              Material m = inv.getArgument(0);
              m.setId(UUID.randomUUID());
              return m;
            });

    // When
    Material saved = materialService.createMaterial(dto);

    // Then — server-side stamped provenance, scalars copied over verbatim, no FK lookup attempted
    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    Material captured = cap.getValue();
    assertEquals("Raw Ouratite", captured.getName());
    assertEquals(MaterialType.RAW, captured.getType());
    assertEquals(QuantityType.SCU, captured.getQuantityType());
    assertEquals("manual", captured.getDescription());
    assertEquals(Boolean.TRUE, captured.getIsManualRawMaterial());
    assertEquals(Boolean.FALSE, captured.getIsJobOrder());
    assertEquals(
        MaterialSourceSystem.MANUAL,
        captured.getSourceSystems(),
        "Server stamps source_systems=MANUAL (R9 Step 1, surfaced as derived isManualEntry)");
    assertNull(captured.getRefinedMaterial());
    assertNull(captured.getCategory());
    assertNull(captured.getIdCommodity(), "Manual entries carry no UEX commodity id");
    assertNotNull(saved.getId());
  }

  @Test
  void createMaterial_resolvesRefinedAndCategoryByIds() {
    // Given a refined target + a category that both exist in the database
    UUID refinedId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    Material refinedTarget = new Material();
    refinedTarget.setId(refinedId);
    refinedTarget.setName("Ouratite");
    MaterialCategory categoryRow = new MaterialCategory();
    categoryRow.setId(categoryId);
    categoryRow.setName("Mineralien");

    MaterialCreateDto dto =
        new MaterialCreateDto(
            "Raw Ouratite",
            "RAW",
            "SCU",
            null,
            refinedId,
            categoryId,
            true,
            false,
            false,
            false,
            false);

    when(materialRepository.findById(refinedId)).thenReturn(Optional.of(refinedTarget));
    when(materialCategoryRepository.findById(categoryId)).thenReturn(Optional.of(categoryRow));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    // When
    materialService.createMaterial(dto);

    // Then — the captured entity points at the looked-up rows, not new ones
    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertSame(refinedTarget, cap.getValue().getRefinedMaterial());
    assertSame(categoryRow, cap.getValue().getCategory());
  }

  @Test
  void createMaterial_unknownType_throwsBadRequest() {
    MaterialCreateDto dto =
        new MaterialCreateDto(
            "X", "NOT_A_TYPE", "SCU", null, null, null, null, null, null, null, null);

    assertThrows(BadRequestException.class, () -> materialService.createMaterial(dto));
    verify(materialRepository, never()).save(any());
  }

  @Test
  void createMaterial_unknownQuantityType_throwsBadRequest() {
    MaterialCreateDto dto =
        new MaterialCreateDto(
            "X", "RAW", "NOT_A_UNIT", null, null, null, null, null, null, null, null);

    assertThrows(BadRequestException.class, () -> materialService.createMaterial(dto));
    verify(materialRepository, never()).save(any());
  }

  @Test
  void createMaterial_refinedOnNonRawMaterial_throwsBadRequest() {
    // type=NO_REFINE, isManualRawMaterial=false, but caller sets refinedMaterialId → reject
    MaterialCreateDto dto =
        new MaterialCreateDto(
            "X", "NO_REFINE", "SCU", null, UUID.randomUUID(), null, false, null, null, null, null);

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> materialService.createMaterial(dto));
    assertTrue(
        ex.getMessage().toLowerCase().contains("refined"),
        "Message should mention refined-material constraint, was: " + ex.getMessage());
    verify(materialRepository, never()).save(any());
    verify(materialRepository, never()).findById(any());
  }

  @Test
  void createMaterial_refinedAllowed_whenIsManualRawMaterialTrue_evenIfTypeIsNotRaw() {
    // type=NO_REFINE but isManualRawMaterial=true → refinedMaterialId must be honoured
    UUID refinedId = UUID.randomUUID();
    Material refinedTarget = new Material();
    refinedTarget.setId(refinedId);

    MaterialCreateDto dto =
        new MaterialCreateDto(
            "X", "NO_REFINE", "SCU", null, refinedId, null, true, null, null, null, null);

    when(materialRepository.findById(refinedId)).thenReturn(Optional.of(refinedTarget));
    when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));

    materialService.createMaterial(dto);

    ArgumentCaptor<Material> cap = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(cap.capture());
    assertSame(refinedTarget, cap.getValue().getRefinedMaterial());
  }

  @Test
  void createMaterial_unknownRefinedId_throwsNotFound() {
    UUID refinedId = UUID.randomUUID();
    MaterialCreateDto dto =
        new MaterialCreateDto(
            "X", "RAW", "SCU", null, refinedId, null, true, null, null, null, null);

    when(materialRepository.findById(refinedId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> materialService.createMaterial(dto));
    verify(materialRepository, never()).save(any());
  }

  @Test
  void createMaterial_unknownCategoryId_throwsNotFound() {
    UUID categoryId = UUID.randomUUID();
    MaterialCreateDto dto =
        new MaterialCreateDto(
            "X", "RAW", "SCU", null, null, categoryId, true, null, null, null, null);

    when(materialCategoryRepository.findById(categoryId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> materialService.createMaterial(dto));
    verify(materialRepository, never()).save(any());
  }
}
