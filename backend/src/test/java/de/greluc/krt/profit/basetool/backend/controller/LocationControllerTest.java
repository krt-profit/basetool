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

import de.greluc.krt.profit.basetool.backend.mapper.LocationMapper;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.LocationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link LocationController}.
 *
 * <ul>
 *   <li>Create passes through {@link LocationMapper#stripServerManaged(Location)}, so {@code id} /
 *       {@code version} cannot be mass-assigned.
 *   <li>The lookup endpoint returns the service's reference DTOs unmapped.
 *   <li>{@link PageResponse} wrapping honours the {@code includeHidden} flag.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LocationControllerTest {

  @Mock private LocationService service;
  @Mock private LocationMapper mapper;

  @InjectMocks private LocationController controller;

  @Test
  void getAll_passesIncludeHiddenFlagToService() {
    when(service.getAllLocations(any(Pageable.class), eq(true)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllLocations(null, null, null, true);

    verify(service).getAllLocations(any(Pageable.class), eq(true));
  }

  @Test
  void getAll_defaultsIncludeHiddenToFalse() {
    when(service.getAllLocations(any(Pageable.class), eq(false)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.getAllLocations(null, null, null, false);

    verify(service).getAllLocations(any(Pageable.class), eq(false));
  }

  @Test
  void getAll_wrapsServicePageIntoPageResponseAndMapsContent() {
    Location entity = new Location();
    LocationDto dto = new LocationDto(UUID.randomUUID(), "Lorville", null, false, false, 1L);
    when(service.getAllLocations(any(Pageable.class), eq(false)))
        .thenReturn(new PageImpl<>(List.of(entity)));
    when(mapper.toDto(entity)).thenReturn(dto);

    PageResponse<LocationDto> resp = controller.getAllLocations(0, 50, null, false);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  @Test
  void lookup_returnsReferenceListDirectlyFromService_noMapper() {
    List<LocationReferenceDto> refs =
        List.of(
            new LocationReferenceDto(UUID.randomUUID(), "Lorville"),
            new LocationReferenceDto(UUID.randomUUID(), "Area18"));
    when(service.findAllReference()).thenReturn(refs);

    List<LocationReferenceDto> result = controller.lookupLocations();

    assertSame(refs, result);
    verifyNoInteractions(mapper);
  }

  @Test
  void getById_delegatesAndMaps() {
    UUID id = UUID.randomUUID();
    Location entity = new Location();
    LocationDto dto = new LocationDto(id, "x", null, false, false, 1L);
    when(service.getLocation(id)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(dto);

    LocationDto result = controller.getLocation(id);

    assertSame(dto, result);
  }

  @Test
  void getRefineryLocations_mapsEachEntityToDto() {
    Location loc1 = new Location();
    Location loc2 = new Location();
    LocationDto dto1 = new LocationDto(UUID.randomUUID(), "ARC-L1", null, false, false, 1L);
    LocationDto dto2 = new LocationDto(UUID.randomUUID(), "CRU-L4", null, false, false, 1L);
    when(service.getRefineryLocations()).thenReturn(List.of(loc1, loc2));
    when(mapper.toDto(loc1)).thenReturn(dto1);
    when(mapper.toDto(loc2)).thenReturn(dto2);

    List<LocationDto> result = controller.getRefineryLocations();

    assertEquals(List.of(dto1, dto2), result);
  }

  @Test
  void getHomeLocations_mapsEachEntityToDto() {
    Location loc1 = new Location();
    Location loc2 = new Location();
    LocationDto dto1 = new LocationDto(UUID.randomUUID(), "Orison", null, false, true, 1L);
    LocationDto dto2 = new LocationDto(UUID.randomUUID(), "Lorville", null, false, true, 1L);
    when(service.getHomeLocations()).thenReturn(List.of(loc1, loc2));
    when(mapper.toDto(loc1)).thenReturn(dto1);
    when(mapper.toDto(loc2)).thenReturn(dto2);

    List<LocationDto> result = controller.getHomeLocations();

    assertEquals(List.of(dto1, dto2), result);
  }

  @Test
  void create_stripsServerManagedFields_andDelegatesToService() {
    LocationDto request = new LocationDto(UUID.randomUUID(), "New Loc", "desc", false, false, 99L);
    Location mappedEntity = new Location();
    mappedEntity.setId(request.id());
    mappedEntity.setVersion(request.version());

    when(mapper.toEntity(request)).thenReturn(mappedEntity);

    Location persisted = new Location();
    persisted.setId(UUID.randomUUID());
    persisted.setVersion(1L);
    LocationDto response = new LocationDto(persisted.getId(), "New Loc", "desc", false, false, 1L);

    when(service.createLocation(any())).thenReturn(persisted);
    when(mapper.toDto(persisted)).thenReturn(response);

    LocationDto result = controller.createLocation(request);

    assertSame(response, result);

    ArgumentCaptor<Location> entityCap = ArgumentCaptor.forClass(Location.class);
    verify(service).createLocation(entityCap.capture());
    Location forwarded = entityCap.getValue();
    assertNull(forwarded.getId(), "id must be stripped to force an INSERT");
    assertNull(forwarded.getVersion(), "version must be stripped so JPA initialises it");
  }

  @Test
  void update_forwardsIdAndDtoToService_withoutEntityMapping() {
    UUID id = UUID.randomUUID();
    LocationDto request = new LocationDto(id, "Renamed", "desc", false, false, 4L);
    Location updated = new Location();
    LocationDto response = new LocationDto(id, "Renamed", "desc", false, false, 5L);

    when(service.updateLocation(id, request)).thenReturn(updated);
    when(mapper.toDto(updated)).thenReturn(response);

    LocationDto result = controller.updateLocation(id, request);

    assertSame(response, result);
    verify(service).updateLocation(id, request);
    verify(mapper, never()).toEntity(any(LocationDto.class));
  }

  @Test
  void delete_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteLocation(id);

    verify(service).deleteLocation(id);
    verifyNoMoreInteractions(service, mapper);
  }
}
