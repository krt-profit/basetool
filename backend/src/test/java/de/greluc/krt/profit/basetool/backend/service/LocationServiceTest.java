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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationUsage;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.EntityInUseException;
import de.greluc.krt.profit.basetool.backend.mapper.LocationMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

  @Mock private LocationRepository locationRepository;

  @Mock private LocationMapper locationMapper;

  @Mock private LocationUsage shipUsage;

  @Mock private LocationUsage orderUsage;

  private LocationService locationService;

  @BeforeEach
  void setUp() {
    locationService =
        new LocationService(locationRepository, locationMapper, List.of(orderUsage, shipUsage));
  }

  @Test
  void createLocation_ShouldSaveDirectly() {
    Location inputLocation = new Location();
    inputLocation.setName("Port Olisar");

    when(locationRepository.existsByNameIgnoreCase("Port Olisar")).thenReturn(false);
    when(locationRepository.save(any(Location.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Location savedLocation = locationService.createLocation(inputLocation);

    assertNotNull(savedLocation);
    assertEquals("Port Olisar", savedLocation.getName());
    verify(locationRepository, times(1)).save(inputLocation);
  }

  @Test
  void createLocation_DuplicateName_ShouldThrowException() {
    Location inputLocation = new Location();
    inputLocation.setName("Dupe");

    when(locationRepository.existsByNameIgnoreCase("Dupe")).thenReturn(true);

    assertThrows(
        DuplicateEntityException.class, () -> locationService.createLocation(inputLocation));
  }

  @Test
  void deleteLocation_WhenInUseByShips_ShouldThrowException() {
    UUID locId = UUID.randomUUID();
    when(shipUsage.position()).thenReturn(10);
    when(orderUsage.position()).thenReturn(20);
    when(shipUsage.users()).thenReturn("ships");
    when(shipUsage.isUsed(locId)).thenReturn(true);

    EntityInUseException thrown =
        assertThrows(EntityInUseException.class, () -> locationService.deleteLocation(locId));

    assertEquals(
        "Cannot delete location: It is currently used by one or more ships.", thrown.getMessage());
    verify(orderUsage, never()).isUsed(any(UUID.class));
    verify(locationRepository, never()).delete(any(Location.class));
  }

  @Test
  void deleteLocation_WhenInUseByRefineryOrders_ShouldThrowException() {
    UUID locId = UUID.randomUUID();
    when(shipUsage.position()).thenReturn(10);
    when(orderUsage.position()).thenReturn(20);
    when(shipUsage.isUsed(locId)).thenReturn(false);
    when(orderUsage.users()).thenReturn("refinery orders");
    when(orderUsage.isUsed(locId)).thenReturn(true);

    EntityInUseException thrown =
        assertThrows(EntityInUseException.class, () -> locationService.deleteLocation(locId));

    assertEquals(
        "Cannot delete location: It is currently used by one or more refinery orders.",
        thrown.getMessage());
    verify(locationRepository, never()).delete(any(Location.class));
  }

  @Test
  void deleteLocation_WhenUnused_DeletesTheLocationAfterAskingEveryUsage() {
    UUID locId = UUID.randomUUID();
    Location location = new Location();
    location.setId(locId);
    when(shipUsage.position()).thenReturn(10);
    when(orderUsage.position()).thenReturn(20);
    when(shipUsage.isUsed(locId)).thenReturn(false);
    when(orderUsage.isUsed(locId)).thenReturn(false);
    when(locationRepository.findById(locId)).thenReturn(Optional.of(location));

    locationService.deleteLocation(locId);

    verify(shipUsage).isUsed(locId);
    verify(orderUsage).isUsed(locId);
    verify(locationRepository).delete(location);
  }

  @Test
  void findAllReference_returnsTheCompleteUnboundedProjection() {
    LocationReferenceDto ref = new LocationReferenceDto(UUID.randomUUID(), "Port Olisar");
    when(locationRepository.findAllReference()).thenReturn(List.of(ref));

    List<LocationReferenceDto> result = locationService.findAllReference();

    assertEquals(List.of(ref), result);
    verify(locationRepository, times(1)).findAllReference();
  }

  @Test
  void searchReference_escapesLikeMetacharactersAndPagesTheRepositoryQuery() {
    LocationReferenceDto ref = new LocationReferenceDto(UUID.randomUUID(), "Area 18 100%");
    PageRequest pageable = PageRequest.of(0, 25);
    when(locationRepository.searchReference("100\\%", pageable))
        .thenReturn(new PageImpl<>(List.of(ref)));

    Page<LocationReferenceDto> result = locationService.searchReference("100%", pageable);

    assertEquals(List.of(ref), result.getContent());
    verify(locationRepository, times(1)).searchReference("100\\%", pageable);
  }

  @Test
  void searchReference_passesNullThroughAsTheNoFilterMarker() {
    PageRequest pageable = PageRequest.of(0, 25);
    when(locationRepository.searchReference(null, pageable)).thenReturn(new PageImpl<>(List.of()));

    locationService.searchReference(null, pageable);

    verify(locationRepository, times(1)).searchReference(null, pageable);
  }

  @Test
  void getHomeLocations_mapsTheCuratedDescendingQueryToReadModels() {
    Location orison = new Location();
    orison.setName("Orison");
    Location lorville = new Location();
    lorville.setName("Lorville");
    LocationDto orisonDto = new LocationDto(UUID.randomUUID(), "Orison", null, false, true, 0L);
    LocationDto lorvilleDto = new LocationDto(UUID.randomUUID(), "Lorville", null, false, true, 0L);
    when(locationRepository.findByHomeLocationTrueAndHiddenFalseOrderByNameDesc())
        .thenReturn(List.of(orison, lorville));
    when(locationMapper.toDto(orison)).thenReturn(orisonDto);
    when(locationMapper.toDto(lorville)).thenReturn(lorvilleDto);

    assertEquals(List.of(orisonDto, lorvilleDto), locationService.getHomeLocations());
    verify(locationRepository, times(1)).findByHomeLocationTrueAndHiddenFalseOrderByNameDesc();
  }

  @Test
  void updateLocation_copiesHomeLocationFlagFromDto() {
    UUID id = UUID.randomUUID();
    Location existing = new Location();
    existing.setId(id);
    existing.setName("Lorville");
    existing.setVersion(1L);
    existing.setHomeLocation(false);

    when(locationRepository.existsByNameIgnoreCaseAndIdNot("Lorville", id)).thenReturn(false);
    when(locationRepository.findById(id)).thenReturn(Optional.of(existing));
    when(locationRepository.save(any(Location.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    LocationDto dto = new LocationDto(id, "Lorville", "Hurston", false, true, 1L);
    Location result = locationService.updateLocation(id, dto);

    assertTrue(result.getHomeLocation(), "updateLocation must copy the homeLocation flag from DTO");
  }
}
