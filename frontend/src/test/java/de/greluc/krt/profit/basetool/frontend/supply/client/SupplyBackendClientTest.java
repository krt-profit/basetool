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

package de.greluc.krt.profit.basetool.frontend.supply.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient.NeedAction;
import de.greluc.krt.profit.basetool.frontend.supply.model.DeclineDonationRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.ReceiveDonationRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.UpdateSupplySettingsRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.UpdateSupplyUnitRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.VersionRequest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link SupplyBackendClient} sends to the supply endpoints (REQ-SUP-001 …
 * REQ-SUP-020): path, query and the typed body the browser posted.
 */
class SupplyBackendClientTest {

  private static final UUID ID = UUID.fromString("9c8b7a6f-5e4d-4c3b-8a2f-1e0d9c8b7a6f");
  private static final UUID UNIT = UUID.fromString("3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private SupplyBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new SupplyBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void needReadsCarryEveryPresentFilter() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson("[]");

    assertThat(client.needs(UNIT, "OPEN", 1, 20)).isNotNull();
    assertThat(client.needs(null, " ", 0, 50)).isNotNull();
    assertThat(client.openNeeds(UNIT)).isEmpty();
    assertThat(client.needJobOrders()).isEmpty();

    backend.expect(
        "GET",
        "/api/v1/supply/needs?page=1&size=20&sort=createdAt,desc&orgUnitId="
            + UNIT
            + "&status=OPEN");
    backend.expect("GET", "/api/v1/supply/needs?page=0&size=50&sort=createdAt,desc");
    backend.expect("GET", "/api/v1/supply/needs/open?orgUnitId=" + UNIT);
    backend.expect("GET", "/api/v1/supply/needs/job-orders");
  }

  @Test
  void donationReadsAndStatistics() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{\"donations\":0,\"entries\":[]}");
    backend.answerJson("{\"months\":[],\"units\":[],\"donors\":[]}");

    assertThat(client.donationsOfUnit(UNIT, "PLEDGED", 0, 20)).isNotNull();
    assertThat(client.donationsOfUnit(UNIT, null, 2, 10)).isNotNull();
    assertThat(client.myDonations(0, 20)).isNotNull();
    assertThat(client.myStats()).isNotNull();
    assertThat(client.stats(12)).isNotNull();

    backend.expect(
        "GET", "/api/v1/supply/donations?orgUnitId=" + UNIT + "&page=0&size=20&status=PLEDGED");
    backend.expect("GET", "/api/v1/supply/donations?orgUnitId=" + UNIT + "&page=2&size=10");
    backend.expect("GET", "/api/v1/supply/me/donations?page=0&size=20");
    backend.expect("GET", "/api/v1/supply/me/donations/stats");
    backend.expect("GET", "/api/v1/supply/stats?months=12");
  }

  @Test
  void needActionsPostTheEchoedVersion() {
    for (int i = 0; i < 3; i++) {
      backend.answerEmpty();
    }

    client.changeNeed(ID, NeedAction.WITHDRAW, new VersionRequest(4L));
    client.changeNeed(ID, NeedAction.PAUSE, new VersionRequest(5L));
    client.changeNeed(ID, NeedAction.RESUME, new VersionRequest(6L));

    backend.expect("POST", "/api/v1/supply/needs/" + ID + "/withdraw", "{\"version\":4}");
    backend.expect("POST", "/api/v1/supply/needs/" + ID + "/pause", "{\"version\":5}");
    backend.expect("POST", "/api/v1/supply/needs/" + ID + "/resume", "{\"version\":6}");
  }

  @Test
  void donationWrites() {
    for (int i = 0; i < 3; i++) {
      backend.answerEmpty();
    }

    client.receive(ID, new ReceiveDonationRequest(2.5, UNIT, 3L));
    client.decline(ID, new DeclineDonationRequest("WRONG_ITEM", 3L));
    client.withdraw(ID, new VersionRequest(3L));

    backend.expect(
        "POST",
        "/api/v1/supply/donations/" + ID + "/receive",
        "{\"receivedAmount\":2.5,\"locationId\":\"" + UNIT + "\",\"version\":3}");
    backend.expect(
        "POST",
        "/api/v1/supply/donations/" + ID + "/decline",
        "{\"reason\":\"WRONG_ITEM\",\"version\":3}");
    backend.expect("POST", "/api/v1/supply/donations/" + ID + "/withdraw", "{\"version\":3}");
  }

  @Test
  void adminReadsAndWrites() {
    backend.answerJson("[]");
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    assertThat(client.adminUnits()).isEmpty();
    client.updateUnit(UNIT, new UpdateSupplyUnitRequest(true, false, 2L));
    client.settings();
    client.updateSettings(new UpdateSupplySettingsRequest(true, null));

    backend.expect("GET", "/api/v1/supply/admin/units");
    backend.expect(
        "PUT",
        "/api/v1/supply/admin/units/" + UNIT,
        "{\"mayRaiseNeeds\":true,\"acceptsDonations\":false,\"version\":2}");
    backend.expect("GET", "/api/v1/supply/admin/settings");
    backend.expect(
        "PUT", "/api/v1/supply/admin/settings", "{\"enabled\":true,\"governingAreaId\":null}");
  }

  @Test
  void theStateIsReadWhileTheSurfaceMayBeOff() {
    backend.answerEmpty();

    client.state();

    backend.expect("GET", "/api/v1/supply/state");
  }
}
