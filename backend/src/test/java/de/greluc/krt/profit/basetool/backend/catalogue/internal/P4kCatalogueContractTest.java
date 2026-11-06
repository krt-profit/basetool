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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueReader;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemPlayability;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipPlayability;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The import reads the contract's additive rules and nothing of the old format (REQ-P4K-003). */
class P4kCatalogueContractTest {

  private final P4kCatalogueReader reader = new P4kCatalogueReader(JsonMapper.builder().build());

  private P4kCatalogue read(byte[] bytes) {
    return reader.read(new ByteArrayInputStream(bytes));
  }

  @Test
  void theFixture_validatesAgainstTheP4kxSchema() throws IOException {
    String schema;
    try (InputStream in = getClass().getResourceAsStream("/p4k/basetool-catalog.schema.json")) {
      assertThat(in).isNotNull();
      schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String fixture = new String(P4kTestCatalogues.fixtureBytes(), StandardCharsets.UTF_8);

    List<Error> errors =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schema, InputFormat.JSON)
            .validate(fixture, InputFormat.JSON);

    assertThat(errors).isEmpty();
  }

  @Test
  void theFixtureHead_isALiveCatalogueOfVersion2() {
    P4kCatalogue catalogue = read(P4kTestCatalogues.fixtureBytes());

    assertThat(catalogue.head().schemaVersion()).isEqualTo(2);
    assertThat(catalogue.head().source().channel()).isEqualTo("LIVE");
    assertThat(catalogue.head().build().changeNumber()).isEqualTo(P4kTestCatalogues.CHANGE_NUMBER);
  }

  @Test
  void anExtraKindAndExtraFieldsOnEveryEntity_readLikeWithout() {
    ObjectNode root = P4kTestCatalogues.tree();
    root.putArray("futureKind").addObject().put("guid", "x");
    ((ObjectNode) root.get("source")).put("futureField", 1);
    for (String kind : List.of("manufacturers", "items", "ships", "commodities", "blueprints")) {
      for (JsonNode record : root.get(kind)) {
        ((ObjectNode) record).putObject("futureField").put("nested", true);
        ((ObjectNode) record.get("name")).put("fr", "nom");
      }
    }

    assertThat(read(P4kTestCatalogues.bytes(root)))
        .isEqualTo(read(P4kTestCatalogues.fixtureBytes()));
  }

  @Test
  void anUnknownClosedSetValue_readsAsNull() {
    assertThat(P4kClosedSet.parse(P4kItemPlayability.class, "crew")).isNull();
    assertThat(P4kClosedSet.isUnknown(P4kItemPlayability.class, "crew")).isTrue();
    assertThat(P4kClosedSet.isUnknown(P4kItemPlayability.class, null)).isFalse();
    assertThat(P4kClosedSet.parse(P4kShipPlayability.class, "ai")).isEqualTo(P4kShipPlayability.AI);
  }

  @Test
  void noClassOfTheOldFlatFormatRemains() {
    assertThatThrownBy(() ->
            Class.forName(
                "de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogDto"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() ->
            Class.forName(
                "de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kIngredientDto"))
        .isInstanceOf(ClassNotFoundException.class);
  }
}
