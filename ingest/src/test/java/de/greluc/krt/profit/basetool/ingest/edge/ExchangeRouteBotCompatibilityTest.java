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

package de.greluc.krt.profit.basetool.ingest.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.ingest.contract.ExchangeDocuments;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every exchange route of the committed OpenAPI document, and every schema URL, passes {@link
 * BotProtectionFilter}'s method, prefix and suffix lists (REQ-XCH-001).
 */
class ExchangeRouteBotCompatibilityTest {

  private static final String OPENAPI = "/api/exchange-v1.openapi.json";
  private static final List<String> HTTP_METHODS =
      List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  private final BotProtectionFilter filter =
      new BotProtectionFilter(
          mock(MeterRegistry.class),
          JsonMapper.builder().build(),
          TestLoggingProperties.defaults());

  @Test
  void everyDocumentedRoutePassesTheBotFilter() throws IOException {
    List<String> checked = new ArrayList<>();
    for (Map.Entry<String, JsonNode> path : openApi().get("paths").properties()) {
      String uri = path.getKey().replaceAll("\\{[^}]+}", "x");
      assertThat(uri).startsWith("/exchange/v1");
      for (String method : path.getValue().propertyNames()) {
        if (!HTTP_METHODS.contains(method)) {
          continue;
        }
        String upper = method.toUpperCase(Locale.ROOT);
        assertThat(BotProtectionFilter.ALLOWED_HTTP_METHODS).as(upper + " " + uri).contains(upper);
        assertThat(filter.isBotPath(uri)).as("prefix rule hits " + uri).isFalse();
        assertThat(filter.isBotFileExtension(uri)).as("suffix rule hits " + uri).isFalse();
        checked.add(upper + " " + uri);
      }
    }
    assertThat(checked)
        .as("the document is not vacuous")
        .contains("POST /exchange/v1/catalog/resolve", "GET /exchange/v1/me/blueprints");
  }

  @Test
  void everySchemaUrlPassesTheBotFilter() {
    for (String name : new ExchangeDocuments().schemaNames()) {
      String uri = "/exchange/v1/schemas/" + name;
      assertThat(filter.isBotPath(uri)).as(uri).isFalse();
      assertThat(filter.isBotFileExtension(uri)).as(uri).isFalse();
    }
  }

  /**
   * Reads the committed OpenAPI document.
   *
   * @return its root
   * @throws IOException if it cannot be read
   */
  private JsonNode openApi() throws IOException {
    try (InputStream in = getClass().getResourceAsStream(OPENAPI)) {
      assertThat(in).isNotNull();
      return JsonMapper.builder().build().readTree(in);
    }
  }
}
