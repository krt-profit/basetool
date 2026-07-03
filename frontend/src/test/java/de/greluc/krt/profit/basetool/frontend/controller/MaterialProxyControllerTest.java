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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MaterialProxyController}. The controller is a thin frontend-side proxy that
 * forwards two read-only endpoints to the backend. The contract under test:
 *
 * <ol>
 *   <li>The downstream URI is composed exactly as documented (path-parameter interpolation,
 *       multi-value query parameters appended in order).
 *   <li>A {@code null} response from the backend is normalised to an empty list — never propagated
 *       to the caller — so that Thymeleaf rendering doesn't NPE.
 *   <li>The optional {@code starSystemNames} parameter is omitted when missing / empty, and
 *       otherwise appended as repeated query params (NOT comma-separated).
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MaterialProxyControllerTest {

  @Mock private BackendApiClient backendApiClient;

  @InjectMocks private MaterialProxyController controller;

  // ── getMaterialTerminals ────────────────────────────────────────────────

  @Test
  void getMaterialTerminals_proxiesById_andReturnsBackendResponse() {
    // Given
    UUID materialId = UUID.randomUUID();
    List<Map<String, Object>> backendData =
        List.of(
            Map.of("terminalName", "Lorville TDD", "priceBuy", 12.5),
            Map.of("terminalName", "Area18 TDD", "priceBuy", 13.0));
    when(backendApiClient.<List<Map<String, Object>>>get(
            eq("/api/v1/materials/" + materialId + "/terminals"), anyTypeRef()))
        .thenReturn(backendData);

    // When
    List<Map<String, Object>> result = controller.getMaterialTerminals(materialId);

    // Then
    assertEquals(backendData, result);
    verify(backendApiClient)
        .get(eq("/api/v1/materials/" + materialId + "/terminals"), anyTypeRef());
  }

  @Test
  void getMaterialTerminals_withNullBackendResponse_returnsEmptyList() {
    // Given — the backend returned null (e.g. material not found, 204 No Content)
    UUID materialId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(
            eq("/api/v1/materials/" + materialId + "/terminals"), anyTypeRef()))
        .thenReturn(null);

    // When
    List<Map<String, Object>> result = controller.getMaterialTerminals(materialId);

    // Then — never propagate null upstream; Thymeleaf templates iterate with
    // `${terminals}` and would NPE on a null collection.
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  // ── getProfitCalculation ────────────────────────────────────────────────

  @Test
  void getProfitCalculation_withoutStarSystemNames_buildsBaseUri() {
    // Given
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(any(String.class), anyTypeRef()))
        .thenReturn(List.of());

    // When
    controller.getProfitCalculation(shipId, null);

    // Then — no query parameters appended beyond the mandatory shipId
    ArgumentCaptor<String> uriCap = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef());
    assertEquals("/api/v1/materials/profit-calculation?shipId=" + shipId, uriCap.getValue());
  }

  @Test
  void getProfitCalculation_withEmptyStarSystemList_buildsBaseUri() {
    // Given — explicit empty list should behave like null
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(any(String.class), anyTypeRef()))
        .thenReturn(List.of());

    // When
    controller.getProfitCalculation(shipId, List.of());

    // Then
    ArgumentCaptor<String> uriCap = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef());
    assertEquals("/api/v1/materials/profit-calculation?shipId=" + shipId, uriCap.getValue());
  }

  @Test
  void getProfitCalculation_withMultipleStarSystems_appendsEachAsRepeatedParam() {
    // Given
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(any(String.class), anyTypeRef()))
        .thenReturn(List.of());

    // When
    controller.getProfitCalculation(shipId, List.of("Stanton", "Pyro"));

    // Then — each star system is appended as its own query parameter,
    // matching backend's Pageable/multi-value binding (NOT CSV-encoded)
    ArgumentCaptor<String> uriCap = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef());
    String uri = uriCap.getValue();
    assertTrue(uri.startsWith("/api/v1/materials/profit-calculation?shipId=" + shipId));
    assertTrue(uri.contains("&starSystemNames=Stanton"));
    assertTrue(uri.contains("&starSystemNames=Pyro"));
    // Order preserved (insertion order in StringBuilder)
    assertTrue(uri.indexOf("Stanton") < uri.indexOf("Pyro"));
  }

  @Test
  void getProfitCalculation_withSingleStarSystem_appendsOneParam() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(any(String.class), anyTypeRef()))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, List.of("Stanton"));

    ArgumentCaptor<String> uriCap = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef());
    assertEquals(
        "/api/v1/materials/profit-calculation?shipId=" + shipId + "&starSystemNames=Stanton",
        uriCap.getValue());
  }

  @Test
  void getProfitCalculation_withNullBackendResponse_returnsEmptyList() {
    // Given
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(any(String.class), anyTypeRef()))
        .thenReturn(null);

    // When
    List<Map<String, Object>> result = controller.getProfitCalculation(shipId, null);

    // Then
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
