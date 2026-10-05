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

package de.greluc.krt.profit.basetool.frontend.bank.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAccountLifecycleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankDepositRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderTransferRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CancelBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ConfirmBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateBankAccountRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateBankGrantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitBalanceTargetRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.RegisterBankHolderRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.RejectBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.RenameBankAccountRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ReverseBankTransactionRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetBankApprovalLimitRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetBankBalanceTargetRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SetCartelApprovalTiersRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateBankGrantRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateBankHolderRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link BankBackendClient} sends (plan F3), each the exact URI and body the bank
 * controllers sent before the client existed.
 */
class BankBackendClientTest {

  private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
  private static final UUID USER = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
  private static final Instant FROM = Instant.parse("2026-09-01T10:15:30Z");
  private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
  private static final String PERIOD = "from=2026-09-01T10:15:30Z&to=2026-10-01T00:00:00Z";
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";
  private static final UUID OTHER = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
  private static final BigDecimal AMOUNT = new BigDecimal("1000");
  private static final String VERSIONED = "{\"id\":\"" + ID + "\",\"version\":2}";
  private static final String TRANSACTION =
      "{\"id\":\""
          + ID
          + "\",\"type\":\"DEPOSIT\",\"note\":null,\"createdAt\":\"2026-09-01T10:15:30Z\"}";

  private BackendClientHarness backend;
  private BankBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new BankBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void staffReads() {
    backend.answerJson("{}");
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");

    client.dashboard();
    assertThat(client.holders()).isEmpty();
    client.holder(ID);
    client.holderBookings(ID, 2);
    assertThat(client.activeOrgUnitsAllKinds()).isEmpty();
    assertThat(client.cachedActiveOrgUnitsAllKinds()).isEmpty();
    client.account(ID);
    client.transferFeeRate();

    backend.expect("GET", "/api/v1/bank/dashboard");
    backend.expect("GET", "/api/v1/bank/holders");
    backend.expect("GET", "/api/v1/bank/holders/" + ID);
    backend.expect("GET", "/api/v1/bank/holders/" + ID + "/transactions?page=2&size=20");
    backend.expect("GET", "/api/v1/org-units/active-all-kinds");
    backend.expect("GET", "/api/v1/org-units/active-all-kinds");
    backend.expect("GET", "/api/v1/bank/accounts/" + ID);
    backend.expect("GET", "/api/v1/bank/transfer-fee-rate");
  }

  @Test
  void accountHistoryAndSeriesCarryThePeriod() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{}");

    client.accountBookings(ID, 1, 50, FROM, TO);
    client.balanceSeries(ID, FROM, TO);

    backend.expect("GET", "/api/v1/bank/accounts/" + ID + "/transactions?page=1&size=50&" + PERIOD);
    backend.expect("GET", "/api/v1/bank/accounts/" + ID + "/balance-series?" + PERIOD);
  }

  @Test
  void accountListsAndQueues() {
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    client.grants(USER, ID);
    client.grants(null, ID);
    client.grants(null, null);
    client.accountPage(3, 25);
    client.cartelAccountPage();
    client.activeAccountProbe();
    client.bookingRequests(List.of("PENDING", "CONFIRMED"), 200);
    client.searchActiveAccounts("a&b c", 11);

    backend.expect("GET", "/api/v1/bank/grants?userId=" + USER);
    backend.expect("GET", "/api/v1/bank/grants?accountId=" + ID);
    backend.expect("GET", "/api/v1/bank/grants");
    backend.expect("GET", "/api/v1/bank/accounts?page=3&size=25&sort=name,asc");
    backend.expect("GET", "/api/v1/bank/accounts?type=CARTEL&size=1");
    backend.expect("GET", "/api/v1/bank/accounts?status=ACTIVE&size=1");
    backend.expect("GET", "/api/v1/bank/requests?size=200&status=PENDING&status=CONFIRMED");
    backend.expect(
        "GET", "/api/v1/bank/accounts?status=ACTIVE&size=11&sort=name,asc&query=a%26b%20c");
  }

  @Test
  void staffWritesSendTheBackendRecordsAndDecodeTheAnswers() {
    backend.answerJson(TRANSACTION);
    backend.answerJson("{\"transaction\":null,\"pendingRequest\":" + VERSIONED + "}");
    backend.answerJson("{\"transaction\":" + TRANSACTION + ",\"pendingRequest\":null}");
    for (int i = 0; i < 16; i++) {
      backend.answerJson(VERSIONED);
    }
    backend.answerEmpty();

    assertThat(
            client.deposit(
                new BankDepositRequest(
                    ID, USER, AMOUNT, "Einlage", null, false, null, null, null, null)))
        .extracting(BankTransactionDto::type)
        .isEqualTo("DEPOSIT");
    assertThat(
            client.withdrawal(
                new BankWithdrawalRequest(
                    ID, USER, AMOUNT, null, "Grund", null, OTHER, null, true, null)))
        .extracting(outcome -> outcome.pendingRequest().version())
        .isEqualTo(2L);
    assertThat(
            client.transfer(
                new BankTransferRequest(ID, USER, OTHER, USER, AMOUNT, null, null, null, null)))
        .extracting(outcome -> outcome.transaction().id())
        .isEqualTo(ID);
    client.holderTransfer(new BankHolderTransferRequest(USER, OTHER, AMOUNT, "Umbuchung"));
    client.reverseTransaction(ID, new ReverseBankTransactionRequest(null));
    client.confirmRequest(ID, new ConfirmBankBookingRequest(USER, null, true, null, 1L));
    client.rejectRequest(ID, new RejectBankBookingRequest("Nein", 1L));
    assertThat(client.createAccount(new CreateBankAccountRequest("Kasse", "SPECIAL", null, null)))
        .extracting(BankAccountDto::version)
        .isEqualTo(2L);
    client.renameAccount(ID, new RenameBankAccountRequest("Neu", 1L));
    client.setBalanceTarget(ID, new SetBankBalanceTargetRequest(null, 1L));
    client.setApprovalTiers(ID, new SetCartelApprovalTiersRequest(AMOUNT, null, 1L));
    client.closeAccount(ID, new BankAccountLifecycleRequest(1L));
    client.reopenAccount(ID, new BankAccountLifecycleRequest(1L));
    client.registerHolder(new RegisterBankHolderRequest(USER));
    client.updateHolder(ID, new UpdateBankHolderRequest(false, 1L));
    client.createGrant(new CreateBankGrantRequest(USER, ID, true, false, null));
    client.updateGrant(USER, ID, new UpdateBankGrantRequest(true, true, false, 3L));
    client.deleteGrant(USER, ID);

    String accounts = "/api/v1/bank/accounts/" + ID;
    backend.expect(
        "POST",
        "/api/v1/bank/deposits",
        "{\"accountId\":\""
            + ID
            + "\",\"holderId\":\""
            + USER
            + "\",\"amount\":1000,\"note\":\"Einlage\",\"staffNote\":null,"
            + "\"splitEnabled\":false,\"splitPercent\":null,\"counterpartyUserId\":null,"
            + "\"counterpartyOrgUnitId\":null,\"counterpartyExternalName\":null}");
    backend.expect(
        "POST",
        "/api/v1/bank/withdrawals",
        "{\"accountId\":\""
            + ID
            + "\",\"holderId\":\""
            + USER
            + "\",\"amount\":1000,\"note\":null,\"justification\":\"Grund\",\"staffNote\":null,"
            + "\"counterpartyUserId\":\""
            + OTHER
            + "\",\"counterpartyOrgUnitId\":null,\"feeInclusive\":true,"
            + "\"counterpartyExternalName\":null}");
    backend.expect(
        "POST",
        "/api/v1/bank/transfers",
        "{\"sourceAccountId\":\""
            + ID
            + "\",\"sourceHolderId\":\""
            + USER
            + "\",\"destinationAccountId\":\""
            + OTHER
            + "\",\"destinationHolderId\":\""
            + USER
            + "\",\"amount\":1000,\"note\":null,\"justification\":null,\"staffNote\":null,"
            + "\"feeInclusive\":null}");
    backend.expect(
        "POST",
        "/api/v1/bank/holders/transfer",
        "{\"sourceHolderId\":\""
            + USER
            + "\",\"destinationHolderId\":\""
            + OTHER
            + "\",\"amount\":1000,\"note\":\"Umbuchung\"}");
    backend.expect("POST", "/api/v1/bank/transactions/" + ID + "/reversal", "{\"note\":null}");
    backend.expect(
        "POST",
        "/api/v1/bank/requests/" + ID + "/confirm",
        "{\"holderId\":\""
            + USER
            + "\",\"destinationHolderId\":null,\"ownerApprovalConfirmed\":true,"
            + "\"staffNote\":null,\"version\":1}");
    backend.expect(
        "POST", "/api/v1/bank/requests/" + ID + "/reject", "{\"reason\":\"Nein\",\"version\":1}");
    backend.expect(
        "POST",
        "/api/v1/bank/accounts",
        "{\"name\":\"Kasse\",\"type\":\"SPECIAL\",\"orgUnitId\":null,\"areaName\":null}");
    backend.expect("PATCH", accounts, "{\"name\":\"Neu\",\"version\":1}");
    backend.expect("PATCH", accounts + "/balance-target", "{\"target\":null,\"version\":1}");
    backend.expect(
        "PATCH",
        accounts + "/approval-tiers",
        "{\"employeeCeiling\":1000,\"areaLeadCeiling\":null,\"version\":1}");
    backend.expect("POST", accounts + "/close", "{\"version\":1}");
    backend.expect("POST", accounts + "/reopen", "{\"version\":1}");
    backend.expect("POST", "/api/v1/bank/holders", "{\"userId\":\"" + USER + "\"}");
    backend.expect("PATCH", "/api/v1/bank/holders/" + ID, "{\"active\":false,\"version\":1}");
    backend.expect(
        "POST",
        "/api/v1/bank/grants",
        "{\"userId\":\""
            + USER
            + "\",\"accountId\":\""
            + ID
            + "\",\"canDeposit\":true,\"canWithdraw\":false,\"canTransfer\":null}");
    backend.expect(
        "PATCH",
        "/api/v1/bank/grants/" + USER + "/" + ID,
        "{\"canDeposit\":true,\"canWithdraw\":true,\"canTransfer\":false,\"version\":3}");
    backend.expect("DELETE", "/api/v1/bank/grants/" + USER + "/" + ID, null);
  }

  @Test
  void exportsAndWipeReset() {
    byte[] pdf = {0x25, 0x50, 0x44, 0x46};
    backend.answerBytes("application/pdf", pdf);
    backend.answerBytes("application/pdf", pdf);
    backend.answerBytes("application/pdf", pdf);
    backend.answerJson("{\"accountsReset\":3,\"holderStashesZeroed\":2}");

    assertThat(client.accountStatement(ID, FROM, TO, "Europe/Berlin")).isEqualTo(pdf);
    assertThat(client.threeMonthReport(" ")).isEqualTo(pdf);
    assertThat(client.orgUnitAccountStatement(ID, FROM, TO, "Europe/Berlin")).isEqualTo(pdf);
    assertThat(client.wipeReset()).isNotNull();

    RecordedRequest statement =
        backend.expect("GET", "/api/v1/bank/accounts/" + ID + "/statement?" + PERIOD);
    assertThat(statement.getHeader("X-User-Time-Zone")).isEqualTo("Europe/Berlin");
    RecordedRequest report = backend.expect("GET", "/api/v1/bank/export/three-month-report");
    assertThat(report.getHeader("X-User-Time-Zone")).isNull();
    RecordedRequest orgUnitStatement =
        backend.expect("GET", "/api/v1/org-units/bank/accounts/" + ID + "/statement?" + PERIOD);
    assertThat(orgUnitStatement.getHeader("X-User-Time-Zone")).isEqualTo("Europe/Berlin");
    backend.expect("POST", "/api/v1/bank/admin/wipe-reset", "{}");
  }

  @Test
  void orgUnitReads() {
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{}");

    assertThat(client.orgUnitBalances()).isEmpty();
    assertThat(client.ownOrgUnitRequests()).isEmpty();
    assertThat(client.foreignOrgUnitRequests()).isEmpty();
    assertThat(client.orgUnitTransferTargets()).isEmpty();
    client.orgUnitAccount(ID);
    client.orgUnitAccountSettings(ID);
    client.orgUnitAccountBookings(ID, 0, 100, FROM, TO);
    client.orgUnitBalanceSeries(ID, FROM, TO);

    String account = "/api/v1/org-units/bank/accounts/" + ID;
    backend.expect("GET", "/api/v1/org-units/bank/balances");
    backend.expect("GET", "/api/v1/org-units/bank/requests");
    backend.expect("GET", "/api/v1/org-units/bank/requests/foreign");
    backend.expect("GET", "/api/v1/org-units/bank/transfer-targets");
    backend.expect("GET", account);
    backend.expect("GET", account + "/settings");
    backend.expect("GET", account + "/transactions?page=0&size=100&" + PERIOD);
    backend.expect("GET", account + "/balance-series?" + PERIOD);
  }

  @Test
  void orgUnitWritesSendTheBackendRecordsAndDecodeTheAnswers() {
    for (int i = 0; i < 20; i++) {
      backend.answerJson(VERSIONED);
    }
    SetBankApprovalLimitRequest limit = new SetBankApprovalLimitRequest(AMOUNT);

    assertThat(
            client.createOrgUnitRequest(
                new CreateBankBookingRequest(
                    ID, "WITHDRAWAL", null, AMOUNT, "Sprit", null, null, null, USER, OTHER)))
        .extracting(BankBookingRequestDto::version)
        .isEqualTo(2L);
    client.cancelOrgUnitRequest(ID, new CancelBankBookingRequest(1L));
    client.updateOrgUnitRequest(
        ID, new UpdateBankBookingRequest(AMOUNT, null, null, OTHER, null, null, 1L));
    assertThat(client.setOrgUnitBalanceTarget(ID, new OrgUnitBalanceTargetRequest(AMOUNT, 1L)))
        .isNotNull();
    client.addRoleVisibility(ID, "KRT_MEMBER", Map.of());
    client.removeRoleVisibility(ID, "KRT_MEMBER");
    client.setAllMembersVisibility(ID, true, Map.of());
    client.setAreaMembersVisibility(ID, false, Map.of());
    client.addUserVisibility(ID, USER, Map.of());
    client.removeUserVisibility(ID, USER);
    client.setRoleApprovalLimit(ID, "OFFICER", limit);
    client.clearRoleApprovalLimit(ID, "OFFICER");
    client.setAllMembersApprovalLimit(ID, limit);
    client.clearAllMembersApprovalLimit(ID);
    client.setAreaMembersApprovalLimit(ID, limit);
    client.clearAreaMembersApprovalLimit(ID);
    client.setUserApprovalLimit(ID, USER, limit);
    client.clearUserApprovalLimit(ID, USER);
    assertThat(client.grantOwnerApproval(ID, Map.of()))
        .extracting(BankBookingRequestDto::version)
        .isEqualTo(2L);
    client.revokeOwnerApproval(ID);

    String requests = "/api/v1/org-units/bank/requests";
    String account = "/api/v1/org-units/bank/accounts/" + ID;
    String limitJson = "{\"limit\":1000}";
    backend.expect(
        "POST",
        requests,
        "{\"sourceAccountId\":\""
            + ID
            + "\",\"type\":\"WITHDRAWAL\",\"targetAccountId\":null,\"amount\":1000,"
            + "\"note\":\"Sprit\",\"justification\":null,\"splitEnabled\":null,"
            + "\"splitPercent\":null,\"counterpartyUserId\":\""
            + USER
            + "\",\"counterpartyOrgUnitId\":\""
            + OTHER
            + "\"}");
    backend.expect("POST", requests + "/" + ID + "/cancel", "{\"version\":1}");
    backend.expect(
        "PUT",
        requests + "/" + ID,
        "{\"amount\":1000,\"note\":null,\"justification\":null,\"targetAccountId\":\""
            + OTHER
            + "\",\"counterpartyUserId\":null,\"counterpartyOrgUnitId\":null,\"version\":1}");
    backend.expect("PUT", account + "/balance-target", "{\"target\":1000,\"version\":1}");
    backend.expect("POST", account + "/visibility/role/KRT_MEMBER", "{}");
    backend.expect("DELETE", account + "/visibility/role/KRT_MEMBER", null);
    backend.expect("PUT", account + "/visibility/all-members/true", "{}");
    backend.expect("PUT", account + "/visibility/area-members/false", "{}");
    backend.expect("POST", account + "/visibility/user/" + USER, "{}");
    backend.expect("DELETE", account + "/visibility/user/" + USER, null);
    backend.expect("PUT", account + "/approval-limit/role/OFFICER", limitJson);
    backend.expect("DELETE", account + "/approval-limit/role/OFFICER", null);
    backend.expect("PUT", account + "/approval-limit/all-members", limitJson);
    backend.expect("DELETE", account + "/approval-limit/all-members", null);
    backend.expect("PUT", account + "/approval-limit/area-members", limitJson);
    backend.expect("DELETE", account + "/approval-limit/area-members", null);
    backend.expect("PUT", account + "/approval-limit/user/" + USER, limitJson);
    backend.expect("DELETE", account + "/approval-limit/user/" + USER, null);
    backend.expect("POST", requests + "/" + ID + "/owner-approval", "{}");
    backend.expect("DELETE", requests + "/" + ID + "/owner-approval", null);
  }
}
