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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.bank.client.BankBackendClient;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankAccountLifecycleRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingOutcomeDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankDepositRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankGrantDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankTransactionDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankTransferRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.RenameBankAccountRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.ReverseBankTransactionRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.UpdateBankGrantRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.UpdateBankHolderRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.web.PickerSearch;
import java.math.BigDecimal;
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
    controller = new BankProxyController(new BankBackendClient(backendApiClient));
  }

  @Test
  void bookDeposit_ShouldForwardBodyToBackend() {
    BankDepositRequest body =
        new BankDepositRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigDecimal.valueOf(100),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    BankTransactionDto booked = new BankTransactionDto(UUID.randomUUID(), "DEPOSIT", null, null);
    when(backendApiClient.post("/api/v1/bank/deposits", body, BankTransactionDto.class))
        .thenReturn(booked);

    Object result = controller.bookDeposit(body);

    assertSame(booked, result);
    verify(backendApiClient).post("/api/v1/bank/deposits", body, BankTransactionDto.class);
  }

  @Test
  void bookTransfer_ShouldReturnEmptyMapForBodylessResponse() {
    BankTransferRequest body =
        new BankTransferRequest(UUID.randomUUID(), null, null, null, null, null, null, null, null);
    when(backendApiClient.post("/api/v1/bank/transfers", body, BankBookingOutcomeDto.class))
        .thenReturn(null);

    Object result = controller.bookTransfer(body);

    assertEquals(Map.of(), result);
  }

  @Test
  void reverseTransaction_ShouldForwardAnEmptyNoteWhenBodyMissing() {
    UUID id = UUID.randomUUID();
    ReverseBankTransactionRequest empty = new ReverseBankTransactionRequest(null);
    when(backendApiClient.post(
            eq("/api/v1/bank/transactions/{id}/reversal"),
            eq(empty),
            eq(BankTransactionDto.class),
            eq(id)))
        .thenReturn(new BankTransactionDto(id, "REVERSAL", null, null));

    controller.reverseTransaction(id, null);

    verify(backendApiClient)
        .post("/api/v1/bank/transactions/{id}/reversal", empty, BankTransactionDto.class, id);
  }

  @Test
  void renameAccount_ShouldPatchBackend() {
    UUID id = UUID.randomUUID();
    RenameBankAccountRequest body = new RenameBankAccountRequest("Neu", 1L);
    BankAccountDto renamed = mock(BankAccountDto.class);
    when(backendApiClient.patch("/api/v1/bank/accounts/{id}", body, BankAccountDto.class, id))
        .thenReturn(renamed);

    Object result = controller.renameAccount(id, body);

    assertSame(renamed, result);
  }

  @Test
  void closeAndReopen_ShouldPostLifecycleEndpoints() {
    UUID id = UUID.randomUUID();
    BankAccountLifecycleRequest body = new BankAccountLifecycleRequest(2L);

    controller.closeAccount(id, body);
    controller.reopenAccount(id, body);

    verify(backendApiClient)
        .post("/api/v1/bank/accounts/{id}/close", body, BankAccountDto.class, id);
    verify(backendApiClient)
        .post("/api/v1/bank/accounts/{id}/reopen", body, BankAccountDto.class, id);
  }

  @Test
  void updateHolder_ShouldPatchBackend() {
    UUID id = UUID.randomUUID();
    UpdateBankHolderRequest body = new UpdateBankHolderRequest(false, 0L);

    controller.updateHolder(id, body);

    verify(backendApiClient).patch("/api/v1/bank/holders/{id}", body, BankHolderDto.class, id);
  }

  @Test
  void searchAccounts_ShouldForwardActiveNameSortedSearch_andUnwrapContent() {
    BankAccountDto row = mock(BankAccountDto.class);
    when(row.accountNo()).thenReturn("KB-0001");
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.anyString(),
            anyTypeRef(),
            org.mockito.ArgumentMatchers.<Object>any()))
        .thenReturn(new PageResponse<>(List.of(row), 0, 50, 1, 1, List.of()));

    List<BankAccountDto> result = controller.searchAccounts("pho");

    assertEquals(1, result.size());
    assertEquals("KB-0001", result.get(0).accountNo());
    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> varCaptor = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), varCaptor.capture());
    String uri = uriCaptor.getValue();
    assertTrue(uri.startsWith("/api/v1/bank/accounts"), "forwards to the account list endpoint");
    assertTrue(uri.contains("query={query}"), "the query rides as a single-encoded URI variable");
    assertTrue(uri.contains("status=ACTIVE"), "the picker searches active accounts only");
    assertTrue(uri.contains("sort=name,asc"), "results are name-sorted");
    assertEquals("pho", varCaptor.getValue(), "the raw query value is forwarded verbatim");
  }

  @Test
  void searchAccounts_passesMultiWordQueryAsUriVariable() {
    when(backendApiClient.get(
            eq(
                "/api/v1/bank/accounts?status=ACTIVE&size="
                    + PickerSearch.PAGE_SIZE
                    + "&sort=name,asc&query={query}"),
            anyTypeRef(),
            eq("Phoenix Reserve")))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    controller.searchAccounts("Phoenix Reserve");

    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/bank/accounts?status=ACTIVE&size="
                    + PickerSearch.PAGE_SIZE
                    + "&sort=name,asc&query={query}"),
            anyTypeRef(),
            eq("Phoenix Reserve"));
  }

  @Test
  void searchAccounts_NullQuery_matchesAll_andEmptyResponseDegradesToEmptyList() {
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.anyString(),
            anyTypeRef(),
            org.mockito.ArgumentMatchers.<Object>any()))
        .thenReturn(null);

    List<BankAccountDto> result = controller.searchAccounts(null);

    assertEquals(List.of(), result);
    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> varCaptor = ArgumentCaptor.forClass(Object.class);
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), varCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("query={query}"), "the query rides as a URI variable");
    assertEquals("", varCaptor.getValue(), "the null query is normalised to the empty filter");
  }

  @Test
  void grantLifecycle_ShouldTargetCompositeKeyPaths() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UpdateBankGrantRequest flags = new UpdateBankGrantRequest(true, false, true, 3L);

    controller.updateGrant(userId, accountId, flags);
    controller.deleteGrant(userId, accountId);

    verify(backendApiClient)
        .patch(
            "/api/v1/bank/grants/{userId}/{accountId}",
            flags,
            BankGrantDto.class,
            userId,
            accountId);
    verify(backendApiClient)
        .delete("/api/v1/bank/grants/{userId}/{accountId}", Void.class, userId, accountId);
  }
}
