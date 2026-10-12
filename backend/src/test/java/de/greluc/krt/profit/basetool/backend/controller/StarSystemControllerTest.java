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

import de.greluc.krt.profit.basetool.backend.catalogue.api.StarSystemDto;
import de.greluc.krt.profit.basetool.backend.mapper.StarSystemMapper;
import de.greluc.krt.profit.basetool.backend.model.StarSystem;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.StarSystemService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Pure-method unit tests for {@link StarSystemController}. */
@ExtendWith(MockitoExtension.class)
class StarSystemControllerTest {

  @Mock private StarSystemService service;
  @Mock private StarSystemMapper mapper;

  @InjectMocks private StarSystemController controller;

  @Test
  void getAll_wrapsServicePageIntoPageResponse() {
    StarSystemDto dto =
        new StarSystemDto(UUID.randomUUID(), 1, "Stanton", null, true, null, null, null, 1L);
    when(service.getAllStarSystems(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(dto)));

    PageResponse<StarSystemDto> resp = controller.getAllStarSystems(null, null, null);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void getById_returnsTheServicesReadModel() {
    UUID id = UUID.randomUUID();
    StarSystemDto dto = new StarSystemDto(id, 1, "Stanton", null, true, null, null, null, 1L);
    when(service.getStarSystem(id)).thenReturn(dto);

    StarSystemDto result = controller.getStarSystem(id);

    assertSame(dto, result);
  }

  @Test
  void create_roundTripsViaMapper() {
    StarSystemDto request =
        new StarSystemDto(null, 99, "Pyro", "Lawless", false, null, null, null, null);
    StarSystem entity = new StarSystem();
    StarSystem persisted = new StarSystem();
    StarSystemDto response =
        new StarSystemDto(UUID.randomUUID(), 99, "Pyro", "Lawless", false, null, null, null, 1L);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.createStarSystem(entity)).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    StarSystemDto result = controller.createStarSystem(request);

    assertSame(response, result);
    verify(service).createStarSystem(entity);
  }

  @Test
  void update_passesIdAndMappedEntityToService() {
    UUID id = UUID.randomUUID();
    StarSystemDto request = new StarSystemDto(id, 1, "Renamed", null, true, null, null, null, 4L);
    StarSystem entity = new StarSystem();
    StarSystem updated = new StarSystem();
    StarSystemDto response = new StarSystemDto(id, 1, "Renamed", null, true, null, null, null, 5L);

    when(mapper.toEntity(request)).thenReturn(entity);
    when(service.updateStarSystem(id, entity)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    StarSystemDto result = controller.updateStarSystem(id, request);

    assertSame(response, result);
    verify(service).updateStarSystem(id, entity);
  }

  @Test
  void delete_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteStarSystem(id);

    verify(service).deleteStarSystem(id);
    verifyNoMoreInteractions(service, mapper);
  }
}
