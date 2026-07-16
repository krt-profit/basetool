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

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.EntityInUseException;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

  @Mock private LocationRepository locationRepository;

  @Mock private ShipRepository shipRepository;

  @Mock private RefineryOrderRepository refineryOrderRepository;

  @InjectMocks private LocationService locationService;

  @Test
  void createLocation_ShouldSaveDirectly() {
    // Given
    Location inputLocation = new Location();
    inputLocation.setName("Port Olisar");

    when(locationRepository.existsByNameIgnoreCase("Port Olisar")).thenReturn(false);
    when(locationRepository.save(any(Location.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When
    Location savedLocation = locationService.createLocation(inputLocation);

    // Then
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
    when(shipRepository.existsByLocationId(locId)).thenReturn(true);

    assertThrows(EntityInUseException.class, () -> locationService.deleteLocation(locId));
  }

  @Test
  void deleteLocation_WhenInUseByRefineryOrders_ShouldThrowException() {
    UUID locId = UUID.randomUUID();
    when(shipRepository.existsByLocationId(locId)).thenReturn(false);
    when(refineryOrderRepository.existsByLocationId(locId)).thenReturn(true);

    assertThrows(EntityInUseException.class, () -> locationService.deleteLocation(locId));
  }

  @Test
  void findAllReference_capsTheUnpaginatedLookupAtTheDefensiveBound() {
    // Given
    LocationReferenceDto ref = new LocationReferenceDto(UUID.randomUUID(), "Port Olisar");
    when(locationRepository.findAllReference(PageRequest.of(0, LocationService.LOOKUP_MAX_RESULTS)))
        .thenReturn(List.of(ref));

    // When
    List<LocationReferenceDto> result = locationService.findAllReference();

    // Then
    assertEquals(List.of(ref), result);
    verify(locationRepository, times(1))
        .findAllReference(PageRequest.of(0, LocationService.LOOKUP_MAX_RESULTS));
  }

  @Test
  void getHomeLocations_delegatesToCuratedDescendingQuery() {
    Location orison = new Location();
    orison.setName("Orison");
    Location lorville = new Location();
    lorville.setName("Lorville");
    when(locationRepository.findByHomeLocationTrueAndHiddenFalseOrderByNameDesc())
        .thenReturn(List.of(orison, lorville));

    assertEquals(List.of(orison, lorville), locationService.getHomeLocations());
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
