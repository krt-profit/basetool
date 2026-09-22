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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Pins the option lists of {@link AdminNotificationRulePageController} — the single source every
 * {@code <option>} of the notification-rule editor is rendered from — against the backend and the
 * message bundles.
 *
 * <p>The frontend holds no copy of the backend enums, so these lists are hand-kept. Two drifts are
 * silent at runtime and are therefore gated here: a backend enum that gains a value the editor does
 * not offer (the defect this editor shipped with — four of twelve event types, and no {@code
 * ACCOUNT_RESPONSIBLE}, so opening a seeded rule selected an option that did not exist), and a code
 * whose label key no bundle declares. The label keys are built at render time as {@code
 * <prefix>.__${code}__}, which {@code MessageBundleConsistencyTest}'s literal-key scan cannot see,
 * and a missing one renders as {@code ??key??} in the admin's dropdown.
 */
class AdminNotificationRuleOptionListsTest {

  /** Candidate locations of the committed OpenAPI document, relative to the test working dir. */
  private static final List<Path> OPENAPI_CANDIDATES =
      List.of(
          Path.of("..", "backend", "src", "main", "resources", "api", "openapi.json"),
          Path.of("backend", "src", "main", "resources", "api", "openapi.json"));

  /** The three bundles every label must be declared in, by the name a failure should show. */
  private static final List<String> BUNDLES =
      List.of("messages.properties", "messages_de.properties", "messages_en.properties");

  /**
   * Each enum-backed list equals the enum values the backend publishes in {@code openapi.json}, in
   * the same order — so the editor offers every value, and in the backend's declaration order.
   *
   * @throws IOException when {@code openapi.json} cannot be read
   */
  @Test
  void enumListsMatchTheBackendEnumsInDeclarationOrder() throws IOException {
    JsonNode schemas = loadOpenApiSchemas();

    assertThat(AdminNotificationRulePageController.EVENT_TYPES)
        .as("NotificationEventType")
        .containsExactlyElementsOf(enumValues(schemas, "NotificationRuleDto", "eventType"));
    assertThat(AdminNotificationRulePageController.NOTIFICATION_TYPES)
        .as("NotificationType")
        .containsExactlyElementsOf(enumValues(schemas, "NotificationRuleDto", "notificationType"));
    assertThat(AdminNotificationRulePageController.SELECTOR_KINDS)
        .as("SelectorKind")
        .containsExactlyElementsOf(
            enumValues(schemas, "NotificationRuleSelectorWriteRequest", "kind"));
    assertThat(AdminNotificationRulePageController.ORG_RELATIVE_ROLES)
        .as("OrgRelativeRole")
        .containsExactlyElementsOf(
            enumValues(schemas, "NotificationRuleSelectorWriteRequest", "orgRelativeRole"));
    assertThat(AdminNotificationRulePageController.CONTEXT_ROLES)
        .as("NotificationContextRole")
        .containsExactlyElementsOf(
            enumValues(schemas, "NotificationRuleSelectorWriteRequest", "contextRole"));
  }

  /**
   * Every code of every list has its label in all three bundles, as do the two static selector
   * texts the page references.
   *
   * @throws IOException when a bundle cannot be read
   */
  @Test
  void everyOptionHasALabelInEveryBundle() throws IOException {
    List<String> keys = new ArrayList<>();
    addKeys(
        keys,
        "admin.notificationRules.eventType.",
        AdminNotificationRulePageController.EVENT_TYPES);
    addKeys(
        keys,
        "admin.notificationRules.notificationType.",
        AdminNotificationRulePageController.NOTIFICATION_TYPES);
    addKeys(
        keys,
        "admin.notificationRules.selector.kind.",
        AdminNotificationRulePageController.SELECTOR_KINDS);
    addKeys(
        keys,
        "admin.notificationRules.selector.orgRelativeRole.",
        AdminNotificationRulePageController.ORG_RELATIVE_ROLES);
    addKeys(
        keys,
        "admin.notificationRules.selector.contextRole.",
        AdminNotificationRulePageController.CONTEXT_ROLES);
    addKeys(
        keys,
        "admin.notificationRules.selector.roleCode.",
        AdminNotificationRulePageController.ROLE_CODES);
    keys.add("admin.notificationRules.selector.fromEvent");
    keys.add("admin.notificationRules.selector.unknown");

    Map<String, List<String>> missingByBundle = new LinkedHashMap<>();
    for (String bundle : BUNDLES) {
      Properties properties = loadBundle(bundle);
      List<String> missing =
          keys.stream().filter(key -> properties.getProperty(key) == null).toList();
      if (!missing.isEmpty()) {
        missingByBundle.put(bundle, missing);
      }
    }

    assertThat(missingByBundle)
        .as(
            "notification-rule editor labels no bundle declares — Thymeleaf renders them as"
                + " ??key_locale?? in the admin's dropdowns")
        .isEmpty();
  }

  /**
   * Appends {@code prefix + code} for every code.
   *
   * @param keys the list the keys are appended to
   * @param prefix the label-key prefix, ending in a dot
   * @param codes the codes to label
   */
  private static void addKeys(List<String> keys, String prefix, List<String> codes) {
    codes.forEach(code -> keys.add(prefix + code));
  }

  /**
   * Reads the {@code enum} array of one schema property.
   *
   * @param schemas the {@code components.schemas} node
   * @param schema the schema name
   * @param property the property name
   * @return the enum constant names in document order
   */
  private static List<String> enumValues(JsonNode schemas, String schema, String property) {
    JsonNode values = schemas.path(schema).path("properties").path(property).path("enum");
    assertThat(values.isArray())
        .as("openapi.json types %s.%s as an enum", schema, property)
        .isTrue();
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.asText()));
    return result;
  }

  /**
   * Loads {@code components.schemas} from the committed OpenAPI document, trying the
   * module-relative and the repository-relative path so the test runs from either working
   * directory.
   *
   * @return the schemas node
   * @throws IOException when the document cannot be read
   */
  private static JsonNode loadOpenApiSchemas() throws IOException {
    Path path =
        OPENAPI_CANDIDATES.stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "openapi.json not found; looked at " + OPENAPI_CANDIDATES));
    return new ObjectMapper().readTree(path.toFile()).path("components").path("schemas");
  }

  /**
   * Loads one message bundle from the module's resources, from either working directory.
   *
   * @param name the bundle's file name
   * @return the parsed bundle
   * @throws IOException when the bundle cannot be read
   */
  private static Properties loadBundle(String name) throws IOException {
    Path direct = Path.of("src", "main", "resources", name);
    Path path = Files.exists(direct) ? direct : Path.of("frontend").resolve(direct);
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
}
