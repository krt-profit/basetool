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

package de.greluc.krt.profit.basetool.frontend.audit.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.time.Instant;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Pins the requests {@link AuditBackendClient} sends (plan F3), each the exact URI the controllers
 * built before the client existed.
 */
class AuditBackendClientTest {

  private static final Instant FROM = Instant.parse("2026-09-01T10:15:30Z");
  private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
  private static final UUID ACTOR = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private AuditBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new AuditBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void trailPagesCarryEveryPresentFilter() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    AuditBackendClient.Filter all =
        new AuditBackendClient.Filter(2, 50, FROM, TO, ACTOR, "ROLE_GRANTED", "basetool-android");
    AuditBackendClient.Filter none =
        new AuditBackendClient.Filter(0, 50, null, null, null, " ", null);

    client.bankEvents(all);
    client.areaEvents("ROLE", all);
    client.areaEvents("MARKET", none);

    String query =
        "?page=2&size=50&from=2026-09-01T10:15:30Z&to=2026-10-01T00:00:00Z&actorUserId="
            + ACTOR
            + "&eventType=ROLE_GRANTED&clientId=basetool-android";
    backend.expect("GET", "/api/v1/bank/admin/audit" + query);
    backend.expect("GET", "/api/v1/audit/ROLE" + query);
    backend.expect("GET", "/api/v1/audit/MARKET?page=0&size=50");
  }

  @Test
  void theTrailUriIsTheOneTheControllerBuilt() {
    backend.answerJson(EMPTY_PAGE);
    AuditBackendClient.Filter filter =
        new AuditBackendClient.Filter(1, 50, FROM, null, ACTOR, null, "other");

    client.areaEvents("INVENTORY", filter);

    String before =
        UriComponentsBuilder.fromPath("/api/v1/audit/INVENTORY")
            .queryParam("page", 1)
            .queryParam("size", 50)
            .queryParam("from", FROM)
            .queryParam("actorUserId", ACTOR)
            .queryParam("clientId", "other")
            .toUriString();
    backend.expect("GET", before);
  }

  @Test
  void exchangeRegistry() {
    backend.answerJson("[]");

    assertThat(client.exchangeClients()).isEmpty();

    backend.expect("GET", "/api/v1/admin/exchange-clients");
  }

  @Test
  void exportsAndPurges() {
    byte[] pdf = {1, 2, 3};
    backend.answerBytes("application/pdf", pdf);
    backend.answerBytes("application/pdf", pdf);
    backend.answerBytes("application/json", pdf);
    backend.answerBytes("application/json", pdf);
    backend.answerJson("{\"deletedCount\":3}");
    backend.answerJson("{\"deletedCount\":4}");

    assertThat(client.exportPdf("BANK", FROM, TO, "Europe/Berlin")).isEqualTo(pdf);
    assertThat(client.exportPdf("HANGAR", FROM, TO, " ")).isEqualTo(pdf);
    assertThat(client.exportJson("BANK", FROM, TO)).isEqualTo(pdf);
    assertThat(client.exportJson("MISSION", FROM, TO)).isEqualTo(pdf);
    assertThat(client.purge("BANK", FROM)).isNotEmpty();
    assertThat(client.purge("REFINERY", FROM)).isNotEmpty();

    String period = "?from=2026-09-01T10:15:30Z&to=2026-10-01T00:00:00Z";
    RecordedRequest bankPdf = backend.expect("GET", "/api/v1/bank/admin/audit/export" + period);
    assertThat(bankPdf.getHeader("X-User-Time-Zone")).isEqualTo("Europe/Berlin");
    RecordedRequest areaPdf = backend.expect("GET", "/api/v1/audit/HANGAR/export" + period);
    assertThat(areaPdf.getHeader("X-User-Time-Zone")).isNull();
    backend.expect("GET", "/api/v1/bank/admin/audit/export.json" + period);
    backend.expect("GET", "/api/v1/audit/MISSION/export.json" + period);
    backend.expect("DELETE", "/api/v1/bank/admin/audit?before=2026-09-01T10:15:30Z", null);
    backend.expect("DELETE", "/api/v1/audit/REFINERY?before=2026-09-01T10:15:30Z", null);
  }
}
