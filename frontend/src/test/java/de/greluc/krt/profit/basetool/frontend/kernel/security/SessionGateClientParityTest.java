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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the browser half of the session gates to the one gate list (ADR-0385): the AJAX headers
 * {@code krt-fetch.js} acts on and the SSE events {@code notifications.js} listens for are exactly
 * those of the gates the context holds, in their order. Shares the context of {@code
 * ContextShapeTest}.
 */
@SpringBootTest
class SessionGateClientParityTest {

  /** The transport module that reads a gate's AJAX header. */
  private static final String KRT_FETCH_MODULE = "/static/js/kernel/krt-fetch.js";

  /** The notification module that follows a gate's SSE event. */
  private static final String NOTIFICATIONS_MODULE = "/static/js/notification/notifications.js";

  /** The header list in {@code krt-fetch.js}. */
  private static final Pattern GATE_HEADERS =
      Pattern.compile("SESSION_GATE_HEADERS = \\[([^\\]]*)\\]");

  /** The event list in {@code notifications.js}. */
  private static final Pattern GATE_EVENTS =
      Pattern.compile("SESSION_GATE_EVENTS = \\[([^\\]]*)\\]");

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @Autowired private SessionGates sessionGates;

  @Test
  void theConsentGateRunsBeforeTheHomeLocationGate() {
    assertThat(sessionGates.all())
        .extracting(SessionGate::problemCode)
        .containsExactly("TERMS_NOT_ACCEPTED", "HOME_LOCATION_REQUIRED");
  }

  @Test
  void krtFetchActsOnExactlyTheGatesHeaders() throws IOException {
    assertThat(listIn(KRT_FETCH_MODULE, GATE_HEADERS))
        .as("krtSessionGate reads a header no gate sends, or misses one a gate sends")
        .containsExactlyElementsOf(
            sessionGates.all().stream().map(SessionGate::ajaxHeader).toList());
  }

  @Test
  void theNotificationStreamFollowsExactlyTheGatesEvents() throws IOException {
    assertThat(listIn(NOTIFICATIONS_MODULE, GATE_EVENTS))
        .as("notifications.js listens for an event no gate sends, or misses one a gate sends")
        .containsExactlyElementsOf(sessionGates.all().stream().map(SessionGate::sseEvent).toList());
  }

  /**
   * Reads a quoted string list out of a script.
   *
   * @param resource the script's classpath resource
   * @param list the pattern whose first group holds the list's items
   * @return the items without their quotes
   * @throws IOException if the script cannot be read
   */
  private static @NotNull List<String> listIn(@NotNull String resource, @NotNull Pattern list)
      throws IOException {
    Matcher matcher = list.matcher(readResource(resource));
    assertThat(matcher.find()).as("the gate list not found in %s (renamed?)", resource).isTrue();
    return Arrays.stream(matcher.group(1).split(","))
        .map(item -> item.strip().replace("'", ""))
        .toList();
  }

  /**
   * Reads a classpath resource as UTF-8 text, failing the test if it is missing.
   *
   * @param resource the absolute classpath resource path
   * @return the resource content
   * @throws IOException if the resource stream cannot be read
   */
  private static @NotNull String readResource(@NotNull String resource) throws IOException {
    try (InputStream in = SessionGateClientParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
