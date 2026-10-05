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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateDeletionRequestRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Mockito tests for {@link DeletionRequestProxyController} (REQ-SEC-061, ADR-0181): the proxy
 * relays no user id, asserted on the literal URI, and a missing or malformed {@code eraseHistory}
 * flag means {@code false}.
 */
class DeletionRequestProxyControllerTest {

  private static final String URI = "/api/v1/users/me/deletion-request";

  private static final AdminDeletionRequestDto PENDING =
      new AdminDeletionRequestDto(
          UUID.fromString("4b3a2918-0f7e-4d6c-9b5a-48372615f4e3"),
          UUID.fromString("c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f"),
          null,
          "PENDING",
          true,
          Instant.parse("2026-09-01T10:15:30Z"),
          null,
          null,
          0L);

  private static DeletionRequestProxyController controller(BackendApiClient client) {
    return new DeletionRequestProxyController(new IdentityBackendClient(client));
  }

  @Test
  void request_relaysNoUserIdAndCoercesTheFlag() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(eq(URI), any(), eq(AdminDeletionRequestDto.class))).thenReturn(PENDING);
    DeletionRequestProxyController controller = controller(client);

    ResponseEntity<Object> response = controller.request(Map.of("eraseHistory", true));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(PENDING, response.getBody());
    assertEquals(true, capturePostedBody(client).eraseHistory());
  }

  @Test
  void request_aStringFlagIsNotATrueFlag() {
    BackendApiClient client = mock(BackendApiClient.class);
    DeletionRequestProxyController controller = controller(client);

    controller.request(Map.of("eraseHistory", "true"));

    assertEquals(false, capturePostedBody(client).eraseHistory());
  }

  @Test
  void request_anAbsentFlagIsFalse() {
    BackendApiClient client = mock(BackendApiClient.class);
    DeletionRequestProxyController controller = controller(client);

    controller.request(Map.of());

    assertEquals(false, capturePostedBody(client).eraseHistory());
  }

  @Test
  void request_relaysTheBackendStatusRatherThanFlatteningItTo500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(eq(URI), any(), eq(AdminDeletionRequestDto.class)))
        .thenThrow(new BackendServiceException("already pending", new RuntimeException(), 409));
    DeletionRequestProxyController controller = controller(client);

    ResponseEntity<Object> response = controller.request(Map.of());

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
  }

  @Test
  void request_anUnexpectedFailureIs500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(eq(URI), any(), eq(AdminDeletionRequestDto.class)))
        .thenThrow(new IllegalStateException("boom"));
    DeletionRequestProxyController controller = controller(client);

    ResponseEntity<Object> response = controller.request(Map.of());

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void withdraw_relaysNoUserIdAndAnswers204() {
    BackendApiClient client = mock(BackendApiClient.class);
    DeletionRequestProxyController controller = controller(client);

    ResponseEntity<Object> response = controller.withdraw();

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(client).delete(URI, Void.class);
  }

  @Test
  void withdraw_relaysTheBackendStatus() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(eq(URI), eq(Void.class)))
        .thenThrow(new BackendServiceException("gone", new RuntimeException(), 404));
    DeletionRequestProxyController controller = controller(client);

    ResponseEntity<Object> response = controller.withdraw();

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void withdraw_anUnexpectedFailureIs500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(eq(URI), eq(Void.class))).thenThrow(new IllegalStateException("boom"));
    DeletionRequestProxyController controller = controller(client);

    ResponseEntity<Object> response = controller.withdraw();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void card_publishesTheRequestForTheFragment() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(URI, AdminDeletionRequestDto.class)).thenReturn(PENDING);
    DeletionRequestProxyController controller = controller(client);
    Model model = new ConcurrentModel();

    String view = controller.card(model);

    assertEquals("fragments/profile-deletion-card :: card", view);
    assertEquals(PENDING, model.getAttribute("deletionRequest"));
  }

  @Test
  void card_saysSoWhenTheBackendIsDownRatherThanClaimingNoRequest() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(URI, AdminDeletionRequestDto.class))
        .thenThrow(new BackendServiceException("down", new RuntimeException(), 503));
    DeletionRequestProxyController controller = controller(client);
    Model model = new ConcurrentModel();

    String view = controller.card(model);

    assertEquals("fragments/profile-deletion-card :: card", view);
    assertNull(model.getAttribute("deletionRequest"));
    assertEquals(true, model.getAttribute("deletionRequestUnavailable"));
  }

  @Test
  void card_marksTheStateAvailableOnASuccessfulLoad() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(URI, AdminDeletionRequestDto.class)).thenReturn(null);
    DeletionRequestProxyController controller = controller(client);
    Model model = new ConcurrentModel();

    controller.card(model);

    assertEquals(false, model.getAttribute("deletionRequestUnavailable"));
  }

  /**
   * Captures the body the controller posted, asserting it went to the id-free self-service URI.
   *
   * @param client the mocked client
   * @return the posted body
   */
  private static CreateDeletionRequestRequest capturePostedBody(BackendApiClient client) {
    ArgumentCaptor<CreateDeletionRequestRequest> captor = ArgumentCaptor.captor();
    verify(client).post(eq(URI), captor.capture(), eq(AdminDeletionRequestDto.class));
    return captor.getValue();
  }
}
