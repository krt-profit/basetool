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
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The job-order notices of a client's write name the application in both languages, and the web's
 * keep the type's own wording without a placeholder (REQ-NOTIF-028, REQ-ORDERS-078).
 */
class JobOrderClientNoticeWordingTest {

  private static final Locale GERMAN = Locale.GERMAN;
  private static final Locale ENGLISH = Locale.ENGLISH;

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
        new NotificationRelays(new SimpleMeterRegistry()),
        SecurityConfig.roleHierarchy(),
        new SessionGates(List.of()));
  }

  private static Map<String, String> order(boolean throughClient) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("displayId", "12");
    params.put("handle", "Ada");
    params.put("orgUnit", "ALP");
    params.put("requester", "BRV");
    params.put("unit", "BRV");
    params.put("statusCode", "CANCELLED");
    if (throughClient) {
      params.put("viaCode", "CLIENT");
      params.put("app", "VerseKit");
    }
    return params;
  }

  @Test
  void aClientsCreateNamesTheApplication() {
    assertEquals(
        "Neuer Auftrag #12 für ALP über VerseKit",
        controller().render("JOB_ORDER_CREATED", order(true), GERMAN));
    assertEquals(
        "New job order #12 for ALP via VerseKit",
        controller().render("JOB_ORDER_CREATED", order(true), ENGLISH));
  }

  @Test
  void aClientsNeedChangesNameTheApplication() {
    assertEquals(
        "Auftrag #12 für ALP vom Auftraggeber BRV über VerseKit geändert",
        controller().render("JOB_ORDER_UPDATED_BY_REQUESTER", order(true), GERMAN));
    assertEquals(
        "Job order #12 Ada was updated by the responsible unit via VerseKit.",
        controller().render("JOB_ORDER_UPDATED_BY_RESPONSIBLE", order(true), ENGLISH));
  }

  @Test
  void aClientsCancelNamesTheApplication() {
    assertEquals(
        "Auftrag #12 Ada (BRV) wurde über VerseKit storniert.",
        controller().render("JOB_ORDER_FINISHED", order(true), GERMAN));
    assertEquals(
        "Job order #12 Ada (BRV) was cancelled via VerseKit.",
        controller().render("JOB_ORDER_FINISHED", order(true), ENGLISH));
  }

  @Test
  void theWebsNoticesKeepTheirWordingWithoutAPlaceholder() {
    assertEquals(
        "Neuer Auftrag #12 für ALP",
        controller().render("JOB_ORDER_CREATED", order(false), GERMAN));
    assertEquals(
        "Auftrag #12 Ada (BRV) wurde storniert.",
        controller().render("JOB_ORDER_FINISHED", order(false), GERMAN));
    assertEquals(
        "Job order #12 Ada was updated by the responsible unit.",
        controller().render("JOB_ORDER_UPDATED_BY_RESPONSIBLE", order(false), ENGLISH));
  }
}
