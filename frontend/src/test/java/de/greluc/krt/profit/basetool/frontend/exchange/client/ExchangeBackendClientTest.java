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

package de.greluc.krt.profit.basetool.frontend.exchange.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.ConnectedAppMassChangeRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeBulkUndoRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientStatusRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeClientUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeSettingsUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ExchangeUndoRequestDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link ExchangeBackendClient} sends (plan F3). */
class ExchangeBackendClientTest {

  private static final UUID ID = UUID.fromString("7a0c7a0c-0000-4000-8000-00000000c11e");
  private static final Instant SINCE = Instant.parse("2026-09-01T10:15:30Z");

  private BackendClientHarness backend;
  private ExchangeBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new ExchangeBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void registryReads() {
    backend.answerJson("[]");
    backend.answerJson("{\"id\":\"" + ID + "\"}");
    backend.answerJson("[]");
    backend.answerJson("{\"enabled\":true}");

    assertThat(client.clients()).isEmpty();
    assertThat(client.client(ID)).isNotNull();
    assertThat(client.usage()).isEmpty();
    assertThat(client.settings()).isNotNull();

    backend.expect("GET", "/api/v1/connected-apps/admin/clients");
    backend.expect("GET", "/api/v1/connected-apps/admin/clients/" + ID);
    backend.expect("GET", "/api/v1/connected-apps/admin/clients/usage");
    backend.expect("GET", "/api/v1/connected-apps/admin/settings");
  }

  @Test
  void registryWrites() {
    for (int i = 0; i < 5; i++) {
      backend.answerJson("{}");
    }

    client.createClient(
        new ExchangeClientCreateRequest(
            "fleet-tool", "Fleet Tool", List.of("exchange.connect"), "1.0", null, 60, 100));
    client.updateClient(
        ID,
        new ExchangeClientUpdateRequest(
            "Fleet Tool", List.of("exchange.connect"), null, null, null, null, 3L));
    client.suspendClient(ID, new ExchangeClientStatusRequest(4L));
    client.activateClient(ID, new ExchangeClientStatusRequest(5L));
    client.updateSettings(new ExchangeSettingsUpdateRequest(Boolean.FALSE, 6L));

    backend.expect(
        "POST",
        "/api/v1/connected-apps/admin/clients",
        "{\"clientId\":\"fleet-tool\",\"displayName\":\"Fleet Tool\",\"capabilities\":"
            + "[\"exchange.connect\"],\"minClientVersion\":\"1.0\",\"contactUrl\":null,"
            + "\"requestsPerMinute\":60,\"writesPerDay\":100}");
    backend.expect(
        "PUT",
        "/api/v1/connected-apps/admin/clients/" + ID,
        "{\"displayName\":\"Fleet Tool\",\"capabilities\":[\"exchange.connect\"],"
            + "\"minClientVersion\":null,\"contactUrl\":null,\"requestsPerMinute\":null,"
            + "\"writesPerDay\":null,\"version\":3}");
    backend.expect(
        "POST", "/api/v1/connected-apps/admin/clients/" + ID + "/suspend", "{\"version\":4}");
    backend.expect(
        "POST", "/api/v1/connected-apps/admin/clients/" + ID + "/activate", "{\"version\":5}");
    backend.expect(
        "PUT", "/api/v1/connected-apps/admin/settings", "{\"enabled\":false,\"version\":6}");
  }

  @Test
  void bulkUndo() {
    for (int i = 0; i < 5; i++) {
      backend.answerJson(i == 2 || i == 3 ? "[]" : "{}");
    }
    ExchangeBulkUndoRequest scope = new ExchangeBulkUndoRequest(SINCE, null, "stock");

    client.previewUndo(ID, scope);
    client.startUndo(ID, scope);
    client.undoInstallations(ID, SINCE);
    client.undoRuns();
    client.undoRun(ID);

    String body =
        "{\"since\":\"2026-09-01T10:15:30Z\",\"installationId\":null,\"resource\":\"stock\"}";
    backend.expect("POST", "/api/v1/connected-apps/admin/clients/" + ID + "/undo/preview", body);
    backend.expect("POST", "/api/v1/connected-apps/admin/clients/" + ID + "/undo", body);
    backend.expect(
        "GET",
        "/api/v1/connected-apps/admin/clients/"
            + ID
            + "/undo/installations?since=2026-09-01T10%3A15%3A30Z");
    backend.expect("GET", "/api/v1/connected-apps/admin/undo-runs");
    backend.expect("GET", "/api/v1/connected-apps/admin/undo-runs/" + ID);
  }

  @Test
  void connectedApps() {
    backend.answerJson("[]");
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerEmpty();

    assertThat(client.connectedApps()).isEmpty();
    client.disconnectClient("fleet-tool");
    client.markInstallationSeen(ID);
    client.undoClientWrites("fleet-tool", new ExchangeUndoRequestDto(SINCE));
    client.disconnectInstallation(ID);

    backend.expect("GET", "/api/v1/connected-apps");
    backend.expect("DELETE", "/api/v1/connected-apps/fleet-tool", null);
    backend.expect("POST", "/api/v1/connected-apps/installations/" + ID + "/seen", null);
    backend.expect(
        "POST", "/api/v1/connected-apps/fleet-tool/undo", "{\"since\":\"2026-09-01T10:15:30Z\"}");
    backend.expect("DELETE", "/api/v1/connected-apps/installations/" + ID, null);
  }

  @Test
  void massChanges() {
    backend.answerJson("{}");
    backend.answerJson("{}");
    ConnectedAppMassChangeRequestDto request =
        new ConnectedAppMassChangeRequestDto("fleet-tool", "inst-1", "stock", "{}", SINCE);

    client.previewMassChange(request);
    client.confirmMassChange(request);

    String body =
        "{\"clientId\":\"fleet-tool\",\"installationKey\":\"inst-1\",\"resource\":\"stock\","
            + "\"changeSet\":\"{}\",\"stagedAt\":\"2026-09-01T10:15:30Z\"}";
    backend.expect("POST", "/api/v1/connected-apps/mass-changes/preview", body);
    backend.expect("POST", "/api/v1/connected-apps/mass-changes/confirm", body);
  }
}
