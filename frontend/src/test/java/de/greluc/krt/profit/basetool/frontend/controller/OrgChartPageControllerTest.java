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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.AreaLeadershipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Pure-method unit tests for {@link OrgChartPageController}. Verifies the read page loads the chart
 * (without preloading any user list — account seats are mirror-only since REQ-ROLE-006) and that
 * the AJAX write proxies relay the backend's status + {@code {code, detail}} body on failure (so
 * the page JS can toast the right message).
 */
@SuppressWarnings("unchecked")
class OrgChartPageControllerTest {

  private static OrgChartDto emptyChart() {
    return new OrgChartDto(
        null,
        List.of(),
        new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
        List.of(),
        List.of());
  }

  @Test
  void orgChart_loadsChartWithoutPreloadingUsers() {
    // Account-linked seats are mirror-only now (epic #800, REQ-ROLE-006): the chart editor offers
    // no
    // account picker, so the page no longer preloads the user-lookup list.
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    OrgChartDto chart = emptyChart();
    when(backend.get("/api/v1/org-chart", OrgChartDto.class)).thenReturn(chart);
    Model model = new ConcurrentModel();

    String view = controller.orgChart(null, model);

    assertEquals("org-chart", view);
    assertSame(chart, model.getAttribute("orgChart"));
    verify(backend, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
  }

  @Test
  void orgChart_backendFailure_setsErrorAttribute() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    when(backend.get("/api/v1/org-chart", OrgChartDto.class))
        .thenThrow(new BackendServiceException("boom", null, 503));
    Model model = new ConcurrentModel();

    String view = controller.orgChart(null, model);

    assertEquals("org-chart", view);
    assertEquals("error.orgChart.load", model.getAttribute("error"));
  }

  @Test
  void orgChart_fragmentChartBody_returnsChartBodySelector() {
    // The in-place chart refresh (epic #571 / REQ-FE-005) re-renders only the chartBody fragment.
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    OrgChartDto chart = emptyChart();
    when(backend.get("/api/v1/org-chart", OrgChartDto.class)).thenReturn(chart);
    Model model = new ConcurrentModel();

    String view = controller.orgChart("chartBody", model);

    assertEquals("org-chart :: chartBody", view);
    assertSame(chart, model.getAttribute("orgChart"));
  }

  @Test
  void createPosition_success_returns200() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    when(backend.post(eq("/api/v1/org-chart/positions"), any(), eq(Object.class)))
        .thenReturn(new Object());

    ResponseEntity<Object> response =
        controller.createPosition(Map.of("positionType", "AREA_COORDINATOR"));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void createPosition_backendValidationError_relaysStatusAndBody() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    when(backend.post(eq("/api/v1/org-chart/positions"), any(), eq(Object.class)))
        .thenThrow(
            new BackendServiceException(
                "bad", null, 400, "BAD_REQUEST", null, List.of(), "Limit reached."));

    ResponseEntity<Object> response =
        controller.createPosition(Map.of("positionType", "COMMAND_LEAD"));

    assertEquals(400, response.getStatusCode().value());
    Map<String, Object> body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("BAD_REQUEST", body.get("code"));
    assertEquals("Limit reached.", body.get("detail"));
  }

  @Test
  void updatePosition_optimisticLock_relays409() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    UUID id = UUID.randomUUID();
    when(backend.put(eq("/api/v1/org-chart/positions/" + id), any(), eq(Object.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "Stale."));

    ResponseEntity<Object> response = controller.updatePosition(id, Map.of("version", 0));

    assertEquals(409, response.getStatusCode().value());
    Map<String, Object> body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("OPTIMISTIC_LOCK", body.get("code"));
  }

  @Test
  void deletePosition_success_returns200() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    UUID id = UUID.randomUUID();

    ResponseEntity<Object> response = controller.deletePosition(id);

    assertEquals(200, response.getStatusCode().value());
    verify(backend).delete("/api/v1/org-chart/positions/" + id, Void.class);
  }

  @Test
  void deletePosition_notFound_relays404() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    UUID id = UUID.randomUUID();
    when(backend.delete("/api/v1/org-chart/positions/" + id, Void.class))
        .thenThrow(
            new BackendServiceException(
                "missing", null, 404, "NOT_FOUND", null, List.of(), "Gone."));

    ResponseEntity<Object> response = controller.deletePosition(id);

    assertEquals(404, response.getStatusCode().value());
    assertFalse(((Map<String, Object>) response.getBody()).isEmpty());
  }

  @Test
  void vacateLeader_success_returns200AndRelaysVersion() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    UUID id = UUID.randomUUID();

    ResponseEntity<Object> response = controller.vacateLeader(id, 3L);

    assertEquals(200, response.getStatusCode().value());
    verify(backend).delete("/api/v1/org-chart/positions/" + id + "/leader?version=3", Void.class);
  }

  @Test
  void vacateLeader_notCommand_relays400() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller = new OrgChartPageController(backend);
    UUID id = UUID.randomUUID();
    when(backend.delete("/api/v1/org-chart/positions/" + id + "/leader?version=3", Void.class))
        .thenThrow(
            new BackendServiceException(
                "bad", null, 400, "BAD_REQUEST", null, List.of(), "Not a Kommando."));

    ResponseEntity<Object> response = controller.vacateLeader(id, 3L);

    assertEquals(400, response.getStatusCode().value());
    Map<String, Object> body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("BAD_REQUEST", body.get("code"));
  }
}
