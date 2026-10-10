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

package de.greluc.krt.profit.basetool.frontend.refinery.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStoreDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderStoreItemDto;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the requests {@link RefineryBackendClient} sends (plan F3), each the exact URI and body the
 * refinery controllers built before the client existed.
 */
class RefineryBackendClientTest {

  private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID MATERIAL = UUID.fromString("22222222-3333-4444-5555-666666666666");
  private static final UUID LOCATION = UUID.fromString("44444444-5555-6666-7777-888888888888");
  private static final UUID USER = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");
  private static final UUID ORG_UNIT = UUID.fromString("88888888-9999-aaaa-bbbb-cccccccccccc");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private RefineryBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new RefineryBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void orderPagesExpandPageSizeAndSearch() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    client.orderPage(false, List.of("OPEN", "IN_PROGRESS"), false, null, "endsAt,asc", 0, 1);
    client.orderPage(true, List.of("OPEN", "IN_PROGRESS"), true, "Gold Erz", "endsAt,asc", 2, 50);

    backend.expect(
        "GET", "/api/v1/refinery-orders/all?page=0&size=1&sort=endsAt,asc&status=OPEN,IN_PROGRESS");
    backend.expect(
        "GET",
        "/api/v1/refinery-orders/my-orders?page=2&size=50&sort=endsAt,asc"
            + "&status=OPEN,IN_PROGRESS&ready=true&q=Gold%20Erz");
  }

  @Test
  void orderReadsAndImport() {
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");
    JsonNode extract =
        JsonMapper.builder().build().readTree("{\"refinery\":\"ARC-L1\",\"goods\":[]}");

    client.order(ID);
    client.yields(LOCATION);
    client.importExtract(extract);

    backend.expect("GET", "/api/v1/refinery-orders/" + ID);
    backend.expect("GET", "/api/v1/refinery-orders/locations/" + LOCATION + "/yields");
    backend.expect(
        "POST", "/api/v1/refinery-orders/import-extract", "{\"refinery\":\"ARC-L1\",\"goods\":[]}");
  }

  @Test
  void orderMutations() {
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerEmpty();
    RefineryOrderDto order =
        new RefineryOrderDto(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            RefineryOrderStatus.OPEN,
            null,
            3L,
            ORG_UNIT);

    client.create(order);
    client.update(ID, order);
    client.cancel(ID);
    client.store(
        ID,
        new RefineryOrderStoreDto(
            List.of(
                new RefineryOrderStoreItemDto(
                    MATERIAL, LOCATION, 2, 1.25, USER, null, null, ORG_UNIT, false))));

    String orderJson =
        "{\"id\":null,\"owner\":null,\"location\":null,\"mission\":null,\"startedAt\":null,"
            + "\"durationMinutes\":null,\"expenses\":null,\"otherExpenses\":null,"
            + "\"oreSales\":null,\"profit\":null,\"refiningMethod\":null,\"goods\":[],"
            + "\"status\":\"OPEN\",\"owningSquadron\":null,\"version\":3,\"owningOrgUnitId\":\""
            + ORG_UNIT
            + "\"";
    assertThat(body(backend.expect("POST", "/api/v1/refinery-orders"))).startsWith(orderJson);
    assertThat(body(backend.expect("PUT", "/api/v1/refinery-orders/" + ID))).startsWith(orderJson);
    backend.expect("DELETE", "/api/v1/refinery-orders/" + ID, null);
    backend.expect(
        "POST",
        "/api/v1/refinery-orders/" + ID + "/store",
        "{\"items\":[{\"materialId\":\""
            + MATERIAL
            + "\",\"locationId\":\""
            + LOCATION
            + "\",\"quality\":2,\"amount\":1.25,\"userId\":\""
            + USER
            + "\",\"jobOrderId\":null,\"note\":null,\"owningOrgUnitId\":\""
            + ORG_UNIT
            + "\",\"personal\":false}]}");
  }

  @Test
  void cachedCatalogues() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");

    client.materials();
    client.refiningMethods();
    client.allLocations();
    client.refineryLocations();

    backend.expect("GET", "/api/v1/materials?size=1000&page=0");
    backend.expect("GET", "/api/v1/refining-methods?size=1000&page=0");
    backend.expect("GET", "/api/v1/locations?size=1000&page=0");
    backend.expect("GET", "/api/v1/locations/refineries");
  }

  @Test
  void formLookups() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("[]");
    backend.answerJson("{}");

    client.missions();
    client.pickableOrgUnits();
    client.memberships(USER);
    client.user(USER);
    client.currentUser();
    client.activeJobOrders();
    client.roundingMode();

    backend.expect("GET", "/api/v1/missions?size=1000&sort=plannedStartTime,desc");
    backend.expect("GET", "/api/v1/users/me/pickable-org-units");
    backend.expect("GET", "/api/v1/users/" + USER + "/memberships");
    backend.expect("GET", "/api/v1/users/" + USER);
    backend.expect("GET", "/api/v1/users/me");
    backend.expect("GET", "/api/v1/orders/lookup");
    backend.expect("GET", "/api/v1/settings/refinery.rounding.mode");
  }

  /**
   * Reads a recorded request's body.
   *
   * @param request the recorded request
   * @return the body as UTF-8 text
   */
  private static String body(RecordedRequest request) {
    return request.getBody().readString(StandardCharsets.UTF_8);
  }
}
