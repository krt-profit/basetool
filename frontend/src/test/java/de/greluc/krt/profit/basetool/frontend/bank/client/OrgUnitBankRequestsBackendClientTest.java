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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.CancelBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.RejectBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.UpdateBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link OrgUnitBankRequestsBackendClient} sends and what it decodes. */
class OrgUnitBankRequestsBackendClientTest {

  private static final String REQUESTS = "/api/v1/bank/org-units/requests";
  private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
  private static final UUID USER = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
  private static final UUID OTHER = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
  private static final BigDecimal AMOUNT = new BigDecimal("1000");
  private static final String VERSIONED = "{\"id\":\"" + ID + "\",\"version\":2}";

  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":3,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private OrgUnitBankRequestsBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new OrgUnitBankRequestsBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void readsTheOwnAndTheForeignRequestLists() {
    backend.answerJson("[]");
    backend.answerJson("[]");

    assertThat(client.own()).isEmpty();
    assertThat(client.foreign()).isEmpty();

    backend.expect("GET", REQUESTS);
    backend.expect("GET", REQUESTS + "/foreign");
  }

  @Test
  void sendsTheSizeAndEachStateOfThePage() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    assertThat(client.ownPage(List.of("CONFIRMED", "REJECTED"), 3)).isNotNull();
    assertThat(client.ownPage(List.of(), 5)).isNotNull();

    backend.expect("GET", REQUESTS + "/page?size=3&status=CONFIRMED&status=REJECTED");
    backend.expect("GET", REQUESTS + "/page?size=5");
  }

  @Test
  void anUnknownOrHiddenRequestReadsAsAbsent() {
    backend.answerProblem(404, "NOT_FOUND");

    assertThat(client.view(UUID.randomUUID())).isNull();
  }

  @Test
  void anyOtherBackendFailureOfTheRequestReadStillFails() {
    backend.answerProblem(403, "ACCESS_DENIED");

    assertThatThrownBy(() -> client.view(UUID.randomUUID()))
        .isInstanceOf(BackendServiceException.class);
  }

  @Test
  void writesSendTheBackendRecordsAndDecodeTheAnswers() {
    for (int i = 0; i < 6; i++) {
      backend.answerJson(VERSIONED);
    }

    assertThat(
            client.create(
                new CreateBankBookingRequest(
                    ID, "WITHDRAWAL", null, AMOUNT, "Sprit", null, null, null, USER, OTHER)))
        .extracting(BankBookingRequestDto::version)
        .isEqualTo(2L);
    client.cancel(ID, new CancelBankBookingRequest(1L));
    client.update(ID, new UpdateBankBookingRequest(AMOUNT, null, null, OTHER, null, null, 1L));
    assertThat(client.grantApproval(ID, Map.of()))
        .extracting(BankBookingRequestDto::version)
        .isEqualTo(2L);
    client.revokeApproval(ID);
    assertThat(client.rejectAsApprover(ID, new RejectBankBookingRequest("Zu hoch", 1L)))
        .extracting(BankBookingRequestDto::version)
        .isEqualTo(2L);

    backend.expect(
        "POST",
        REQUESTS,
        "{\"sourceAccountId\":\""
            + ID
            + "\",\"type\":\"WITHDRAWAL\",\"targetAccountId\":null,\"amount\":1000,"
            + "\"note\":\"Sprit\",\"justification\":null,\"splitEnabled\":null,"
            + "\"splitPercent\":null,\"counterpartyUserId\":\""
            + USER
            + "\",\"counterpartyOrgUnitId\":\""
            + OTHER
            + "\"}");
    backend.expect("POST", REQUESTS + "/" + ID + "/cancel", "{\"version\":1}");
    backend.expect(
        "PUT",
        REQUESTS + "/" + ID,
        "{\"amount\":1000,\"note\":null,\"justification\":null,\"targetAccountId\":\""
            + OTHER
            + "\",\"counterpartyUserId\":null,\"counterpartyOrgUnitId\":null,\"version\":1}");
    backend.expect("POST", REQUESTS + "/" + ID + "/owner-approval", "{}");
    backend.expect("DELETE", REQUESTS + "/" + ID + "/owner-approval", null);
    backend.expect(
        "POST", REQUESTS + "/" + ID + "/reject", "{\"reason\":\"Zu hoch\",\"version\":1}");
  }

  @Test
  void decodesThePageTotal() {
    backend.answerJson(
        "{\"content\":[],\"page\":0,\"size\":3,\"totalElements\":7,\"totalPages\":3}");

    assertThat(client.ownPage(List.of("PENDING"), 3).totalElements()).isEqualTo(7);
  }
}
