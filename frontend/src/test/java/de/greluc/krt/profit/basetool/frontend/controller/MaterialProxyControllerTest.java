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

  @Test
  void getMaterialTerminals_proxiesById_andReturnsBackendResponse() {
    UUID materialId = UUID.randomUUID();
    List<Map<String, Object>> backendData =
        List.of(
            Map.of("terminalName", "Lorville TDD", "priceBuy", 12.5),
            Map.of("terminalName", "Area18 TDD", "priceBuy", 13.0));
    when(backendApiClient.<List<Map<String, Object>>>get(
            eq("/api/v1/materials/" + materialId + "/terminals"), anyTypeRef()))
        .thenReturn(backendData);

    List<Map<String, Object>> result = controller.getMaterialTerminals(materialId);

    assertEquals(backendData, result);
    verify(backendApiClient)
        .get(eq("/api/v1/materials/" + materialId + "/terminals"), anyTypeRef());
  }

  @Test
  void getMaterialTerminals_withNullBackendResponse_returnsEmptyList() {
    UUID materialId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(
            eq("/api/v1/materials/" + materialId + "/terminals"), anyTypeRef()))
        .thenReturn(null);

    List<Map<String, Object>> result = controller.getMaterialTerminals(materialId);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void getProfitCalculation_withoutStarSystemNames_buildsBaseUri() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, null);

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef(), any(Object[].class));
    assertEquals("/api/v1/materials/profit-calculation?shipId=" + shipId, uriCap.getValue());
  }

  @Test
  void getProfitCalculation_withEmptyStarSystemList_buildsBaseUri() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, List.of());

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef(), any(Object[].class));
    assertEquals("/api/v1/materials/profit-calculation?shipId=" + shipId, uriCap.getValue());
  }

  @Test
  void getProfitCalculation_withMultipleStarSystems_appendsEachAsRepeatedPlaceholder() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, List.of("Stanton", "Pyro"));

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    ArgumentCaptor<Object> varCap = ArgumentCaptor.captor();
    verify(backendApiClient)
        .get(uriCap.capture(), anyTypeRef(), varCap.capture(), varCap.capture());
    String template = uriCap.getValue();
    assertEquals(
        "/api/v1/materials/profit-calculation?shipId="
            + shipId
            + "&starSystemNames={f0}&starSystemNames={f1}",
        template);
    assertEquals(List.of("Stanton", "Pyro"), varCap.getAllValues());
  }

  @Test
  void getProfitCalculation_withSingleStarSystem_appendsOnePlaceholder() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, List.of("Stanton"));

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    ArgumentCaptor<Object> varCap = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef(), varCap.capture());
    assertEquals(
        "/api/v1/materials/profit-calculation?shipId=" + shipId + "&starSystemNames={f0}",
        uriCap.getValue());
    assertEquals("Stanton", varCap.getValue());
  }

  @Test
  void getProfitCalculation_withUriSyntaxInStarSystemName_relaysItAsAVariableNotAsQuerySyntax() {
    UUID shipId = UUID.randomUUID();
    String hostile = "Stanton&shipId=00000000-0000-0000-0000-000000000000&page=99";
    when(backendApiClient.<List<Map<String, Object>>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, List.of(hostile));

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    ArgumentCaptor<Object> varCap = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef(), varCap.capture());
    String template = uriCap.getValue();
    assertEquals(
        "/api/v1/materials/profit-calculation?shipId=" + shipId + "&starSystemNames={f0}",
        template);
    assertFalse(template.contains("page=99"), template);
    assertEquals(1, template.split("shipId=", -1).length - 1, template);
    assertEquals(hostile, varCap.getValue());
  }

  @Test
  void getProfitCalculation_withNullBackendResponse_returnsEmptyList() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<Map<String, Object>>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(null);

    List<Map<String, Object>> result = controller.getProfitCalculation(shipId, null);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
