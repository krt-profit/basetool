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

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Mockito tests for {@link DeletionRequestProxyController} (REQ-SEC-061, ADR-0181).
 *
 * <p>The property worth a test rather than a comment: <b>the proxy accepts no user id and relays
 * none.</b> The backend derives the subject from the token, and that is precisely what makes these
 * three endpoints safe to expose to every authenticated member without a scope check of their own.
 * An id sneaking into the relayed URI would turn a self-service surface into one member erasing
 * another, so each test asserts the literal URI.
 *
 * <p>The flag coercion is the other one: a missing or malformed {@code eraseHistory} means "did not
 * ask for the extra erasure", because that erasure is irreversible and a parse failure must not
 * grant it.
 */
class DeletionRequestProxyControllerTest {

  private static final String URI = "/api/v1/users/me/deletion-request";

  @Test
  void request_relaysNoUserIdAndCoercesTheFlag() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(eq(URI), any(), eq(Object.class))).thenReturn(Map.of("status", "PENDING"));
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);

    ResponseEntity<Object> response = controller.request(Map.of("eraseHistory", true));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(Map.of("status", "PENDING"), response.getBody());
    assertEquals(true, capturePostedBody(client).get("eraseHistory"));
  }

  @Test
  void request_aStringFlagIsNotATrueFlag() {
    BackendApiClient client = mock(BackendApiClient.class);
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);

    controller.request(Map.of("eraseHistory", "true"));

    assertEquals(false, capturePostedBody(client).get("eraseHistory"));
  }

  @Test
  void request_anAbsentFlagIsFalse() {
    BackendApiClient client = mock(BackendApiClient.class);
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);

    controller.request(Map.of());

    assertEquals(false, capturePostedBody(client).get("eraseHistory"));
  }

  @Test
  void request_relaysTheBackendStatusRatherThanFlatteningItTo500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(eq(URI), any(), eq(Object.class)))
        .thenThrow(new BackendServiceException("already pending", new RuntimeException(), 409));
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);

    ResponseEntity<Object> response = controller.request(Map.of());

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
  }

  @Test
  void request_anUnexpectedFailureIs500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(eq(URI), any(), eq(Object.class)))
        .thenThrow(new IllegalStateException("boom"));
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);

    ResponseEntity<Object> response = controller.request(Map.of());

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void withdraw_relaysNoUserIdAndAnswers204() {
    BackendApiClient client = mock(BackendApiClient.class);
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);

    ResponseEntity<Object> response = controller.withdraw();

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(client).delete(URI, Void.class);
  }

  @Test
  void withdraw_relaysTheBackendStatus() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(eq(URI), eq(Void.class)))
        .thenThrow(new BackendServiceException("gone", new RuntimeException(), 404));
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);

    ResponseEntity<Object> response = controller.withdraw();

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void withdraw_anUnexpectedFailureIs500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(eq(URI), eq(Void.class))).thenThrow(new IllegalStateException("boom"));
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);

    ResponseEntity<Object> response = controller.withdraw();

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void card_publishesTheRequestForTheFragment() {
    BackendApiClient client = mock(BackendApiClient.class);
    Map<String, Object> pending = new HashMap<>();
    pending.put("status", "PENDING");
    when(client.get(
            eq(URI), ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any()))
        .thenReturn(pending);
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);
    Model model = new ConcurrentModel();

    String view = controller.card(model);

    assertEquals("fragments/profile-deletion-card :: card", view);
    assertEquals(pending, model.getAttribute("deletionRequest"));
  }

  @Test
  void card_saysSoWhenTheBackendIsDownRatherThanClaimingNoRequest() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(
            eq(URI), ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any()))
        .thenThrow(new BackendServiceException("down", new RuntimeException(), 503));
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);
    Model model = new ConcurrentModel();

    String view = controller.card(model);

    assertEquals("fragments/profile-deletion-card :: card", view);
    assertNull(model.getAttribute("deletionRequest"));
    assertEquals(true, model.getAttribute("deletionRequestUnavailable"));
  }

  @Test
  void card_marksTheStateAvailableOnASuccessfulLoad() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(
            eq(URI), ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any()))
        .thenReturn(null);
    DeletionRequestProxyController controller = new DeletionRequestProxyController(client);
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
  @SuppressWarnings("unchecked")
  private static Map<String, Object> capturePostedBody(BackendApiClient client) {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(client).post(eq(URI), captor.capture(), eq(Object.class));
    return (Map<String, Object>) captor.getValue();
  }
}
