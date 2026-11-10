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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockChangeSet.Operation;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Pins supported org-stock wire names, validation paths and exhaustive typed decoding. */
class OrgStockOperationContractTest {
  @Test
  void operationNamesMatchEveryGatewaySchemaBranch() throws Exception {
    var mapper = JsonMapper.builder().build();
    Path schemaPath =
        Path.of("../ingest/src/main/resources/exchange/v1/schemas/change-set.schema.json");
    if (!Files.exists(schemaPath))
      schemaPath = Path.of("ingest/src/main/resources/exchange/v1/schemas/change-set.schema.json");
    var schema = mapper.readTree(Files.readString(schemaPath));
    TreeSet<String> wireNames = new TreeSet<>();
    schema
        .path("$defs")
        .path("orgStockOp")
        .path("oneOf")
        .forEach(branch ->
            wireNames.add(branch.path("properties").path("op").path("const").stringValue()));
    assertThat(Arrays.stream(Operation.values()).map(Operation::wireName).toList())
        .containsExactlyInAnyOrderElementsOf(wireNames);
    for (Operation operation : Operation.values()) {
      var set =
          mapper.readValue(
              "{\"ops\":[{\"op\":\""
                  + operation.wireName()
                  + "\",\"unit\":\""
                  + UUID.randomUUID()
                  + "\"}]}",
              ExchangeOrgStockChangeSet.class);
      assertThat(set.ops().getFirst().operation()).isSameAs(operation);
      var encoded = mapper.readTree(mapper.writeValueAsString(set)).path("ops").get(0);
      assertThat(encoded.path("op").stringValue()).isEqualTo(operation.wireName());
      assertThat(encoded.has("operation")).isFalse();
    }
  }

  @Test
  void unsupportedInputKeepsTheOpValidationPath() {
    var mapper = JsonMapper.builder().build();
    var set =
        mapper.readValue(
            "{\"ops\":[{\"op\":\"unsupported\",\"unit\":\"" + UUID.randomUUID() + "\"}]}",
            ExchangeOrgStockChangeSet.class);
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(set))
          .anyMatch(v -> v.getPropertyPath().toString().equals("ops[0].op"));
    }
    assertThatThrownBy(() -> set.ops().getFirst().operation())
        .isInstanceOf(BadRequestException.class);
  }
}
