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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MaterialCategoryService;
import de.greluc.krt.profit.basetool.backend.catalogue.web.MaterialCategoryController;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Unit tests for {@link MaterialCategoryController}: service delegation of the reads, and no write
 * endpoint (REQ-P4K-025).
 */
@ExtendWith(MockitoExtension.class)
class MaterialCategoryControllerTest {

  @Mock private MaterialCategoryService service;

  @InjectMocks private MaterialCategoryController controller;

  @Test
  void getAll_returnsServiceListMappedToDtos() {
    MaterialCategoryDto mineralsDto = new MaterialCategoryDto(UUID.randomUUID(), "Mineral", 1L);
    MaterialCategoryDto gasesDto = new MaterialCategoryDto(UUID.randomUUID(), "Gas", 1L);

    when(service.findAll()).thenReturn(List.of(mineralsDto, gasesDto));

    List<MaterialCategoryDto> result = controller.getAll();

    assertEquals(2, result.size());
    assertSame(mineralsDto, result.get(0));
    assertSame(gasesDto, result.get(1));
  }

  @Test
  void getById_returnsTheServicesReadModel() {
    UUID id = UUID.randomUUID();
    MaterialCategoryDto dto = new MaterialCategoryDto(id, "Mineral", 1L);

    when(service.findById(id)).thenReturn(dto);

    MaterialCategoryDto result = controller.getById(id);

    assertSame(dto, result);
  }

  @Test
  void noEndpointCreatesRenamesOrDeletesACategory() {
    assertTrue(
        Arrays.stream(MaterialCategoryController.class.getDeclaredMethods())
            .noneMatch(m ->
                m.isAnnotationPresent(PostMapping.class)
                    || m.isAnnotationPresent(PutMapping.class)
                    || m.isAnnotationPresent(PatchMapping.class)
                    || m.isAnnotationPresent(DeleteMapping.class)),
        "the P4K import owns the categories (REQ-P4K-025)");
  }
}
