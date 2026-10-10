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

package de.greluc.krt.profit.basetool.frontend.orgchart.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartPositionCreateRequest;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartPositionDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartPositionUpdateRequest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link OrgChartBackendClient} sends (plan F3): the typed position bodies carry
 * every field the backend reads, and the typed answer every field it returns.
 */
class OrgChartBackendClientTest {

  private static final UUID ID = UUID.fromString("c0c0c0c0-0000-4000-8000-000000000001");
  private static final UUID UNIT = UUID.fromString("c0c0c0c0-0000-4000-8000-000000000002");

  private static final String POSITION =
      "{\"id\":\""
          + ID
          + "\",\"positionType\":\"COMMAND_LEAD\",\"orgUnitId\":\""
          + UNIT
          + "\",\"userId\":null,\"userName\":null,\"displayName\":\"Jo\",\"name\":\"Kdo 1\","
          + "\"parentId\":null,\"sortIndex\":2,\"version\":5}";

  private BackendClientHarness backend;
  private OrgChartBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new OrgChartBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void chartAndEditorWrites() {
    backend.answerJson("{}");
    backend.answerJson(POSITION);
    backend.answerJson(POSITION);
    backend.answerJson(POSITION);
    backend.answerEmpty();

    client.orgChart();
    OrgChartPositionDto created =
        client.createPosition(
            new OrgChartPositionCreateRequest("COMMAND_LEAD", UNIT, null, null, "Kdo 1", 2, "Jo"));
    client.updatePosition(ID, new OrgChartPositionUpdateRequest(null, "Kdo 1", 2, 5L, "Jo"));
    client.vacateLeader(ID, 5L);
    client.deletePosition(ID);

    assertThat(created)
        .isEqualTo(
            new OrgChartPositionDto(
                ID, "COMMAND_LEAD", UNIT, null, null, "Jo", "Kdo 1", null, 2, 5L));
    backend.expect("GET", "/api/v1/org-chart");
    backend.expect(
        "POST",
        "/api/v1/org-chart/positions",
        "{\"positionType\":\"COMMAND_LEAD\",\"orgUnitId\":\""
            + UNIT
            + "\",\"userId\":null,\"parentId\":null,\"name\":\"Kdo 1\",\"sortIndex\":2,"
            + "\"displayName\":\"Jo\"}");
    backend.expect(
        "PUT",
        "/api/v1/org-chart/positions/" + ID,
        "{\"userId\":null,\"name\":\"Kdo 1\",\"sortIndex\":2,\"version\":5,"
            + "\"displayName\":\"Jo\"}");
    backend.expect("DELETE", "/api/v1/org-chart/positions/" + ID + "/leader?version=5", null);
    backend.expect("DELETE", "/api/v1/org-chart/positions/" + ID, null);
  }
}
