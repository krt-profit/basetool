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
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.mapper.RefiningMethodMapper;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.service.RefiningMethodService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Pure-method unit tests for {@link RefiningMethodController}. */
@ExtendWith(MockitoExtension.class)
class RefiningMethodControllerTest {

  @Mock private RefiningMethodService service;
  @Mock private RefiningMethodMapper mapper;

  @InjectMocks private RefiningMethodController controller;

  @Test
  void getAll_wrapsServicePageIntoPageResponse() {
    RefiningMethodDto dto =
        new RefiningMethodDto(UUID.randomUUID(), "Cormack", null, "CORMACK", 95, 70, 30);
    when(service.getAllRefiningMethods(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(dto)));

    PageResponse<RefiningMethodDto> resp = controller.getAllRefiningMethods(null, null, null);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getById_returnsTheServicesReadModel() {
    UUID id = UUID.randomUUID();
    RefiningMethodDto dto = new RefiningMethodDto(id, "x", null, "X", 50, 50, 50);
    when(service.getRefiningMethod(id)).thenReturn(dto);

    RefiningMethodDto result = controller.getRefiningMethod(id);

    assertSame(dto, result);
  }

  @Test
  void create_roundTripsViaMapper() {
    RefiningMethodDto request = new RefiningMethodDto(null, "Dinyx", null, "DINYX", 60, 60, 60);
    RefiningMethod entity = new RefiningMethod();
    RefiningMethod persisted = new RefiningMethod();
    RefiningMethodDto response =
        new RefiningMethodDto(UUID.randomUUID(), "Dinyx", null, "DINYX", 60, 60, 60);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.createRefiningMethod(entity)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    RefiningMethodDto result = controller.createRefiningMethod(request);

    assertSame(response, result);
    verify(service).createRefiningMethod(entity);
  }

  @Test
  void update_passesIdAndMappedEntityToService() {
    UUID id = UUID.randomUUID();
    RefiningMethodDto request = new RefiningMethodDto(id, "Renamed", null, "REN", 40, 30, 100);
    RefiningMethod entity = new RefiningMethod();
    RefiningMethod updated = new RefiningMethod();
    RefiningMethodDto response = new RefiningMethodDto(id, "Renamed", null, "REN", 40, 30, 100);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.updateRefiningMethod(id, entity)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    RefiningMethodDto result = controller.updateRefiningMethod(id, request);

    assertSame(response, result);
    verify(service).updateRefiningMethod(id, entity);
  }

  @Test
  void delete_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteRefiningMethod(id);

    verify(service).deleteRefiningMethod(id);
    verifyNoMoreInteractions(service, mapper);
  }
}
