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

package de.greluc.krt.profit.basetool.frontend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationPreferenceDto;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The areas of the profile's notification card (REQ-NOTIF-027): every notification type the backend
 * publishes has an area and a label in all three bundles, so a new type fails the build until a
 * member can read what they would be turning off.
 */
class NotificationPreferenceGroupsTest {

  private static final List<Path> OPENAPI_CANDIDATES =
      List.of(
          Path.of("..", "backend", "src", "main", "resources", "api", "openapi.json"),
          Path.of("backend", "src", "main", "resources", "api", "openapi.json"));

  private static final List<String> BUNDLES =
      List.of("messages.properties", "messages_de.properties", "messages_en.properties");

  private static NotificationPreferenceDto pref(String type, boolean mutable) {
    return new NotificationPreferenceDto(type, mutable, false);
  }

  @Test
  void aTypeIsSortedIntoTheAreaItsPrefixNames() {
    assertThat(NotificationPreferenceGroups.groupOf("JOB_ORDER_CREATED")).isEqualTo("orders");
    assertThat(NotificationPreferenceGroups.groupOf("BANK_ACCOUNT_RESPONSIBLE_ASSIGNED"))
        .isEqualTo("bank");
    assertThat(NotificationPreferenceGroups.groupOf("MATERIAL_EXCHANGE_INTEREST_REGISTERED"))
        .isEqualTo("market");
    assertThat(NotificationPreferenceGroups.groupOf("INVENTORY_TRANSFERRED_TO_USER"))
        .isEqualTo("inventory");
    assertThat(NotificationPreferenceGroups.groupOf("EXCHANGE_BULK_UNDO_APPLIED"))
        .isEqualTo("connectedApps");
    assertThat(NotificationPreferenceGroups.groupOf("ACCOUNT_DELETION_REQUESTED"))
        .isEqualTo("account");
    assertThat(NotificationPreferenceGroups.groupOf("DISCORD_REGISTRATION_PENDING"))
        .isEqualTo("account");
  }

  @Test
  void aTypeNoPrefixClaimsLandsInOther() {
    assertThat(NotificationPreferenceGroups.groupOf("SOMETHING_NEW"))
        .isEqualTo(NotificationPreferenceGroups.OTHER);
  }

  @Test
  void groupsKeepTheOrderAreasFirstAppearAndDropEmptyOnes() {
    List<NotificationPreferenceGroups.Group> groups =
        NotificationPreferenceGroups.group(
            List.of(
                pref("JOB_ORDER_CREATED", true),
                pref("BANK_BOOKING_REQUEST_CREATED", true),
                pref("JOB_ORDER_UPDATED_BY_REQUESTER", true),
                pref("ACCOUNT_DELETION_REQUESTED", false)));

    assertThat(groups)
        .extracting(NotificationPreferenceGroups.Group::key)
        .containsExactly("orders", "bank", "account");
    assertThat(groups.get(0).items())
        .extracting(NotificationPreferenceDto::type)
        .containsExactly("JOB_ORDER_CREATED", "JOB_ORDER_UPDATED_BY_REQUESTER");
  }

  @Test
  void noPreferencesGiveNoGroups() {
    assertThat(NotificationPreferenceGroups.group(List.of())).isEmpty();
  }

  @Test
  void everyBackendTypeHasAnAreaAndALabelInEveryBundle() throws IOException {
    List<String> types = notificationTypes();
    assertThat(types).isNotEmpty();
    List<String> keys = new ArrayList<>();
    for (String type : types) {
      keys.add("profile.notifications.type." + type);
      keys.add("profile.notifications.group." + NotificationPreferenceGroups.groupOf(type));
    }
    keys.add("profile.notifications.group." + NotificationPreferenceGroups.OTHER);

    for (String bundle : BUNDLES) {
      Properties properties = loadBundle(bundle);
      assertThat(keys.stream().filter(key -> properties.getProperty(key) == null).toList())
          .as("profile notification labels missing in %s", bundle)
          .isEmpty();
    }
  }

  @Test
  void noBackendTypeFallsIntoOtherWithoutADecision() throws IOException {
    assertThat(notificationTypes())
        .as("a type in 'other' means no prefix names its area — add one to the groups")
        .allSatisfy(
            type ->
                assertThat(NotificationPreferenceGroups.groupOf(type))
                    .isNotEqualTo(NotificationPreferenceGroups.OTHER));
  }

  private static List<String> notificationTypes() throws IOException {
    Path openApi =
        OPENAPI_CANDIDATES.stream()
            .filter(Files::exists)
            .findFirst()
            .orElseThrow(() -> new IOException("openapi.json not found"));
    JsonNode schemas =
        new ObjectMapper().readTree(openApi.toFile()).path("components").path("schemas");
    List<String> types = new ArrayList<>();
    schemas
        .path("NotificationPreferenceDto")
        .path("properties")
        .path("type")
        .path("enum")
        .forEach(node -> types.add(node.asText()));
    return types;
  }

  private static Properties loadBundle(String name) throws IOException {
    Properties properties = new Properties();
    Path direct = Path.of("src", "main", "resources", name);
    Path path = Files.exists(direct) ? direct : Path.of("frontend").resolve(direct);
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
}
