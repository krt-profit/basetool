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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.frontend.model.dto.UexLocationDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit test for the {@code /personal-inventory/uex-search} typeahead endpoint exposed by
 * {@link PersonalInventoryPageController}. Verifies the URL composition, default and clamped
 * limits, and the silent fallback to an empty list on backend failures (so the typeahead never
 * shows a stack trace to the user).
 */
@ExtendWith(MockitoExtension.class)
class PersonalInventoryUexSearchTest {

  @Mock private BackendApiClient backendApiClient;

  @InjectMocks private PersonalInventoryPageController controller;

  @Test
  void uexSearch_delegatesToBackend_andDefaultsLimitTo25() {
    // Given
    UexLocationDto dto =
        new UexLocationDto(
            42, PersonalInventoryLocationType.CITY, "Lorville", "Stanton", "Hurston");
    when(backendApiClient.get(
            contains("/api/v1/uex/locations/search?q=lor&limit=25"), anyTypeRef()))
        .thenReturn(List.of(dto));

    // When
    List<UexLocationDto> result = controller.uexSearch("lor", null);

    // Then
    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals("Lorville", result.get(0).name());
  }

  @Test
  void uexSearch_clampsLimit_to100() {
    // Given
    when(backendApiClient.get(contains("limit=100"), anyTypeRef())).thenReturn(List.of());

    // When
    List<UexLocationDto> result = controller.uexSearch("x", 9999);

    // Then
    assertTrue(result.isEmpty());
  }

  @Test
  void uexSearch_returnsEmptyList_whenBackendThrows() {
    // Given: backend throws unexpectedly; the controller must swallow it.
    when(backendApiClient.get(any(), anyTypeRef())).thenThrow(new RuntimeException("boom"));

    // When
    List<UexLocationDto> result = controller.uexSearch("anything", 25);

    // Then
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
