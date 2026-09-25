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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the "Raffinerie" dropdown's preservation rule on the refinery-order detail page
 * (REQ-REFINERY-020).
 *
 * <p>The backend picker source drops hidden locations, so an order created before an admin hid its
 * refinery would otherwise lose its own option. Because that {@code <select>} is {@code required},
 * a dropped option leaves the field on "-- please choose --" and blocks every later save of an
 * order that was valid when it was created — hence the preserved entry.
 */
class RefineryOrderLocationDropdownTest {

  @Test
  void shouldAppendPreservedLocationMissingFromThePickerList() {
    LocationDto offered = location("ArcCorp Mining Area 141");
    LocationDto hiddenButLinked = location("Levski");

    List<LocationDto> result =
        RefineryOrderPageController.withPreservedLocation(
            new ArrayList<>(List.of(offered)), hiddenButLinked);

    assertEquals(2, result.size());
    assertTrue(result.stream().anyMatch(l -> hiddenButLinked.id().equals(l.id())));
  }

  @Test
  void shouldNotDuplicateAPreservedLocationAlreadyOffered() {
    LocationDto offered = location("Levski");

    List<LocationDto> result =
        RefineryOrderPageController.withPreservedLocation(
            new ArrayList<>(List.of(offered)), offered);

    assertEquals(1, result.size());
    assertEquals(offered.id(), result.get(0).id());
  }

  @Test
  void shouldMatchTheOfferedLocationByIdNotByIdentity() {
    UUID sharedId = UUID.randomUUID();
    LocationDto offered = new LocationDto(sharedId, "Levski", null, false, false, 1L);
    LocationDto sameLocationFromOrder = new LocationDto(sharedId, "Levski", null, false, false, 2L);

    List<LocationDto> result =
        RefineryOrderPageController.withPreservedLocation(
            new ArrayList<>(List.of(offered)), sameLocationFromOrder);

    assertEquals(1, result.size());
  }

  @Test
  void shouldReturnThePickerListUnchangedWithoutAPreservedLocation() {
    List<LocationDto> offered = new ArrayList<>(List.of(location("Levski")));

    List<LocationDto> result = RefineryOrderPageController.withPreservedLocation(offered, null);

    assertSame(offered, result);
    assertEquals(1, result.size());
  }

  @Test
  void shouldIgnoreAPreservedLocationWithoutAnId() {
    List<LocationDto> offered = new ArrayList<>(List.of(location("Levski")));

    List<LocationDto> result =
        RefineryOrderPageController.withPreservedLocation(
            offered, new LocationDto(null, "Unsaved", null, false, false, null));

    assertEquals(1, result.size());
  }

  @Test
  void shouldPreserveIntoAnEmptyListWhenTheBackendCallFailed() {
    LocationDto linked = location("Levski");

    List<LocationDto> result =
        RefineryOrderPageController.withPreservedLocation(new ArrayList<>(), linked);

    assertEquals(1, result.size());
    assertEquals(linked.id(), result.get(0).id());
  }

  private LocationDto location(String name) {
    return new LocationDto(UUID.randomUUID(), name, null, false, false, 1L);
  }
}
