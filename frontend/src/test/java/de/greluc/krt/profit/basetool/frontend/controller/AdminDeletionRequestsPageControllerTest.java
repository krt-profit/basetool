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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.HashMap;
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
 * Mockito tests for {@link AdminDeletionRequestsPageController} (REQ-SEC-061, ADR-0181).
 *
 * <p>Two of these are the reason the class has tests at all.
 *
 * <p><b>A refusal without a reason is refused.</b> Art. 12(4) obliges the controller to tell the
 * requester <em>why</em>, so the note is mandatory in three layers — here, in the backend, and as a
 * CHECK constraint in the database. This class pins the outermost one, including the shapes a
 * hand-written client actually produces: the key missing, the value blank, the value not a string.
 *
 * <p><b>Refusing and carrying out are separate endpoints.</b> They are not one endpoint with a
 * decision parameter, because a parameter is a thing a mistake can flip and one of the two outcomes
 * is irreversible. The tests assert each path relays to its own backend URI.
 */
class AdminDeletionRequestsPageControllerTest {

  private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final String DECLINE_URI =
      "/api/v1/admin/deletion-requests/00000000-0000-0000-0000-0000000000aa/decline";
  private static final String EXECUTE_URI =
      "/api/v1/admin/deletion-requests/00000000-0000-0000-0000-0000000000aa/execute";

  @Test
  void decline_withoutTheNoteKey_is400AndNoBackendCall() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of());

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoPost(client);
  }

  @Test
  void decline_withABlankNote_is400AndNoBackendCall() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of("note", "   "));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoPost(client);
  }

  @Test
  void decline_withANonStringNote_is400AndNoBackendCall() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of("note", 42));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    verifyNoPost(client);
  }

  @Test
  void decline_withAReason_relaysItAndNeverGrantsTheHistoryErasure() {
    // A refusal cannot also grant the extra wish; the flag is pinned false rather than read from
    // the payload so a client cannot produce that combination at all.
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    ResponseEntity<Object> response =
        controller.decline(
            REQUEST_ID, Map.of("note", "Open bank liabilities.", "grantHistoryErasure", true));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<String, Object> body = capturePostedBody(client, DECLINE_URI);
    assertEquals("Open bank liabilities.", body.get("note"));
    assertEquals(false, body.get("grantHistoryErasure"));
  }

  @Test
  void decline_relaysTheBackendStatusRatherThanFlatteningItTo500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(any(String.class), any(), eq(Object.class)))
        .thenThrow(new BackendServiceException("gone", new RuntimeException(), 404));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of("note", "Reason."));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  void decline_anUnexpectedFailureIs500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(any(String.class), any(), eq(Object.class)))
        .thenThrow(new IllegalStateException("boom"));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    ResponseEntity<Object> response = controller.decline(REQUEST_ID, Map.of("note", "Reason."));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void execute_readsTheHistoryErasureFromTheAdminsPayload() {
    // The member's wish is recorded on the request row; whether it is granted is the admin's
    // answer, and that is what this flag carries (decision 6, @greluc).
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    ResponseEntity<Object> response =
        controller.execute(REQUEST_ID, Map.of("grantHistoryErasure", true, "note", "Granted."));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<String, Object> body = capturePostedBody(client, EXECUTE_URI);
    assertEquals(true, body.get("grantHistoryErasure"));
    assertEquals("Granted.", body.get("note"));
  }

  @Test
  void execute_coercesAMissingOrMalformedFlagToFalse() {
    // Anything that is not a literal true means "the admin did not grant the extra erasure", which
    // is the conservative reading: the anonymisation it triggers cannot be undone.
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    controller.execute(REQUEST_ID, Map.of("grantHistoryErasure", "true"));

    assertEquals(false, capturePostedBody(client, EXECUTE_URI).get("grantHistoryErasure"));
  }

  @Test
  void execute_aBlankNoteIsRelayedAsNullRatherThanAsWhitespace() {
    // An empty decision note that is stored as "   " reads as a reason nobody can make sense of;
    // null reads as "no note", which is the truth.
    BackendApiClient client = mock(BackendApiClient.class);
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);
    Map<String, Object> payload = new HashMap<>();
    payload.put("grantHistoryErasure", false);
    payload.put("note", "  ");

    controller.execute(REQUEST_ID, payload);

    assertNull(capturePostedBody(client, EXECUTE_URI).get("note"));
  }

  @Test
  void execute_relaysTheBackendStatusRatherThanFlatteningItTo500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(any(String.class), any(), eq(Object.class)))
        .thenThrow(new BackendServiceException("conflict", new RuntimeException(), 409));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    ResponseEntity<Object> response = controller.execute(REQUEST_ID, Map.of());

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
  }

  @Test
  void execute_anUnexpectedFailureIs500() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.post(any(String.class), any(), eq(Object.class)))
        .thenThrow(new IllegalStateException("boom"));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);

    ResponseEntity<Object> response = controller.execute(REQUEST_ID, Map.of());

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
  }

  @Test
  void page_rendersWithAnErrorBannerAndAnEmptyQueueWhenTheBackendIsDown() {
    // A half-loaded admin page beats an error page here: the rest of the admin area's queues behave
    // the same way, and the admin can still navigate.
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(any(String.class), ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenThrow(new BackendServiceException("down", new RuntimeException(), 503));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);
    Model model = new ConcurrentModel();

    String view = controller.page(model);

    assertEquals("admin/deletion-requests", view);
    assertEquals(List.of(), model.getAttribute("requests"));
    assertEquals("error.loadFailed", model.getAttribute("error"));
  }

  @Test
  void rows_rendersTheFragmentAndCarriesNoErrorAttribute() {
    // The fragment is swapped into a page that already shows its own banner; a second error
    // attribute in the fragment's model would render a banner inside the table.
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(any(String.class), ArgumentMatchers.<ParameterizedTypeReference<Object>>any()))
        .thenThrow(new BackendServiceException("down", new RuntimeException(), 503));
    AdminDeletionRequestsPageController controller =
        new AdminDeletionRequestsPageController(client);
    Model model = new ConcurrentModel();

    String view = controller.rows(model);

    assertEquals("admin/deletion-requests :: rows", view);
    assertEquals(List.of(), model.getAttribute("requests"));
    assertFalse(model.containsAttribute("error"));
  }

  /**
   * Asserts the controller made no backend write.
   *
   * @param client the mocked client
   */
  private static void verifyNoPost(BackendApiClient client) {
    verify(client, never())
        .post(ArgumentMatchers.<String>any(), any(), ArgumentMatchers.<Class<Object>>any());
  }

  /**
   * Captures the body the controller posted to one URI.
   *
   * @param client the mocked client
   * @param uri the expected backend URI
   * @return the posted body
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> capturePostedBody(BackendApiClient client, String uri) {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(client).post(eq(uri), captor.capture(), eq(Object.class));
    return (Map<String, Object>) captor.getValue();
  }
}
