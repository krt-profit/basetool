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

package de.greluc.krt.profit.basetool.backend.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.exchange.ExchangeFieldRatchet.FieldClass;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeAccountCheckDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeAccountCheckRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankAccountPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankBookingPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintDraftDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBlueprintPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeInstallationDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeInstallationLabelRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationListDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgDemandDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockMembersDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockTargetsDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitListDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryDraftRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRevocationRow;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeShipChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeShipPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockPageDto;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ratchet (REQ-XCH-050): every leaf property of the published v1 schemas and the OpenAPI document
 * carries a listed green class, and every field of a backend exchange DTO is declared in the schema
 * that publishes it, so a game text cannot reach a third party through a new field.
 */
class ExchangeFieldRatchetTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final Path SCHEMAS = Path.of("../ingest/src/main/resources/exchange/v1/schemas");

  private static final Path OPENAPI =
      Path.of("../ingest/src/main/resources/api/exchange-v1.openapi.json");

  /** The listed class of every published leaf property. */
  private static final String CLASSES = "/exchange/field-classes.txt";

  /** Every DTO the backend reads from or writes to the exchange, with its published schema. */
  private static final Map<Class<?>, String> PUBLISHED =
      Map.ofEntries(
          Map.entry(ExchangeInstallationDto.class, "installation.schema.json"),
          Map.entry(ExchangeInstallationLabelRequest.class, "installation.schema.json"),
          Map.entry(ExchangeAccountCheckRequest.class, "account-check-request.schema.json"),
          Map.entry(ExchangeAccountCheckDto.class, "account-check-response.schema.json"),
          Map.entry(ExchangeResolveRequest.class, "resolve-request.schema.json"),
          Map.entry(ExchangeResolveResponse.class, "resolve-response.schema.json"),
          Map.entry(ExchangeLocationListDto.class, "location-list.schema.json"),
          Map.entry(ExchangeBlueprintPageDto.class, "page.schema.json#/$defs/blueprintPage"),
          Map.entry(ExchangeStockPageDto.class, "page.schema.json#/$defs/stockPage"),
          Map.entry(ExchangeShipPageDto.class, "page.schema.json#/$defs/shipPage"),
          Map.entry(ExchangeOrgStockPageDto.class, "page.schema.json#/$defs/orgStockPage"),
          Map.entry(ExchangeBankAccountPageDto.class, "page.schema.json#/$defs/bankAccountPage"),
          Map.entry(ExchangeBankBookingPageDto.class, "page.schema.json#/$defs/bankBookingPage"),
          Map.entry(ExchangeBankBalanceSeriesDto.class, "bank-balance-series.schema.json"),
          Map.entry(ExchangeBankRequestPageDto.class, "page.schema.json#/$defs/bankRequestPage"),
          Map.entry(ExchangeChangeResultDto.class, "change-result.schema.json"),
          Map.entry(
              ExchangeBlueprintChangeSet.class, "change-set.schema.json#/$defs/blueprintChangeSet"),
          Map.entry(ExchangeStockChangeSet.class, "change-set.schema.json#/$defs/stockChangeSet"),
          Map.entry(ExchangeShipChangeSet.class, "change-set.schema.json#/$defs/shipChangeSet"),
          Map.entry(
              ExchangeOrgStockChangeSet.class, "change-set.schema.json#/$defs/orgStockChangeSet"),
          Map.entry(ExchangeOrgDemandDto.class, "org-demand.schema.json"),
          Map.entry(ExchangeOrgStockMembersDto.class, "org-stock-members.schema.json"),
          Map.entry(ExchangeOrgStockTargetsDto.class, "org-stock-targets.schema.json"),
          Map.entry(ExchangeOrgUnitListDto.class, "org-unit-list.schema.json"),
          Map.entry(ExchangeBlueprintDraftDto.class, "blueprint-draft.schema.json"),
          Map.entry(ExchangeRefineryDraftRequest.class, "refinery-draft.schema.json"));

  /** Records of the exchange DTO packages that never reach a client, each with the reason. */
  private static final Map<Class<?>, String> UNPUBLISHED =
      Map.of(
          ExchangeRevocationRow.class,
          "a row of the revocation mirror the backend writes to Redis for the gateway");

  @Test
  void everyPublishedLeafPropertyIsListed() throws IOException {
    assertThat(difference(publishedLeaves(), classes().keySet()))
        .as("published properties without a class in " + CLASSES)
        .isEmpty();
  }

  @Test
  void everyListedPropertyIsPublished() throws IOException {
    assertThat(difference(classes().keySet(), publishedLeaves()))
        .as("listed properties no published document declares")
        .isEmpty();
  }

  @Test
  void everyListedClassIsGreen() throws IOException {
    assertThat(ExchangeFieldRatchet.refused(classes()))
        .as("game texts and game media never reach a third party")
        .isEmpty();
  }

  @Test
  void everyDtoFieldIsDeclaredInItsSchema() throws IOException {
    Map<String, JsonNode> documents = published();
    Set<String> undeclared = new TreeSet<>();
    Set<Class<?>> reached = new HashSet<>();
    PUBLISHED.forEach((dto, schema) ->
        undeclared.addAll(ExchangeFieldRatchet.undeclaredFields(documents, dto, schema, reached)));
    assertThat(undeclared).as("DTO fields their v1 schema does not declare").isEmpty();
  }

  @Test
  void everyExchangeDtoIsPublishedOrNamedInternal() throws IOException {
    Map<String, JsonNode> documents = published();
    Set<Class<?>> reached = new HashSet<>();
    PUBLISHED.forEach(
        (dto, schema) -> ExchangeFieldRatchet.undeclaredFields(documents, dto, schema, reached));
    Set<String> unreached =
        exchangeDtoRecords().stream()
            .filter(type -> !reached.contains(type))
            .filter(type -> !UNPUBLISHED.containsKey(type))
            .map(Class::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(unreached)
        .as("exchange DTO records no published schema covers and no reason names")
        .isEmpty();
  }

  @Test
  void theOpenApiDocumentRefersOnlyToPublishedSchemas() throws IOException {
    Set<String> files = published().keySet();
    Set<String> refs = new TreeSet<>();
    collectRefs(MAPPER.readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8)), refs);
    assertThat(refs).isNotEmpty();
    assertThat(refs)
        .allSatisfy(ref ->
            assertThat(ref.startsWith("#/") || files.contains(schemaFile(ref))).as(ref).isTrue());
  }

  @Test
  void aDtoFieldMissingFromItsSchemaFailsAtAnyDepth() {
    Map<String, JsonNode> documents =
        Map.of(
            "planted.schema.json",
            MAPPER.readTree(
                """
                {"type": "object",
                 "properties": {
                   "id": {"type": "string"},
                   "children": {"type": "array", "items": {"$ref": "#/$defs/child"}},
                   "extensions": {"type": "object", "additionalProperties": {"type": "object"}}},
                 "$defs": {"child": {"type": "object", "properties": {"name": {"type": "string"}}}}}
                """));

    Set<String> undeclared =
        ExchangeFieldRatchet.undeclaredFields(
            documents, PlantedDto.class, "planted.schema.json", new HashSet<>());

    assertThat(undeclared).containsExactly("PlantedChild.description", "PlantedDto.lore");
  }

  @Test
  void aNewPropertyIsAnUnlistedLeafAndContainersAreNotListed() {
    Map<String, JsonNode> documents =
        Map.of(
            "planted.schema.json",
            MAPPER.readTree(
                """
                {"type": "object",
                 "properties": {
                   "id": {"type": "string"},
                   "children": {"type": "array", "items": {"$ref": "#/$defs/child"}}},
                 "$defs": {"child": {"type": "object", "properties": {"lore": {"type": "string"}}}}}
                """));

    assertThat(
            difference(
                ExchangeFieldRatchet.leaves(documents),
                Set.of("planted.schema.json#/properties/id")))
        .containsExactly("planted.schema.json#/$defs/child/properties/lore");
  }

  @Test
  void aYellowOrRedClassIsRefused() {
    assertThat(
            ExchangeFieldRatchet.refused(
                Map.of(
                    "a#/properties/id", FieldClass.IDENTIFIER,
                    "a#/properties/description", FieldClass.GAME_TEXT,
                    "a#/properties/image", FieldClass.GAME_MEDIA)))
        .containsExactly("a#/properties/description", "a#/properties/image");
  }

  /**
   * A planted DTO: {@code lore} is undeclared, {@code extensions} a declared free-form map.
   *
   * @param id declared
   * @param lore undeclared
   * @param children declared, each with an undeclared field
   * @param extensions declared free-form
   */
  record PlantedDto(
      String id, String lore, List<PlantedChild> children, Map<String, Object> extensions) {}

  /**
   * A planted nested DTO.
   *
   * @param name declared
   * @param description undeclared
   */
  record PlantedChild(String name, String description) {}

  /**
   * Lists the leaf properties of the v1 schemas and the inline ones of the OpenAPI document.
   *
   * @return the property names
   * @throws IOException if a document cannot be read
   */
  private static @NotNull Set<String> publishedLeaves() throws IOException {
    Map<String, JsonNode> schemas = published();
    Set<String> leaves = new TreeSet<>(ExchangeFieldRatchet.leaves(schemas));
    leaves.addAll(
        ExchangeFieldRatchet.openApiLeaves(
            schemas,
            OPENAPI.getFileName().toString(),
            MAPPER.readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8))));
    return leaves;
  }

  /**
   * Reads the published v1 schemas.
   *
   * @return the schemas by file name
   * @throws IOException if a schema cannot be read
   */
  private static @NotNull Map<String, JsonNode> published() throws IOException {
    Map<String, JsonNode> documents = new TreeMap<>();
    try (Stream<Path> files = Files.list(SCHEMAS)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".schema.json")).toList()) {
        documents.put(
            file.getFileName().toString(),
            MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8)));
      }
    }
    return documents;
  }

  /**
   * Reads the listed classes.
   *
   * @return the class of every listed property
   * @throws IOException if the list cannot be read
   */
  private static @NotNull Map<String, FieldClass> classes() throws IOException {
    Map<String, FieldClass> classes = new TreeMap<>();
    try (InputStream in = ExchangeFieldRatchetTest.class.getResourceAsStream(CLASSES)) {
      assertThat(in).as(CLASSES).isNotNull();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        if (!line.isBlank()) {
          String[] parts = line.trim().split(" ");
          assertThat(classes.put(parts[0], FieldClass.valueOf(parts[1])))
              .as("listed once: " + parts[0])
              .isNull();
        }
      }
    }
    return classes;
  }

  /**
   * Lists every record of the backend's exchange DTO packages.
   *
   * @return the records
   */
  private static @NotNull Set<Class<?>> exchangeDtoRecords() {
    Set<Class<?>> out = new HashSet<>();
    for (JavaClass type :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("de.greluc.krt.profit.basetool.backend.exchange")) {
      if (type.isTopLevelClass() && type.getPackageName().contains(".dto")) {
        out.addAll(ExchangeFieldRatchet.records(type.reflect()));
      }
    }
    return out;
  }

  /**
   * Collects every {@code $ref} of a document.
   *
   * @param node the node
   * @param out the collected references
   */
  private static void collectRefs(@NotNull JsonNode node, @NotNull Set<String> out) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        if ("$ref".equals(field.getKey()) && field.getValue().isString()) {
          out.add(field.getValue().stringValue());
        } else {
          collectRefs(field.getValue(), out);
        }
      }
    } else if (node.isArray()) {
      node.forEach(child -> collectRefs(child, out));
    }
  }

  /**
   * Returns the schema file a published {@code $ref} names.
   *
   * @param ref the reference
   * @return the file name below the schema base
   */
  private static @NotNull String schemaFile(@NotNull String ref) {
    String file = ref.replace(ExchangeFieldRatchet.BASE, "");
    int hash = file.indexOf('#');
    return hash < 0 ? file : file.substring(0, hash);
  }

  /**
   * Returns the elements of one set missing from another.
   *
   * @param left the set
   * @param right the elements to remove
   * @return the remaining elements, sorted
   */
  private static @NotNull Set<String> difference(
      @NotNull Set<String> left, @NotNull Set<String> right) {
    Set<String> out = new TreeSet<>(left);
    out.removeAll(right);
    return out;
  }
}
