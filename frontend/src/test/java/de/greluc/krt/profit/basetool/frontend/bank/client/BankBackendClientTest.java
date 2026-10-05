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

import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
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
  private static final String ANSWER = "{\"id\":\"" + ID + "\",\"version\":2}";
  private static final Map<String, Object> BODY = Map.of("version", 1);
  private static final String BODY_JSON = "{\"version\":1}";

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
  void staffRelaysForwardTheBodyAndReturnTheAnswer() {
    for (int i = 0; i < 17; i++) {
      backend.answerJson(ANSWER);
    }
    backend.answerEmpty();

    assertThat(client.deposit(BODY)).containsEntry("version", 2);
    client.withdrawal(BODY);
    client.transfer(BODY);
    client.holderTransfer(BODY);
    client.reverseTransaction(ID, Map.of());
    client.confirmRequest(ID, BODY);
    client.rejectRequest(ID, BODY);
    client.createAccount(BODY);
    client.renameAccount(ID, BODY);
    client.setBalanceTarget(ID, BODY);
    client.setApprovalTiers(ID, BODY);
    client.closeAccount(ID, BODY);
    client.reopenAccount(ID, BODY);
    client.registerHolder(BODY);
    client.updateHolder(ID, BODY);
    client.createGrant(BODY);
    client.updateGrant(USER, ID, BODY);
    client.deleteGrant(USER, ID);

    String accounts = "/api/v1/bank/accounts/" + ID;
    backend.expect("POST", "/api/v1/bank/deposits", BODY_JSON);
    backend.expect("POST", "/api/v1/bank/withdrawals", BODY_JSON);
    backend.expect("POST", "/api/v1/bank/transfers", BODY_JSON);
    backend.expect("POST", "/api/v1/bank/holders/transfer", BODY_JSON);
    backend.expect("POST", "/api/v1/bank/transactions/" + ID + "/reversal", "{}");
    backend.expect("POST", "/api/v1/bank/requests/" + ID + "/confirm", BODY_JSON);
    backend.expect("POST", "/api/v1/bank/requests/" + ID + "/reject", BODY_JSON);
    backend.expect("POST", "/api/v1/bank/accounts", BODY_JSON);
    backend.expect("PATCH", accounts, BODY_JSON);
    backend.expect("PATCH", accounts + "/balance-target", BODY_JSON);
    backend.expect("PATCH", accounts + "/approval-tiers", BODY_JSON);
    backend.expect("POST", accounts + "/close", BODY_JSON);
    backend.expect("POST", accounts + "/reopen", BODY_JSON);
    backend.expect("POST", "/api/v1/bank/holders", BODY_JSON);
    backend.expect("PATCH", "/api/v1/bank/holders/" + ID, BODY_JSON);
    backend.expect("POST", "/api/v1/bank/grants", BODY_JSON);
    backend.expect("PATCH", "/api/v1/bank/grants/" + USER + "/" + ID, BODY_JSON);
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
  void orgUnitRelaysForwardTheBodyAndReturnTheAnswer() {
    for (int i = 0; i < 20; i++) {
      backend.answerJson(ANSWER);
    }

    assertThat(client.createOrgUnitRequest(BODY)).containsEntry("version", 2);
    client.cancelOrgUnitRequest(ID, BODY);
    client.updateOrgUnitRequest(ID, BODY);
    client.setOrgUnitBalanceTarget(ID, BODY);
    client.addRoleVisibility(ID, "KRT_MEMBER", Map.of());
    client.removeRoleVisibility(ID, "KRT_MEMBER");
    client.setAllMembersVisibility(ID, true, Map.of());
    client.setAreaMembersVisibility(ID, false, Map.of());
    client.addUserVisibility(ID, USER, Map.of());
    client.removeUserVisibility(ID, USER);
    client.setRoleApprovalLimit(ID, "OFFICER", BODY);
    client.clearRoleApprovalLimit(ID, "OFFICER");
    client.setAllMembersApprovalLimit(ID, BODY);
    client.clearAllMembersApprovalLimit(ID);
    client.setAreaMembersApprovalLimit(ID, BODY);
    client.clearAreaMembersApprovalLimit(ID);
    client.setUserApprovalLimit(ID, USER, BODY);
    client.clearUserApprovalLimit(ID, USER);
    client.grantOwnerApproval(ID, Map.of());
    client.revokeOwnerApproval(ID);

    String requests = "/api/v1/org-units/bank/requests";
    String account = "/api/v1/org-units/bank/accounts/" + ID;
    backend.expect("POST", requests, BODY_JSON);
    backend.expect("POST", requests + "/" + ID + "/cancel", BODY_JSON);
    backend.expect("PUT", requests + "/" + ID, BODY_JSON);
    backend.expect("PUT", account + "/balance-target", BODY_JSON);
    backend.expect("POST", account + "/visibility/role/KRT_MEMBER", "{}");
    backend.expect("DELETE", account + "/visibility/role/KRT_MEMBER", null);
    backend.expect("PUT", account + "/visibility/all-members/true", "{}");
    backend.expect("PUT", account + "/visibility/area-members/false", "{}");
    backend.expect("POST", account + "/visibility/user/" + USER, "{}");
    backend.expect("DELETE", account + "/visibility/user/" + USER, null);
    backend.expect("PUT", account + "/approval-limit/role/OFFICER", BODY_JSON);
    backend.expect("DELETE", account + "/approval-limit/role/OFFICER", null);
    backend.expect("PUT", account + "/approval-limit/all-members", BODY_JSON);
    backend.expect("DELETE", account + "/approval-limit/all-members", null);
    backend.expect("PUT", account + "/approval-limit/area-members", BODY_JSON);
    backend.expect("DELETE", account + "/approval-limit/area-members", null);
    backend.expect("PUT", account + "/approval-limit/user/" + USER, BODY_JSON);
    backend.expect("DELETE", account + "/approval-limit/user/" + USER, null);
    backend.expect("POST", requests + "/" + ID + "/owner-approval", "{}");
    backend.expect("DELETE", requests + "/" + ID + "/owner-approval", null);
  }
}
