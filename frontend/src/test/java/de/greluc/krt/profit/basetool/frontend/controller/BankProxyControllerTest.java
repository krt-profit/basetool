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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BankProxyControllerTest {

  private BackendApiClient backendApiClient;
  private BankProxyController controller;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    controller = new BankProxyController(backendApiClient);
  }

  @Test
  void bookDeposit_ShouldForwardBodyToBackend() {
    // Given
    Map<String, Object> body = Map.of("accountId", "a", "holderId", "h", "amount", 100);
    when(backendApiClient.post("/api/v1/bank/deposits", body, Map.class))
        .thenReturn(Map.of("id", "x"));

    // When
    Map<String, Object> result = controller.bookDeposit(body);

    // Then
    assertEquals("x", result.get("id"));
    verify(backendApiClient).post("/api/v1/bank/deposits", body, Map.class);
  }

  @Test
  void bookTransfer_ShouldReturnEmptyMapForBodylessResponse() {
    // Given
    Map<String, Object> body = Map.of("sourceAccountId", "a");
    when(backendApiClient.post("/api/v1/bank/transfers", body, Map.class)).thenReturn(null);

    // When
    Map<String, Object> result = controller.bookTransfer(body);

    // Then
    assertEquals(Map.of(), result);
  }

  @Test
  void reverseTransaction_ShouldForwardEmptyMapWhenBodyMissing() {
    // Given
    UUID id = UUID.randomUUID();
    when(backendApiClient.post(
            eq("/api/v1/bank/transactions/" + id + "/reversal"), eq(Map.of()), eq(Map.class)))
        .thenReturn(Map.of());

    // When
    controller.reverseTransaction(id, null);

    // Then
    verify(backendApiClient)
        .post("/api/v1/bank/transactions/" + id + "/reversal", Map.of(), Map.class);
  }

  @Test
  void renameAccount_ShouldPatchBackend() {
    // Given
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("name", "Neu", "version", 1);
    when(backendApiClient.patch("/api/v1/bank/accounts/" + id, body, Map.class))
        .thenReturn(Map.of("name", "Neu"));

    // When
    Map<String, Object> result = controller.renameAccount(id, body);

    // Then
    assertEquals("Neu", result.get("name"));
  }

  @Test
  void closeAndReopen_ShouldPostLifecycleEndpoints() {
    // Given
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("version", 2);

    // When
    controller.closeAccount(id, body);
    controller.reopenAccount(id, body);

    // Then
    verify(backendApiClient).post("/api/v1/bank/accounts/" + id + "/close", body, Map.class);
    verify(backendApiClient).post("/api/v1/bank/accounts/" + id + "/reopen", body, Map.class);
  }

  @Test
  void updateHolder_ShouldPatchBackend() {
    // Given
    UUID id = UUID.randomUUID();
    Map<String, Object> body = Map.of("active", false, "version", 0);

    // When
    controller.updateHolder(id, body);

    // Then
    verify(backendApiClient).patch("/api/v1/bank/holders/" + id, body, Map.class);
  }

  @Test
  void searchAccounts_ShouldForwardActiveNameSortedSearch_andUnwrapContent() {
    // Given a matching account page from the backend
    Map<String, Object> row = Map.of("id", "acc-1", "accountNo", "KB-0001", "name", "Phoenix");
    when(backendApiClient.get(org.mockito.ArgumentMatchers.anyString(), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(row), 0, 50, 1, 1, List.of()));

    // When
    List<Map<String, Object>> result = controller.searchAccounts("pho");

    // Then — the content is unwrapped, and the forwarded URI carries the picker's fixed filters
    assertEquals(1, result.size());
    assertEquals("KB-0001", result.get(0).get("accountNo"));
    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef());
    String uri = uriCaptor.getValue();
    assertTrue(uri.startsWith("/api/v1/bank/accounts"), "forwards to the account list endpoint");
    assertTrue(uri.contains("query=pho"), "forwards the typed query");
    assertTrue(uri.contains("status=ACTIVE"), "the picker searches active accounts only");
    assertTrue(uri.contains("sort=name,asc"), "results are name-sorted");
  }

  @Test
  void searchAccounts_NullQuery_matchesAll_andEmptyResponseDegradesToEmptyList() {
    // Given a null (browse-mode) query and a null backend response
    when(backendApiClient.get(org.mockito.ArgumentMatchers.anyString(), anyTypeRef()))
        .thenReturn(null);

    // When
    List<Map<String, Object>> result = controller.searchAccounts(null);

    // Then — the null query is normalised to an empty match-all filter and the null page degrades
    // to an empty list (the picker shows "no matches", never throws).
    assertEquals(List.of(), result);
    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef());
    assertTrue(uriCaptor.getValue().contains("query="), "the null query is forwarded as empty");
  }

  @Test
  void grantLifecycle_ShouldTargetCompositeKeyPaths() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    Map<String, Object> flags =
        Map.of("canDeposit", true, "canWithdraw", false, "canTransfer", true, "version", 3);

    // When
    controller.updateGrant(userId, accountId, flags);
    controller.deleteGrant(userId, accountId);

    // Then
    verify(backendApiClient)
        .patch("/api/v1/bank/grants/" + userId + "/" + accountId, flags, Map.class);
    verify(backendApiClient).delete("/api/v1/bank/grants/" + userId + "/" + accountId, Void.class);
  }
}
