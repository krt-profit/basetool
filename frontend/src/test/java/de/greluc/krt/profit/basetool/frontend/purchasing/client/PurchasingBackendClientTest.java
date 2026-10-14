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

package de.greluc.krt.profit.basetool.frontend.purchasing.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient.PartnerAction;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.AcceptDeliveryRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.AppointDelegateRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PausePurchasingRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingVersionRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.RecordDeliveryRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.RequestTrancheRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.ResumePurchasingRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.SettleDealRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdateDelegateLimitRequest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link PurchasingBackendClient} sends to the purchasing endpoints (REQ-PUR-001
 * … REQ-PUR-028): path, query and the typed body the browser posted.
 */
class PurchasingBackendClientTest {

  private static final UUID ID = UUID.fromString("9c8b7a6f-5e4d-4c3b-8a2f-1e0d9c8b7a6f");
  private static final UUID DELIVERY = UUID.fromString("3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private PurchasingBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new PurchasingBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void stateIsReadFromTheStateEndpoint() {
    backend.answerJson(
        "{\"enabled\":true,\"delegate\":false,\"governing\":false,\"bankStaff\":false,"
            + "\"managesUnit\":false,\"canReadReport\":false,\"paused\":false}");

    assertThat(client.state()).isNotNull();

    backend.expect("GET", "/api/v1/purchasing/state");
  }

  @Test
  void dealReadsCarryEveryPresentFilter() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    assertThat(client.deals("FUNDED", true, true, 1, 20)).isNotNull();
    assertThat(client.deals(" ", false, false, 0, 50)).isNotNull();

    backend.expect(
        "GET",
        "/api/v1/purchasing/deals?page=1&size=20&sort=createdAt,desc&status=FUNDED&mine=true"
            + "&overdue=true");
    backend.expect("GET", "/api/v1/purchasing/deals?page=0&size=50&sort=createdAt,desc");
  }

  @Test
  void dealWritesPostTheTypedBodies() {
    for (int i = 0; i < 6; i++) {
      backend.answerEmpty();
    }

    client.cancelDeal(ID, new PurchasingVersionRequest(4L));
    client.requestTranche(ID, new RequestTrancheRequest("ADVANCE", 500L));
    client.recordDelivery(ID, new RecordDeliveryRequest(2.5, DELIVERY));
    client.acceptDelivery(
        ID, DELIVERY, new AcceptDeliveryRequest(2.0, 0.5, "DAMAGED", 700, DELIVERY, 3L));
    client.closeDelivery(ID);
    client.settle(ID, new SettleDealRequest(400L, 100L, "QUALITY", 2L));

    backend.expect("POST", "/api/v1/purchasing/deals/" + ID + "/cancel", "{\"version\":4}");
    backend.expect(
        "POST",
        "/api/v1/purchasing/deals/" + ID + "/tranches",
        "{\"kind\":\"ADVANCE\",\"amount\":500}");
    backend.expect(
        "POST",
        "/api/v1/purchasing/deals/" + ID + "/deliveries",
        "{\"amount\":2.5,\"locationId\":\"" + DELIVERY + "\"}");
    backend.expect(
        "POST",
        "/api/v1/purchasing/deals/" + ID + "/deliveries/" + DELIVERY + "/accept",
        "{\"acceptedAmount\":2.0,\"rejectedAmount\":0.5,\"rejectReason\":\"DAMAGED\","
            + "\"measuredQuality\":700,\"locationId\":\""
            + DELIVERY
            + "\",\"version\":3}");
    backend.expect("POST", "/api/v1/purchasing/deals/" + ID + "/close-delivery");
    backend.expect(
        "PUT",
        "/api/v1/purchasing/deals/" + ID + "/settlement",
        "{\"moneyPaid\":400,\"lossAmount\":100,\"lossReason\":\"QUALITY\",\"version\":2}");
  }

  @Test
  void partnerReadsAndStatusChanges() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    for (int i = 0; i < 3; i++) {
      backend.answerEmpty();
    }

    assertThat(client.partners("arg", "ORG", "ACTIVE", 0, 20)).isNotNull();
    assertThat(client.partners(null, null, null, 2, 10)).isNotNull();
    client.changePartner(ID, PartnerAction.BLOCK, new PurchasingVersionRequest(1L));
    client.changePartner(ID, PartnerAction.UNBLOCK, new PurchasingVersionRequest(2L));
    client.changePartner(ID, PartnerAction.ERASE, new PurchasingVersionRequest(3L));

    backend.expect(
        "GET", "/api/v1/purchasing/partners?page=0&size=20&query=arg&kind=ORG&status=ACTIVE");
    backend.expect("GET", "/api/v1/purchasing/partners?page=2&size=10");
    backend.expect("POST", "/api/v1/purchasing/partners/" + ID + "/block", "{\"version\":1}");
    backend.expect("POST", "/api/v1/purchasing/partners/" + ID + "/unblock", "{\"version\":2}");
    backend.expect("POST", "/api/v1/purchasing/partners/" + ID + "/erase", "{\"version\":3}");
  }

  @Test
  void delegateAndPauseWrites() {
    backend.answerJson("[]");
    for (int i = 0; i < 5; i++) {
      backend.answerEmpty();
    }

    assertThat(client.delegates()).isEmpty();
    client.appoint(new AppointDelegateRequest(ID, null, 1000L));
    client.revoke(ID, new PurchasingVersionRequest(2L));
    client.setLimit(ID, new UpdateDelegateLimitRequest(null, 3L));
    client.pause(new PausePurchasingRequest(null, "BUDGET"));
    client.resume(new ResumePurchasingRequest(ID));

    backend.expect("GET", "/api/v1/purchasing/delegates");
    backend.expect(
        "POST",
        "/api/v1/purchasing/delegates",
        "{\"userId\":\"" + ID + "\",\"validUntil\":null,\"monthlyLimit\":1000}");
    backend.expect("POST", "/api/v1/purchasing/delegates/" + ID + "/revoke", "{\"version\":2}");
    backend.expect(
        "PUT",
        "/api/v1/purchasing/delegates/" + ID + "/limit",
        "{\"monthlyLimit\":null,\"version\":3}");
    backend.expect(
        "POST", "/api/v1/purchasing/pause", "{\"orgUnitId\":null,\"reason\":\"BUDGET\"}");
    backend.expect("POST", "/api/v1/purchasing/pause/resume", "{\"orgUnitId\":\"" + ID + "\"}");
  }

  @Test
  void reportReadsCarryThePeriodOnlyWhenPresent() {
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    client.report("2026-01", "2026-10");
    client.report(null, null);
    client.reportForumText("2026-09", null);

    backend.expect("GET", "/api/v1/purchasing/report?from=2026-01&to=2026-10");
    backend.expect("GET", "/api/v1/purchasing/report");
    backend.expect("GET", "/api/v1/purchasing/report/forum-text?from=2026-09");
  }

  @Test
  void adminReadsAndWrites() {
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerEmpty();

    assertThat(client.fundingAccounts()).isEmpty();
    assertThat(client.riskClasses()).isEmpty();
    assertThat(client.settings()).isNull();

    backend.expect("GET", "/api/v1/purchasing/admin/funding-accounts");
    backend.expect("GET", "/api/v1/purchasing/admin/risk-classes");
    backend.expect("GET", "/api/v1/purchasing/admin/settings");
  }
}
