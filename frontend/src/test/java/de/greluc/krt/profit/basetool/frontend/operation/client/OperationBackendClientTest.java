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

package de.greluc.krt.profit.basetool.frontend.operation.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutStatusUpdateDto;
import de.greluc.krt.profit.basetool.frontend.model.form.OperationForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Pins the requests {@link OperationBackendClient} sends (plan F3), each the exact request the
 * operation controller sent before the client existed.
 */
class OperationBackendClientTest {

  private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID MISSION = UUID.fromString("66666666-7777-8888-9999-000000000000");
  private static final UUID ORG_UNIT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final Instant START = Instant.parse("2026-09-01T10:15:30Z");
  private static final Instant END = Instant.parse("2026-10-01T00:00:00Z");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0}";
  private static final String O = "/api/v1/operations/" + ID;

  private BackendClientHarness backend;
  private OperationBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new OperationBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void theListSearchRelaysEveryFilterAsATemplateVariable() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    client.searchOperations(
        new OperationBackendClient.OperationSearch("a&b c", START, END, 1, 20, "ALL"));
    client.searchOperations(
        new OperationBackendClient.OperationSearch(" ", null, null, 0, 20, "PAST"));
    client.searchOperations(
        new OperationBackendClient.OperationSearch(null, null, null, 0, 20, "UPCOMING"));

    backend.expect(
        "GET",
        sent(
            "/api/v1/operations/search?query={query}&start={start}&end={end}&page={page}&"
                + "size={size}&sort=createdAt,desc&"
                + "status=PLANNED&status=ACTIVE&status=COMPLETED&status=CANCELED&",
            "a&b c",
            START,
            END,
            1,
            20));
    backend.expect(
        "GET",
        sent(
            "/api/v1/operations/search?page={page}&size={size}&sort=createdAt,desc&"
                + "status=COMPLETED&status=CANCELED&",
            0,
            20));
    backend.expect(
        "GET",
        sent(
            "/api/v1/operations/search?page={page}&size={size}&sort=createdAt,desc&"
                + "status=PLANNED&status=ACTIVE&",
            0,
            20));
  }

  @Test
  void detailReads() {
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");

    assertThat(client.pickableOrgUnits()).isEmpty();
    assertThat(client.operation(ID)).isNotNull();
    assertThat(client.missions(ID, 2, 10).content()).isEmpty();
    assertThat(client.financeSummary(ID)).isNotNull();
    assertThat(client.payouts(ID)).isNotNull();
    assertThat(client.missionFinance(ID, MISSION)).isNotNull();

    backend.expect("GET", "/api/v1/org-units/me/pickable");
    backend.expect("GET", O);
    backend.expect(
        "GET",
        "/api/v1/missions/search?operationId=" + ID + "&page=2&size=10&sort=plannedStartTime,asc");
    backend.expect("GET", O + "/finance-summary");
    backend.expect("GET", O + "/payouts");
    backend.expect("GET", O + "/finances/" + MISSION);
  }

  @Test
  void writes() {
    OperationForm form = new OperationForm("Op", null, "PLANNED", 3L, ORG_UNIT);
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerJson("{\"id\":\"" + ID + "\",\"version\":4}");
    backend.answerJson("{}");
    backend.answerEmpty();

    client.createOperation(form);
    client.updateOperation(ID, form);
    assertThat(client.updateOperationAndRead(ID, form).version()).isEqualTo(4L);
    assertThat(client.updatePayoutStatus(ID, new OperationPayoutStatusUpdateDto("u:1", true)))
        .isNotNull();
    client.deleteOperation(ID);

    String json =
        "{\"name\":\"Op\",\"description\":null,\"status\":\"PLANNED\",\"version\":3,"
            + "\"owningOrgUnitId\":\""
            + ORG_UNIT
            + "\"}";
    backend.expect("POST", "/api/v1/operations", json);
    backend.expect("PUT", O, json);
    backend.expect("PUT", O, json);
    backend.expect("PUT", O + "/payouts/paid-out", "{\"participantKey\":\"u:1\",\"paidOut\":true}");
    backend.expect("DELETE", O);
  }

  /**
   * Expands a URI template the way the backend {@code WebClient} does.
   *
   * @param template the template the controller sent before the client existed
   * @param variables its variables, in order
   * @return the raw path and query that reach the backend
   */
  private static String sent(String template, Object... variables) {
    URI uri = new DefaultUriBuilderFactory().expand(template, variables);
    return uri.getRawPath() + "?" + uri.getRawQuery();
  }
}
