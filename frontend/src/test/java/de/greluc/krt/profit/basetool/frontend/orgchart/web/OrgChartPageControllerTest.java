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

package de.greluc.krt.profit.basetool.frontend.orgchart.web;

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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.leadership.model.AreaLeadershipDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.client.OrgChartBackendClient;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartPositionCreateRequest;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartPositionDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartPositionUpdateRequest;
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
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
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
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
    when(backend.get("/api/v1/org-chart", OrgChartDto.class))
        .thenThrow(new BackendServiceException("boom", null, 503));
    Model model = new ConcurrentModel();

    String view = controller.orgChart(null, model);

    assertEquals("org-chart", view);
    assertEquals("error.orgChart.load", model.getAttribute("error"));
  }

  @Test
  void orgChart_fragmentChartBody_returnsChartBodySelector() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
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
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
    when(backend.post(eq("/api/v1/org-chart/positions"), any(), eq(OrgChartPositionDto.class)))
        .thenReturn(
            new OrgChartPositionDto(
                UUID.randomUUID(), "AREA_COORDINATOR", null, null, null, null, null, null, 0, 0L));

    ResponseEntity<Object> response =
        controller.createPosition(
            new OrgChartPositionCreateRequest(
                "AREA_COORDINATOR", null, null, null, null, null, null));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void createPosition_backendValidationError_relaysStatusAndBody() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
    when(backend.post(eq("/api/v1/org-chart/positions"), any(), eq(OrgChartPositionDto.class)))
        .thenThrow(
            new BackendServiceException(
                "bad", null, 400, "BAD_REQUEST", null, List.of(), "Limit reached."));

    ResponseEntity<Object> response =
        controller.createPosition(
            new OrgChartPositionCreateRequest("COMMAND_LEAD", null, null, null, null, null, null));

    assertEquals(400, response.getStatusCode().value());
    Map<String, Object> body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("BAD_REQUEST", body.get("code"));
    assertEquals("Limit reached.", body.get("detail"));
  }

  @Test
  void updatePosition_optimisticLock_relays409() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
    UUID id = UUID.randomUUID();
    when(backend.put(
            eq("/api/v1/org-chart/positions/{id}"), any(), eq(OrgChartPositionDto.class), eq(id)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "Stale."));

    ResponseEntity<Object> response =
        controller.updatePosition(
            id, new OrgChartPositionUpdateRequest(null, null, null, 0L, null));

    assertEquals(409, response.getStatusCode().value());
    Map<String, Object> body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("OPTIMISTIC_LOCK", body.get("code"));
  }

  @Test
  void deletePosition_success_returns200() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
    UUID id = UUID.randomUUID();

    ResponseEntity<Object> response = controller.deletePosition(id);

    assertEquals(200, response.getStatusCode().value());
    verify(backend).delete("/api/v1/org-chart/positions/{id}", Void.class, id);
  }

  @Test
  void deletePosition_notFound_relays404() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
    UUID id = UUID.randomUUID();
    when(backend.delete("/api/v1/org-chart/positions/{id}", Void.class, id))
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
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
    UUID id = UUID.randomUUID();

    ResponseEntity<Object> response = controller.vacateLeader(id, 3L);

    assertEquals(200, response.getStatusCode().value());
    verify(backend)
        .delete("/api/v1/org-chart/positions/{id}/leader?version={version}", Void.class, id, 3L);
  }

  @Test
  void vacateLeader_notCommand_relays400() {
    BackendApiClient backend = mock(BackendApiClient.class);
    OrgChartPageController controller =
        new OrgChartPageController(new OrgChartBackendClient(backend));
    UUID id = UUID.randomUUID();
    when(backend.delete(
            "/api/v1/org-chart/positions/{id}/leader?version={version}", Void.class, id, 3L))
        .thenThrow(
            new BackendServiceException(
                "bad", null, 400, "BAD_REQUEST", null, List.of(), "Not a Kommando."));

    ResponseEntity<Object> response = controller.vacateLeader(id, 3L);

    assertEquals(400, response.getStatusCode().value());
    Map<String, Object> body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("BAD_REQUEST", body.get("code"));
  }
}
