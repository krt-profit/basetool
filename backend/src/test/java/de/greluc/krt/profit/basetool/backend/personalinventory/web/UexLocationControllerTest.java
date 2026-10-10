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

package de.greluc.krt.profit.basetool.backend.personalinventory.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.personalinventory.internal.PersonalInventoryItemService;
import de.greluc.krt.profit.basetool.backend.personalinventory.internal.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.personalinventory.internal.UexLocationDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UexLocationControllerTest {

  @Mock private PersonalInventoryItemService service;

  @InjectMocks private UexLocationController controller;

  @Test
  void searchShouldUseDefaultLimitWhenAbsent() {
    when(service.searchLocations(any(), any(Integer.class))).thenReturn(List.of());

    controller.search("lorville", null);

    ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(service).searchLocations(any(), limitCaptor.capture());
    assertEquals(25, limitCaptor.getValue(), "Default typeahead limit must be 25.");
  }

  @Test
  void searchShouldClampOversizedLimit() {
    when(service.searchLocations(any(), any(Integer.class))).thenReturn(List.of());

    controller.search(null, 9999);

    ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(service).searchLocations(any(), limitCaptor.capture());
    assertEquals(
        2000, limitCaptor.getValue(), "Limit must be clamped to the configured max (2000).");
  }

  @Test
  void searchShouldClampNonPositiveLimitToOne() {
    when(service.searchLocations(any(), any(Integer.class))).thenReturn(List.of());

    controller.search(null, 0);

    ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(service).searchLocations(any(), limitCaptor.capture());
    assertEquals(1, limitCaptor.getValue());
  }

  @Test
  void searchShouldReturnServiceResultUnchanged() {
    UexLocationDto hit =
        new UexLocationDto(1, PersonalInventoryLocationType.CITY, "Lorville", "Stanton", "Hurston");
    when(service.searchLocations(any(), any(Integer.class))).thenReturn(List.of(hit));

    List<UexLocationDto> result = controller.search("lor", 25);

    assertEquals(1, result.size());
    assertEquals("Lorville", result.get(0).name());
  }
}
