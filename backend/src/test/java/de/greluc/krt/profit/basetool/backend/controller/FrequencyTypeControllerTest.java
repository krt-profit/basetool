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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.mapper.FrequencyTypeMapper;
import de.greluc.krt.profit.basetool.backend.model.FrequencyType;
import de.greluc.krt.profit.basetool.backend.model.dto.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.FrequencyTypeService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Pure-method unit tests for {@link FrequencyTypeController}. The Spring-MVC binding (path params,
 * `@PreAuthorize`, JSON serialisation) is covered by the integration suite; here we focus on:
 *
 * <ul>
 *   <li>Pagination wrapper: the {@link PageResponse} is assembled from the Spring {@link Page}
 *       correctly (page / size / total / sort).
 *   <li>Service-layer delegation: every CRUD action delegates with the expected arguments and the
 *       {@link FrequencyTypeMapper} round-trips in both directions.
 *   <li>Activate / reorder endpoints are void-returning and do not silently swallow the id list.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FrequencyTypeControllerTest {

  @Mock private FrequencyTypeService service;
  @Mock private FrequencyTypeMapper mapper;

  @InjectMocks private FrequencyTypeController controller;

  @Test
  void getAll_buildsPageableAndMapsContent() {
    FrequencyType combat = new FrequencyType();
    combat.setName("Combat");
    FrequencyTypeDto dto = new FrequencyTypeDto(UUID.randomUUID(), "Combat", "desc", true, 0, 1L);
    Page<FrequencyType> page = new PageImpl<>(List.of(combat));

    when(service.getAllFrequencyTypes(eq(true), any(Pageable.class))).thenReturn(page);
    when(mapper.toDto(combat)).thenReturn(dto);

    PageResponse<FrequencyTypeDto> response =
        controller.getAllFrequencyTypes(null, null, null, true);

    assertEquals(1, response.totalElements());
    assertEquals(1, response.content().size());
    assertSame(dto, response.content().getFirst());
    verify(service).getAllFrequencyTypes(eq(true), any(Pageable.class));
  }

  @Test
  void getAll_withoutActiveFilter_passesNullToService() {
    when(service.getAllFrequencyTypes(eq(null), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllFrequencyTypes(null, null, null, null);

    verify(service).getAllFrequencyTypes(eq(null), any(Pageable.class));
  }

  @Test
  void getAll_appliesPageParametersToPageable() {
    when(service.getAllFrequencyTypes(any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllFrequencyTypes(2, 25, "name,asc", null);

    ArgumentCaptor<Pageable> pageableCap = ArgumentCaptor.forClass(Pageable.class);
    verify(service).getAllFrequencyTypes(any(), pageableCap.capture());
    Pageable pageable = pageableCap.getValue();
    assertEquals(2, pageable.getPageNumber());
    assertEquals(25, pageable.getPageSize());
  }

  @Test
  void getById_delegatesAndMaps() {
    UUID id = UUID.randomUUID();
    FrequencyType entity = new FrequencyType();
    FrequencyTypeDto dto = new FrequencyTypeDto(id, "X", null, true, 0, 1L);
    when(service.getFrequencyType(id)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(dto);

    FrequencyTypeDto result = controller.getFrequencyType(id);

    assertSame(dto, result);
  }

  @Test
  void create_roundTripsDtoToEntityViaMapperAndBack() {
    FrequencyTypeDto request = new FrequencyTypeDto(null, "Recon", "scout chatter", true, 5, null);
    FrequencyType entity = new FrequencyType();
    FrequencyType persisted = new FrequencyType();
    FrequencyTypeDto response =
        new FrequencyTypeDto(UUID.randomUUID(), "Recon", "scout chatter", true, 5, 1L);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.createFrequencyType(entity)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    FrequencyTypeDto result = controller.createFrequencyType(request);

    assertSame(response, result);
    verify(mapper).toEntity(request);
    verify(service).createFrequencyType(entity);
    verify(mapper).toDto(persisted);
  }

  @Test
  void update_passesIdAndMappedEntityToService() {
    UUID id = UUID.randomUUID();
    FrequencyTypeDto request = new FrequencyTypeDto(id, "Renamed", null, true, 0, 4L);
    FrequencyType entity = new FrequencyType();
    FrequencyType persisted = new FrequencyType();
    FrequencyTypeDto response = new FrequencyTypeDto(id, "Renamed", null, true, 0, 5L);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.updateFrequencyType(id, entity)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    FrequencyTypeDto result = controller.updateFrequencyType(id, request);

    assertSame(response, result);
    verify(service).updateFrequencyType(id, entity);
  }

  @Test
  void delete_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteFrequencyType(id);

    verify(service).deleteFrequencyType(id);
    verifyNoMoreInteractions(service, mapper);
  }

  @Test
  void activate_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.activateFrequencyType(id);

    verify(service).activateFrequencyType(id);
    verifyNoMoreInteractions(service, mapper);
  }

  @Test
  void reorder_passesIdListVerbatim() {
    List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    controller.reorderFrequencyTypes(ids);

    verify(service).reorderFrequencyTypes(ids);
  }
}
