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

import de.greluc.krt.profit.basetool.frontend.dashboard.model.AnnouncementDto;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.AnnouncementRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link DashboardBackendClient} sends (plan F3), and that the typed announcement
 * reads the fields the untyped map carried.
 */
class DashboardBackendClientTest {

  private static final UUID ID = UUID.fromString("a0a0a0a0-0000-4000-8000-000000000001");

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
  void upcomingMissionsKeepTheConcatenatedQuery() {
    backend.answerJson(
        "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}");
    Instant start = Instant.parse("2026-10-05T08:30:00.123456Z");
    Instant end = Instant.parse("2026-10-12T08:30:00.123456Z");

    client.upcomingMissions(start, end);

    backend.expect(
        "GET",
        "/api/v1/missions/search?start="
            + start
            + "&end="
            + end
            + "&sort=plannedStartTime,asc&status=PLANNED&status=ACTIVE&size=50");
  }

  @Test
  void profileAndMemberships() {
    backend.answerJson("{}");
    backend.answerJson("[\"" + ID + "\"]");

    client.currentUser();
    assertThat(client.myOrgUnitIds()).containsExactly(ID);

    backend.expect("GET", "/api/v1/users/me");
    backend.expect("GET", "/api/v1/users/me/org-unit-ids");
  }

  @Test
  void announcement() {
    backend.answerJson(
        "{\"id\":\""
            + ID
            + "\",\"content\":\"Hallo\",\"updatedAt\":\"2026-10-01T12:00:00Z\",\"version\":4}");
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerJson("{\"content\":\"\",\"version\":4}");
    backend.answerEmpty();
    backend.answerEmpty();

    assertThat(client.announcement())
        .isEqualTo(new AnnouncementDto(ID, "Hallo", Instant.parse("2026-10-01T12:00:00Z"), 4L));
    assertThat(client.announcement()).isNull();
    client.markAnnouncementRead(ID);
    assertThat(client.adminAnnouncement().version()).isEqualTo(4L);
    client.saveAnnouncement(new AnnouncementRequest("Neu", null));
    client.deleteAnnouncement();

    backend.expect("GET", "/api/v1/announcement");
    backend.expect("GET", "/api/v1/announcement");
    backend.expect("PUT", "/api/v1/users/me/read-announcement/" + ID, null);
    backend.expect("GET", "/api/v1/announcement/admin");
    backend.expect("PUT", "/api/v1/announcement", "{\"content\":\"Neu\",\"version\":null}");
    backend.expect("DELETE", "/api/v1/announcement", null);
  }
}
