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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungViewDto;
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
 * Pure-method unit tests for {@link LeitungPageController}: the page loads the delegated view + the
 * user-lookup picker (skipping the lookup on the fragment re-swap path), and the AJAX write proxies
 * relay the backend's status + {@code {code, detail}} body on failure so the page JS can toast the
 * right message and recognise the {@code OPTIMISTIC_LOCK} code.
 */
@SuppressWarnings("unchecked")
class LeitungPageControllerTest {

  private static LeitungViewDto emptyView() {
    return new LeitungViewDto(false, List.of(), List.of(), List.of(), List.of());
  }

  @Test
  void leitung_loadsViewAndUserPicker() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    LeitungViewDto view = emptyView();
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class)).thenReturn(view);
    when(backend.get(eq("/api/v1/users/lookup"), anyTypeRef()))
        .thenReturn(List.of(Map.of("id", UUID.randomUUID().toString(), "username", "pilot")));
    Model model = new ConcurrentModel();

    String result = controller.leitung(null, model);

    assertEquals("organisation/leitung", result);
    assertSame(view, model.getAttribute("leitung"));
    assertEquals(1, ((List<?>) model.getAttribute("allUsers")).size());
  }

  @Test
  void leitung_fragment_returnsFragmentSelectorAndSkipsUserLookup() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class)).thenReturn(emptyView());
    Model model = new ConcurrentModel();

    String result = controller.leitung("leitungSections", model);

    assertEquals("organisation/leitung :: leitungSections", result);
    verify(backend, never()).get(eq("/api/v1/users/lookup"), anyTypeRef());
  }

  @Test
  void leitung_backendFailure_setsErrorAttribute() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    when(backend.get("/api/v1/leitung/view", LeitungViewDto.class))
        .thenThrow(new BackendServiceException("boom", null, 503));
    Model model = new ConcurrentModel();

    String result = controller.leitung(null, model);

    assertEquals("organisation/leitung", result);
    assertEquals("leitung.error.load", model.getAttribute("error"));
  }

  @Test
  void assignSquadronRank_success_returns200() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backend.put(
            eq("/api/v1/squadrons/" + squadronId + "/ranks/" + userId), any(), eq(Object.class)))
        .thenReturn(new Object());

    ResponseEntity<Object> response =
        controller.assignSquadronRank(squadronId, userId, Map.of("role", "KOMMANDOLEITER"));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void assignSquadronRank_optimisticLock_relays409() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backend.put(
            eq("/api/v1/squadrons/" + squadronId + "/ranks/" + userId), any(), eq(Object.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "Stale."));

    ResponseEntity<Object> response =
        controller.assignSquadronRank(squadronId, userId, Map.of("role", "ENSIGN"));

    assertEquals(409, response.getStatusCode().value());
    Map<String, Object> body = assertInstanceOf(Map.class, response.getBody());
    assertEquals("OPTIMISTIC_LOCK", body.get("code"));
  }

  @Test
  void removeSquadronRank_relaysVersionToBackend() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    ResponseEntity<Object> response = controller.removeSquadronRank(squadronId, userId, 4L);

    assertEquals(200, response.getStatusCode().value());
    verify(backend)
        .delete(
            "/api/v1/squadrons/" + squadronId + "/ranks/" + userId + "?version=4", Object.class);
  }

  @Test
  void createKommandoGroup_success_returns200() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    UUID squadronId = UUID.randomUUID();
    when(backend.post(
            eq("/api/v1/squadrons/" + squadronId + "/kommando-groups"), any(), eq(Object.class)))
        .thenReturn(new Object());

    ResponseEntity<Object> response =
        controller.createKommandoGroup(squadronId, Map.of("name", "Alpha"));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void addBereichLeader_backendForbidden_relays403() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    UUID bereichId = UUID.randomUUID();
    when(backend.post(
            eq("/api/v1/org-hierarchy/bereiche/" + bereichId + "/members"),
            any(),
            eq(Object.class)))
        .thenThrow(
            new BackendServiceException(
                "denied", null, 403, "ACCESS_DENIED", null, List.of(), "No."));

    ResponseEntity<Object> response =
        controller.addBereichLeader(
            bereichId, Map.of("userId", UUID.randomUUID().toString(), "role", "KOORDINATOR"));

    assertEquals(403, response.getStatusCode().value());
    assertTrue(((Map<String, Object>) response.getBody()).containsKey("code"));
  }

  @Test
  void toggleSkLead_relaysToBackend() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backend.patch(
            eq("/api/v1/special-commands/" + skId + "/members/" + userId + "/lead"),
            any(),
            eq(Object.class)))
        .thenReturn(new Object());

    ResponseEntity<Object> response =
        controller.toggleSkLead(skId, userId, Map.of("isLead", true, "version", 0));

    assertEquals(200, response.getStatusCode().value());
  }

  @Test
  void removeOlMember_success_returns200() {
    BackendApiClient backend = mock(BackendApiClient.class);
    LeitungPageController controller = new LeitungPageController(backend);
    UUID olId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    ResponseEntity<Object> response = controller.removeOlMember(olId, userId);

    assertEquals(200, response.getStatusCode().value());
    verify(backend)
        .delete(
            "/api/v1/org-hierarchy/organisationsleitung/" + olId + "/members/" + userId,
            Object.class);
  }
}
