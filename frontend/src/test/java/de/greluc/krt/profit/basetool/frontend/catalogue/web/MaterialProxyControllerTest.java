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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialSellingTerminalDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ProfitCalculationDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MaterialProxyController}: the downstream URI is composed as documented, a
 * {@code null} backend response becomes an empty list, and {@code starSystemNames} is omitted when
 * empty and otherwise sent as repeated query parameters.
 */
@ExtendWith(MockitoExtension.class)
class MaterialProxyControllerTest {

  @Mock private BackendApiClient backendApiClient;

  private MaterialProxyController controller;

  @BeforeEach
  void setUp() {
    controller = new MaterialProxyController(new CatalogueBackendClient(backendApiClient));
  }

  @Test
  void getMaterialTerminals_proxiesById_andReturnsBackendResponse() {
    UUID materialId = UUID.randomUUID();
    List<MaterialSellingTerminalDto> backendData =
        List.of(
            new MaterialSellingTerminalDto(
                UUID.randomUUID(), "Lorville TDD", new BigDecimal("12.5")),
            new MaterialSellingTerminalDto(
                UUID.randomUUID(), "Area18 TDD", new BigDecimal("13.0")));
    when(backendApiClient.<List<MaterialSellingTerminalDto>>get(
            eq("/api/v1/materials/{id}/terminals"), anyTypeRef(), eq(materialId)))
        .thenReturn(backendData);

    List<MaterialSellingTerminalDto> result = controller.getMaterialTerminals(materialId);

    assertEquals(backendData, result);
    verify(backendApiClient)
        .get(eq("/api/v1/materials/{id}/terminals"), anyTypeRef(), eq(materialId));
  }

  @Test
  void getMaterialTerminals_withNullBackendResponse_returnsEmptyList() {
    UUID materialId = UUID.randomUUID();
    when(backendApiClient.<List<MaterialSellingTerminalDto>>get(
            eq("/api/v1/materials/{id}/terminals"), anyTypeRef(), eq(materialId)))
        .thenReturn(null);

    List<MaterialSellingTerminalDto> result = controller.getMaterialTerminals(materialId);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void getProfitCalculation_withoutStarSystemNames_buildsBaseUri() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<ProfitCalculationDto>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, null);

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef(), eq(shipId));
    assertEquals("/api/v1/materials/profit-calculation?shipId={shipId}", uriCap.getValue());
  }

  @Test
  void getProfitCalculation_withEmptyStarSystemList_buildsBaseUri() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<ProfitCalculationDto>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, List.of());

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef(), eq(shipId));
    assertEquals("/api/v1/materials/profit-calculation?shipId={shipId}", uriCap.getValue());
  }

  @Test
  void getProfitCalculation_withMultipleStarSystems_appendsEachAsRepeatedPlaceholder() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<ProfitCalculationDto>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, List.of("Stanton", "Pyro"));

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    ArgumentCaptor<Object> varCap = ArgumentCaptor.captor();
    verify(backendApiClient)
        .get(uriCap.capture(), anyTypeRef(), eq(shipId), varCap.capture(), varCap.capture());
    String template = uriCap.getValue();
    assertEquals(
        "/api/v1/materials/profit-calculation?shipId={shipId}"
            + "&starSystemNames={starSystemName}&starSystemNames={starSystemName}",
        template);
    assertEquals(List.of("Stanton", "Pyro"), varCap.getAllValues());
  }

  @Test
  void getProfitCalculation_withSingleStarSystem_appendsOnePlaceholder() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<ProfitCalculationDto>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, List.of("Stanton"));

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    ArgumentCaptor<Object> varCap = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef(), eq(shipId), varCap.capture());
    assertEquals(
        "/api/v1/materials/profit-calculation?shipId={shipId}&starSystemNames={starSystemName}",
        uriCap.getValue());
    assertEquals("Stanton", varCap.getValue());
  }

  @Test
  void getProfitCalculation_withUriSyntaxInStarSystemName_relaysItAsAVariableNotAsQuerySyntax() {
    UUID shipId = UUID.randomUUID();
    String hostile = "Stanton&shipId=00000000-0000-0000-0000-000000000000&page=99";
    when(backendApiClient.<List<ProfitCalculationDto>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    controller.getProfitCalculation(shipId, List.of(hostile));

    ArgumentCaptor<String> uriCap = ArgumentCaptor.captor();
    ArgumentCaptor<Object> varCap = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCap.capture(), anyTypeRef(), eq(shipId), varCap.capture());
    String template = uriCap.getValue();
    assertEquals(
        "/api/v1/materials/profit-calculation?shipId={shipId}&starSystemNames={starSystemName}",
        template);
    assertFalse(template.contains("page=99"), template);
    assertEquals(1, template.split("shipId=", -1).length - 1, template);
    assertEquals(hostile, varCap.getValue());
  }

  @Test
  void getProfitCalculation_relaysTheRouteTerminalsUnchanged() {
    UUID shipId = UUID.randomUUID();
    ProfitCalculationDto row =
        new ProfitCalculationDto(
            UUID.randomUUID(),
            "Laranite",
            null,
            null,
            null,
            null,
            null,
            null,
            "TDD Lorville",
            "Hurston · Lorville",
            "Admin - ARC-L1",
            "Stanton · ARC-L1");
    when(backendApiClient.<List<ProfitCalculationDto>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of(row));

    List<ProfitCalculationDto> result = controller.getProfitCalculation(shipId, null);

    assertEquals(List.of(row), result);
  }

  @Test
  void getProfitCalculation_withNullBackendResponse_returnsEmptyList() {
    UUID shipId = UUID.randomUUID();
    when(backendApiClient.<List<ProfitCalculationDto>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(null);

    List<ProfitCalculationDto> result = controller.getProfitCalculation(shipId, null);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
