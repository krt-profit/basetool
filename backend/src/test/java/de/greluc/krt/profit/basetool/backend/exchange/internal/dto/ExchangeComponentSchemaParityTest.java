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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The published component schema lists exactly the codes the backend writes, so the gateway never
 * refuses a component answer (REQ-XCH-049).
 */
class ExchangeComponentSchemaParityTest {

  private static final Path SCHEMA =
      Path.of("../ingest/src/main/resources/exchange/v1/schemas/component.schema.json");

  @Test
  void theSchemaListsEveryComponentType() throws IOException {
    assertThat(codes("componentType"))
        .containsExactlyElementsOf(
            Arrays.stream(ShipComponentType.values()).map(Enum::name).toList());
  }

  @Test
  void theSchemaListsEveryComponentClass() throws IOException {
    assertThat(codes("itemClass"))
        .containsExactlyElementsOf(
            Arrays.stream(ShipComponentClass.values()).map(Enum::name).toList());
  }

  /**
   * Reads the enum of one property of the published schema.
   *
   * @param property the property
   * @return its codes in schema order
   * @throws IOException if the schema cannot be read
   */
  private static @NotNull List<String> codes(@NotNull String property) throws IOException {
    JsonNode schema =
        JsonMapper.builder().build().readTree(Files.readString(SCHEMA, StandardCharsets.UTF_8));
    List<String> codes = new ArrayList<>();
    schema.path("properties").path(property).path("enum").forEach(c -> codes.add(c.asString()));
    return codes;
  }
}
