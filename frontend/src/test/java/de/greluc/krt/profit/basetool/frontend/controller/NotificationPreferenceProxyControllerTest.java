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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationPreferenceDto;
import de.greluc.krt.profit.basetool.frontend.notification.NotificationPreferenceGroups;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Mockito tests for {@link NotificationPreferenceProxyController} (REQ-NOTIF-027): the card
 * fragment groups the member's preferences by area and flags itself unavailable when the backend
 * cannot be read.
 */
class NotificationPreferenceProxyControllerTest {

  private static NotificationPreferenceProxyController controller(BackendApiClient client) {
    return new NotificationPreferenceProxyController(new NotificationBackendClient(client));
  }

  @Test
  @SuppressWarnings("unchecked")
  void card_groupsThePreferencesByArea() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(eq("/api/v1/notifications/preferences"), any(ParameterizedTypeReference.class)))
        .thenReturn(
            List.of(
                new NotificationPreferenceDto("JOB_ORDER_CREATED", true, false),
                new NotificationPreferenceDto("ACCOUNT_DELETION_REQUESTED", false, false)));
    Model model = new ConcurrentModel();

    String view = controller(client).card(model);

    assertEquals("fragments/profile-notification-prefs :: card", view);
    assertEquals(false, model.getAttribute("notificationPrefsUnavailable"));
    List<NotificationPreferenceGroups.Group> groups =
        (List<NotificationPreferenceGroups.Group>) model.getAttribute("notificationPrefGroups");
    assertEquals(List.of("orders", "account"), groups.stream().map(g -> g.key()).toList());
  }

  @Test
  void card_flagsTheCardUnavailableWhenTheBackendCannotBeRead() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(eq("/api/v1/notifications/preferences"), any(ParameterizedTypeReference.class)))
        .thenThrow(new IllegalStateException("down"));
    Model model = new ConcurrentModel();

    controller(client).card(model);

    assertEquals(true, model.getAttribute("notificationPrefsUnavailable"));
    assertTrue(((List<?>) model.getAttribute("notificationPrefGroups")).isEmpty());
  }
}
