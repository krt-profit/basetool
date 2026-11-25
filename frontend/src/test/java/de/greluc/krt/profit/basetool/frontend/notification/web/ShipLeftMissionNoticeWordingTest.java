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

package de.greluc.krt.profit.basetool.frontend.notification.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendSideChannels;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SecurityConfig;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGates;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The gap notice of a ship leaving a mission unit reads, for the cause {@code DELETED}, exactly as
 * a notice stored without a cause, in both languages (REQ-HANGAR-006, REQ-NOTIF-028).
 */
class ShipLeftMissionNoticeWordingTest {

  private static final String TYPE = "HANGAR_SHIP_REMOVED_FROM_UNIT";
  private static final String GERMAN_TEXT =
      "Das Schiff Cutlass Black der Einheit Alpha im Einsatz Op Aurora wurde gelöscht und fällt"
          + " aus der Planung";
  private static final String ENGLISH_TEXT =
      "The ship Cutlass Black of the unit Alpha in the mission Op Aurora was deleted and drops out"
          + " of the plan";

  private static NotificationPageController controller() {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasenames("messages");
    messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
    messages.setFallbackToSystemLocale(false);
    return new NotificationPageController(
        mock(NotificationBackendClient.class),
        messages,
        new BackendSideChannels(mock(WebClient.class), null),
        mock(OAuth2AuthorizedClientManager.class),
        new SimpleMeterRegistry(),
        SecurityConfig.roleHierarchy(),
        new SessionGates(List.of()));
  }

  private static Map<String, String> gap(@Nullable String cause) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("shipType", "Cutlass Black");
    params.put("mission", "Op Aurora");
    params.put("unit", "Alpha");
    if (cause != null) {
      params.put("causeCode", cause);
    }
    return params;
  }

  @Test
  void aDeletedShipKeepsTheDeletionWording() {
    assertEquals(GERMAN_TEXT, controller().render(TYPE, gap("DELETED"), Locale.GERMAN));
    assertEquals(ENGLISH_TEXT, controller().render(TYPE, gap("DELETED"), Locale.ENGLISH));
  }

  @Test
  void aNoticeStoredWithoutACauseReadsAsADeletedShip() {
    assertEquals(GERMAN_TEXT, controller().render(TYPE, gap(null), Locale.GERMAN));
    assertEquals(ENGLISH_TEXT, controller().render(TYPE, gap(null), Locale.ENGLISH));
  }
}
