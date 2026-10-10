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
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendSideChannels;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A render parameter named {@code <name>Code} gives the placeholder {@code {<name>}} the localized
 * word of its code (REQ-NOTIF-028), so one notification type can say „verschoben" or „abgesagt"
 * without a type per word.
 */
class NotificationRenderCodesTest {

  private static NotificationPageController controller() {
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("notifications.type.generic", Locale.GERMAN, "Neue Benachrichtigung");
    messages.addMessage("notifications.type.generic", Locale.ENGLISH, "New notification");
    messages.addMessage(
        "notifications.type.SAMPLE", Locale.GERMAN, "{mission}: {change} durch {actor}");
    messages.addMessage(
        "notifications.type.SAMPLE", Locale.ENGLISH, "{mission}: {change} by {actor}");
    messages.addMessage("notifications.value.change.cancelled", Locale.GERMAN, "abgesagt");
    messages.addMessage("notifications.value.change.cancelled", Locale.ENGLISH, "cancelled");
    return new NotificationPageController(
        mock(NotificationBackendClient.class),
        messages,
        new BackendSideChannels(mock(WebClient.class), null),
        mock(OAuth2AuthorizedClientManager.class),
        new SimpleMeterRegistry());
  }

  private static Map<String, String> params(String code) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("mission", "Nachtflug");
    params.put("changeCode", code);
    params.put("actor", "Ada");
    return params;
  }

  @Test
  void aCodeIsRenderedAsTheWordOfTheMembersLanguage() {
    assertEquals(
        "Nachtflug: abgesagt durch Ada",
        controller().render("SAMPLE", params("cancelled"), Locale.GERMAN));
    assertEquals(
        "Nachtflug: cancelled by Ada",
        controller().render("SAMPLE", params("cancelled"), Locale.ENGLISH));
  }

  @Test
  void aCodeWithoutAWordIsShownAsItIs() {
    assertEquals(
        "Nachtflug: moved durch Ada",
        controller().render("SAMPLE", params("moved"), Locale.GERMAN));
  }

  @Test
  void aParameterNamedJustCodeIsAnOrdinaryParameter() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("code", "X1");
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("notifications.type.PLAIN", Locale.GERMAN, "Code {code}");
    NotificationPageController controller =
        new NotificationPageController(
            mock(NotificationBackendClient.class),
            messages,
            new BackendSideChannels(mock(WebClient.class), null),
            mock(OAuth2AuthorizedClientManager.class),
            new SimpleMeterRegistry());

    assertEquals("Code X1", controller.render("PLAIN", params, Locale.GERMAN));
  }

  @Test
  void anUnknownTypeFallsBackToTheGenericWording() {
    assertEquals(
        "Neue Benachrichtigung",
        controller().render("UNKNOWN", params("cancelled"), Locale.GERMAN));
  }
}
