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

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the server-rendered section keys (REQ-FE-010, ADR-0289): every page's head carries one meta
 * element per {@link LiveSyncTopicClass} with exactly its {@link
 * LiveSyncTopicClass#allowedSections()}, the single source the page modules read.
 */
@SpringBootTest
class LiveSyncSectionKeysTest {

  private static final Pattern META = Pattern.compile("<meta\\b[^>]*krt-live-sync-sections[^>]*>");

  private static final Pattern TOPIC_CLASS = Pattern.compile("data-topic-class=\"([A-Z_]+)\"");

  private static final Pattern CONTENT = Pattern.compile("content=\"([^\"]*)\"");

  @Autowired private WebApplicationContext context;

  @Autowired private LiveSyncSectionKeys sectionKeys;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @Test
  void everyTopicClassIsListedWithItsSortedWhitelist() {
    Map<String, String> keys = sectionKeys.byTopicClass();

    assertThat(keys.keySet())
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(LiveSyncTopicClass.values()).map(Enum::name).toList());
    assertThat(keys)
        .containsEntry(
            "MISSION", "crew,finance,frequencies,mgmt,objectives,organisation,overview,steps");
    assertThat(keys).containsEntry("INVENTORY_ALL", "stock");
  }

  @Test
  void theHeadRendersOneMetaPerTopicClassWithItsWhitelist() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String html =
        mockMvc
            .perform(get("/impressum"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Matcher matcher = META.matcher(html);
    Map<String, Set<String>> rendered = new HashMap<>();
    while (matcher.find()) {
      Matcher topicClass = TOPIC_CLASS.matcher(matcher.group());
      Matcher content = CONTENT.matcher(matcher.group());
      assertThat(topicClass.find() && content.find()).as(matcher.group()).isTrue();
      rendered.put(
          topicClass.group(1),
          Arrays.stream(content.group(1).split(",")).collect(Collectors.toSet()));
    }

    assertThat(rendered.keySet())
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(LiveSyncTopicClass.values()).map(Enum::name).toList());
    for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
      assertThat(rendered.get(topicClass.name()))
          .as("rendered keys of %s", topicClass)
          .containsExactlyInAnyOrderElementsOf(topicClass.allowedSections());
    }
  }
}
