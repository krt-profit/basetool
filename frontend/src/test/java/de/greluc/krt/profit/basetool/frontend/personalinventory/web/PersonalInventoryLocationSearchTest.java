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

package de.greluc.krt.profit.basetool.frontend.personalinventory.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.personalinventory.client.PersonalInventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryLocationOption;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryLocationType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit test for the {@code /personal-inventory/location-search} typeahead endpoint exposed by
 * {@link PersonalInventoryLocationSearchController}. Verifies the URL composition, default and
 * clamped limits, and the silent fallback to an empty list on backend failures (so the typeahead
 * never shows a stack trace to the user).
 */
@ExtendWith(MockitoExtension.class)
class PersonalInventoryLocationSearchTest {

  private static final String SEARCH_URI =
      "/api/v1/personal-inventory/locations?q={q}&limit={limit}";

  @Mock private BackendApiClient backendApiClient;

  private PersonalInventoryLocationSearchController controller;

  @BeforeEach
  void setUp() {
    controller =
        new PersonalInventoryLocationSearchController(
            new PersonalInventoryBackendClient(backendApiClient));
  }

  @Test
  void locationSearch_delegatesToBackend_andDefaultsLimitTo25() {
    PersonalInventoryLocationOption dto =
        new PersonalInventoryLocationOption(
            UUID.randomUUID(),
            PersonalInventoryLocationType.CITY,
            "Lorville",
            "Stanton",
            "Hurston");
    when(backendApiClient.get(eq(SEARCH_URI), anyTypeRef(), eq("lor"), eq(25)))
        .thenReturn(List.of(dto));

    List<PersonalInventoryLocationOption> result = controller.locationSearch("lor", null);

    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals("Lorville", result.get(0).name());
  }

  @Test
  void locationSearch_clampsLimit_to2000() {
    when(backendApiClient.get(eq(SEARCH_URI), anyTypeRef(), eq("x"), eq(2000)))
        .thenReturn(List.of());

    List<PersonalInventoryLocationOption> result = controller.locationSearch("x", 9999);

    assertTrue(result.isEmpty());
  }

  @Test
  void locationSearch_returnsEmptyList_whenBackendThrows() {
    when(backendApiClient.get(eq(SEARCH_URI), anyTypeRef(), eq("anything"), eq(25)))
        .thenThrow(new RuntimeException("boom"));

    List<PersonalInventoryLocationOption> result = controller.locationSearch("anything", 25);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
