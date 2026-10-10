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

package de.greluc.krt.profit.basetool.frontend.notification.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.notification.model.NotificationRuleWriteRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link NotificationBackendClient} sends (plan F3). */
class NotificationBackendClientTest {

  private static final UUID ID = UUID.fromString("0b5e0b5e-0000-4000-8000-000000000001");

  private BackendClientHarness backend;
  private NotificationBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new NotificationBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void inbox() {
    backend.answerJson("[]");
    backend.answerJson(
        "{\"content\":[],\"page\":2,\"size\":50,\"totalElements\":0,\"totalPages\":0}");
    backend.answerJson("{\"count\":3}");
    backend.answerJson("{}");
    backend.answerJson("{\"affected\":4,\"unreadCount\":0}");
    backend.answerEmpty();
    backend.answerJson("{\"affected\":5,\"unreadCount\":0}");

    assertThat(client.recent(10)).isEmpty();
    assertThat(client.page(2, 50)).isNotNull();
    assertThat(client.unreadCount().count()).isEqualTo(3L);
    client.markRead(ID);
    client.markAllRead();
    client.delete(ID);
    client.clearRead();

    backend.expect("GET", "/api/v1/notifications/recent?limit=10");
    backend.expect("GET", "/api/v1/notifications?page=2&size=50&sort=createdAt,desc");
    backend.expect("GET", "/api/v1/notifications/unread-count");
    backend.expect("POST", "/api/v1/notifications/" + ID + "/read", null);
    backend.expect("POST", "/api/v1/notifications/read-all", null);
    backend.expect("DELETE", "/api/v1/notifications/" + ID, null);
    backend.expect("DELETE", "/api/v1/notifications/read", null);
  }

  @Test
  void rules() {
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();

    assertThat(client.rules()).isEmpty();
    client.rule(ID);
    client.createRule(null);
    client.updateRule(ID, null);
    client.deleteRule(ID);

    backend.expect("GET", "/api/v1/notification-rules");
    backend.expect("GET", "/api/v1/notification-rules/" + ID);
    backend.expect("POST", "/api/v1/notification-rules", null);
    backend.expect("PUT", "/api/v1/notification-rules/" + ID, null);
    backend.expect("DELETE", "/api/v1/notification-rules/" + ID, null);
  }

  @Test
  void aRuleBodyIsSentAsGiven() {
    backend.answerJson("{}");
    NotificationRuleWriteRequest request =
        new NotificationRuleWriteRequest(
            "JOB_ORDER_CREATED", "JOB_ORDER_CREATED", "Leads", true, false, 2L, List.of());

    client.createRule(request);

    backend.expect(
        "POST",
        "/api/v1/notification-rules",
        "{\"eventType\":\"JOB_ORDER_CREATED\",\"notificationType\":\"JOB_ORDER_CREATED\","
            + "\"description\":\"Leads\",\"enabled\":true,\"excludeActor\":false,\"version\":2,"
            + "\"selectors\":[]}");
  }
}
