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

package de.greluc.krt.profit.basetool.frontend.dashboard.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardColumn;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardLayoutItem;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardLayoutRequest;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardOptionsRequest;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the dashboard requests {@link DashboardBackendClient} sends: verb, path and body. */
class DashboardLayoutBackendClientTest {

  private BackendClientHarness backend;
  private DashboardBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new DashboardBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void readsTheDashboardOfAMode() {
    backend.answerJson(
        "{\"mode\":\"MEMBER\",\"version\":2,\"customised\":true,\"widgets\":[{\"id\":null,"
            + "\"type\":\"NOTIFICATIONS\",\"column\":\"NARROW\",\"optionsVersion\":0,"
            + "\"rows\":5,\"status\":\"UNREAD\"}]}");

    DashboardResponse read = client.dashboard(DashboardMode.MEMBER);

    assertThat(read.version()).isEqualTo(2);
    assertThat(read.widgets().getFirst().type()).isEqualTo(DashboardWidgetType.NOTIFICATIONS);
    backend.expect("GET", "/api/v1/navigation/dashboards/member");
  }

  @Test
  void putsTheLayoutWithItsVersion() {
    backend.answerJson("{\"mode\":\"MEMBER\",\"version\":1,\"customised\":true,\"widgets\":[]}");

    client.replaceLayout(
        DashboardMode.MEMBER,
        new DashboardLayoutRequest(
            List.of(
                new DashboardLayoutItem(
                    null, DashboardWidgetType.FAVOURITES, DashboardColumn.NARROW)),
            0L));

    backend.expect(
        "PUT",
        "/api/v1/navigation/dashboards/member/layout",
        "{\"widgets\":[{\"id\":null,\"type\":\"FAVOURITES\",\"column\":\"NARROW\"}],\"version\":0}");
  }

  @Test
  void putsTheOptionsOfOneInstance() {
    UUID id = UUID.randomUUID();
    backend.answerJson(
        "{\"id\":\""
            + id
            + "\",\"type\":\"UPCOMING_DAYS\",\"column\":\"WIDE\",\"optionsVersion\":1}");

    DashboardWidgetResponse stored =
        client.replaceOptions(
            DashboardMode.MEMBER, id, new DashboardOptionsRequest(null, 14, 10, null, 0L));

    assertThat(stored.optionsVersion()).isEqualTo(1);
    backend.expect(
        "PUT",
        "/api/v1/navigation/dashboards/member/widgets/" + id + "/options",
        "{\"orgUnitId\":null,\"days\":14,\"rows\":10,\"status\":null,\"version\":0}");
  }

  @Test
  void restoresTheDefaultWithTheEchoedVersion() {
    backend.answerJson("{\"mode\":\"MEMBER\",\"version\":4,\"customised\":false,\"widgets\":[]}");

    client.resetDashboard(DashboardMode.MEMBER, 3);

    backend.expect("DELETE", "/api/v1/navigation/dashboards/member?version=3");
  }

  @Test
  void postsTheTypesToDelete() {
    backend.answerJson("{\"mode\":\"MEMBER\",\"version\":5,\"customised\":true,\"widgets\":[]}");

    client.pruneDashboard(DashboardMode.MEMBER, List.of(DashboardWidgetType.UPCOMING_DAYS));

    backend.expect(
        "POST", "/api/v1/navigation/dashboards/member/prune", "{\"types\":[\"UPCOMING_DAYS\"]}");
  }
}
