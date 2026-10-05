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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.DecideDeletionRequestRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Mockito tests for {@link AdminDeletionRequestsPageController} (REQ-SEC-061, ADR-0181): a refusal
 * without a reason (missing, blank or non-string note) is rejected, and refusing and carrying out
 * relay to their own backend URIs.
 */
class AdminDeletionRequestsPageControllerTest {

  private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final String DECLINE_URI = "/api/v1/admin/deletion-requests/{id}/decline";
  private static final String EXECUTE_URI = "/api/v1/admin/deletion-requests/{id}/execute";

  @Test
  void decline_withoutTheNoteKey_is400AndNoBackendCall() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of());

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoPost(client);
  }

  @Test
  void decline_withABlankNote_is400AndNoBackendCall() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of("note", "   "));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoPost(client);
  }

  @Test
  void decline_withANonStringNote_is400AndNoBackendCall() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of("note", 42));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoPost(client);
  }

  @Test
  void decline_withAReason_relaysItAndNeverGrantsTheHistoryErasure() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    ResponseEntity<Object> response =
        controller.decline(
            REQUEST_ID, Map.of("note", "Open bank liabilities.", "grantHistoryErasure", true));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    DecideDeletionRequestRequest body =
        capturePostedBody(client, DECLINE_URI, AdminDeletionRequestDto.class);
    assertEquals("Open bank liabilities.", body.note());
    assertEquals(false, body.grantHistoryErasure());
  }

  @Test
  void decline_relaysTheBackendStatusRatherThanFlatteningItTo500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(any(String.class), any(), any(), any(Object[].class)))
        .thenThrow(new BackendServiceException("gone", new RuntimeException(), 404));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of("note", "Reason."));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void decline_anUnexpectedFailureIs500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(any(String.class), any(), any(), any(Object[].class)))
        .thenThrow(new IllegalStateException("boom"));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of("note", "Reason."));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void execute_readsTheHistoryErasureFromTheAdminsPayload() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    ResponseEntity<Object> response =
        controller.execute(REQUEST_ID, Map.of("grantHistoryErasure", true, "note", "Granted."));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    DecideDeletionRequestRequest body = capturePostedBody(client, EXECUTE_URI, Void.class);
    assertEquals(true, body.grantHistoryErasure());
  }

  @Test
  void execute_relaysNoNoteEvenWhenTheClientSendsOne() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    controller.execute(REQUEST_ID, Map.of("grantHistoryErasure", false, "note", "Anything."));

    assertThat(capturePostedBody(client, EXECUTE_URI, Void.class).note()).isNull();
  }

  @Test
  void execute_coercesAMissingOrMalformedFlagToFalse() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    controller.execute(REQUEST_ID, Map.of("grantHistoryErasure", "true"));

    assertEquals(false, capturePostedBody(client, EXECUTE_URI, Void.class).grantHistoryErasure());
  }

  @Test
  void execute_relaysTheNumericVersionAndDropsAMalformedOne() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    controller.execute(REQUEST_ID, Map.of("grantHistoryErasure", true, "version", 3));

    assertEquals(3L, capturePostedBody(client, EXECUTE_URI, Void.class).version());

    BackendApiClient other = mock(BackendApiClient.class);
    new AdminDeletionRequestsPageController(new IdentityBackendClient(other))
        .decline(REQUEST_ID, Map.of("note", "Reason.", "version", "x"));

    assertThat(capturePostedBody(other, DECLINE_URI, AdminDeletionRequestDto.class).version())
        .isNull();
  }

  @Test
  void execute_relaysTheBackendStatusRatherThanFlatteningItTo500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(any(String.class), any(), any(), any(Object[].class)))
        .thenThrow(new BackendServiceException("conflict", new RuntimeException(), 409));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    ResponseEntity<Object> response = controller.execute(REQUEST_ID, Map.of());

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
  }

  @Test
  void execute_anUnexpectedFailureIs500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(any(String.class), any(), any(), any(Object[].class)))
        .thenThrow(new IllegalStateException("boom"));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    ResponseEntity<Object> response = controller.execute(REQUEST_ID, Map.of());

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void page_rendersWithAnErrorBannerAndAnEmptyQueueWhenTheBackendIsDown() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(any(String.class), ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenThrow(new BackendServiceException("down", new RuntimeException(), 503));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));
    Model model = new ConcurrentModel();

    String view = controller.page(model);

    assertEquals("admin/deletion-requests", view);
    assertEquals(List.of(), model.getAttribute("requests"));
    assertEquals("admin.deletionRequests.error.load", model.getAttribute("error"));
  }

  @Test
  void rows_letsAFailurePropagateRatherThanPaintingAnEmptyQueue() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(any(String.class), ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenThrow(new BackendServiceException("down", new RuntimeException(), 503));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));

    assertThatThrownBy(() -> controller.rows(new ConcurrentModel()))
        .isInstanceOf(BackendServiceException.class);
  }

  @Test
  void rows_rendersTheFragmentOnASuccessfulLoad() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(any(String.class), ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenReturn(List.of());
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(new IdentityBackendClient(client));
    Model model = new ConcurrentModel();

    String view = controller.rows(model);

    assertEquals("admin/deletion-requests :: rows", view);
    assertEquals(List.of(), model.getAttribute("requests"));
  }

  /**
   * Asserts the controller made no backend write.
   *
   * @param client the mocked client
   */
  private static void verifyNoPost(BackendApiClient client) {
    verify(client, never())
        .post(ArgumentMatchers.<String>any(), any(), ArgumentMatchers.<Class<Object>>any());
    verify(client, never())
        .post(ArgumentMatchers.<String>any(), any(), ArgumentMatchers.<Class<Object>>any());
    verify(client, never())
        .post(
            ArgumentMatchers.<String>any(),
            any(),
            ArgumentMatchers.<Class<Object>>any(),
            any(Object[].class));
  }

  /**
   * Captures the body the controller posted to one URI.
   *
   * @param client the mocked client
   * @param uri the expected backend URI template, expanded with {@link #REQUEST_ID}
   * @param responseType the response type the call decodes
   * @return the posted body
   */
  private static DecideDeletionRequestRequest capturePostedBody(
      BackendApiClient client, String uri, Class<?> responseType) {
    ArgumentCaptor<DecideDeletionRequestRequest> captor = ArgumentCaptor.captor();
    verify(client).post(eq(uri), captor.capture(), eq(responseType), eq(REQUEST_ID));
    return captor.getValue();
  }
}
