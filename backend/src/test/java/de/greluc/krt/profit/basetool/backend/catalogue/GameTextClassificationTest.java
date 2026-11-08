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

package de.greluc.krt.profit.basetool.backend.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.kernel.GameMedia;
import de.greluc.krt.profit.basetool.backend.kernel.GameText;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every catalogue column holding a game text carries {@link GameText}, read against the {@code
 * "x-gameText"} annotations of the vendored p4kx catalogue schema, and no column carries both
 * classes (REQ-P4K-033).
 */
class GameTextClassificationTest {

  /** The catalogue's entities, main sources only. */
  private static final JavaClasses CATALOGUE =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend.catalogue");

  /** The vendored p4kx catalogue schema; its provenance is in {@code p4k/README.md} beside it. */
  private static final String SCHEMA = "/p4k/basetool-catalog.schema.json";

  /**
   * Description columns no game-data source fills: an admin writes them in the Basetool, so they
   * hold no game text. Each with the writer.
   */
  private static final Map<String, String> ADMIN_AUTHORED =
      Map.of(
          "Location.description", "LocationService, the admin's location form",
          "StarSystem.description", "StarSystemService, the admin's star-system form",
          "FrequencyType.description", "FrequencyTypeService, the admin's frequency-type form",
          "RefiningMethod.description", "RefiningMethodService, the admin's refining-method form",
          "JobType.description", "JobTypeService, the admin's job-type form");

  /**
   * The entity fields filled from a schema property marked {@code "x-gameText": true}, keyed by the
   * property's JSON Pointer in the schema.
   */
  private static final Map<String, List<String>> IMPORTED_GAME_TEXTS =
      Map.of(
          "/$defs/Manufacturer/properties/description",
          List.of("Manufacturer.description", "Manufacturer.descriptionDe"),
          "/$defs/Item/properties/description",
          List.of("GameItem.descriptionEn", "GameItem.descriptionDe"),
          "/$defs/Ship/properties/description",
          List.of("ShipType.descriptionEn", "ShipType.descriptionDe"),
          "/$defs/Commodity/properties/description",
          List.of("Material.description"),
          "/$defs/StarmapObject/properties/name",
          List.of("StarmapObject.name", "StarmapObject.nameDe"));

  /** The schema properties marked {@code "x-gameText": true} that the Basetool does not store. */
  private static final Set<String> NOT_IMPORTED =
      Set.of(
          "/$defs/AiName/properties/givenLastName",
          "/$defs/AiName/properties/givenName",
          "/$defs/AiName/properties/givenNickName",
          "/$defs/BrokerEntry/properties/commsChannelName",
          "/$defs/BrokerEntry/properties/description",
          "/$defs/BrokerEntry/properties/hudTitle",
          "/$defs/BrokerEntry/properties/missionGiver",
          "/$defs/BrokerEntry/properties/title",
          "/$defs/Commodity/properties/displayType",
          "/$defs/ContractEntry/properties/contractor",
          "/$defs/ContractEntry/properties/description",
          "/$defs/ContractEntry/properties/missionType",
          "/$defs/ContractEntry/properties/title",
          "/$defs/ContractFaction/properties/displayName",
          "/$defs/ContractTemplate/properties/title",
          "/$defs/FactionReputation/properties/displayName",
          "/$defs/Finish/properties/endReason",
          "/$defs/InstanceEntry/properties/hudObjectiveText",
          "/$defs/Item/properties/displayType",
          "/$defs/LocationName/properties/displayName",
          "/$defs/MineableComposition/properties/depositName",
          "/$defs/MissionType/properties/typeName",
          "/$defs/NavPoint/properties/displayName",
          "/$defs/NumberOption/properties/displayName",
          "/$defs/ObjectiveDisplay/properties/longDescription",
          "/$defs/ObjectiveDisplay/properties/markerLabel",
          "/$defs/ObjectiveDisplay/properties/shortDescription",
          "/$defs/OverrideDisplay/properties/descriptionOverride",
          "/$defs/OverrideDisplay/properties/titleOverride",
          "/$defs/PickupPlace/properties/place",
          "/$defs/RefiningProcess/properties/processName",
          "/$defs/ReputationScope/properties/displayName",
          "/$defs/ReputationStanding/properties/displayName",
          "/$defs/StringOption/properties/displayName");

  @Test
  void everyDescriptionColumnIsAGameTextOrAdminAuthored() {
    Set<String> unclassified = new TreeSet<>();
    Set<String> descriptions = new TreeSet<>();
    for (JavaField field : entityFields()) {
      if (!field.getName().toLowerCase(Locale.ROOT).startsWith("description")) {
        continue;
      }
      String name = name(field);
      descriptions.add(name);
      if (!field.isAnnotatedWith(GameText.class) && !ADMIN_AUTHORED.containsKey(name)) {
        unclassified.add(name);
      }
    }
    assertThat(unclassified).as("description columns without @GameText").isEmpty();
    assertThat(descriptions)
        .as("every admin-authored column exists")
        .containsAll(ADMIN_AUTHORED.keySet());
  }

  @Test
  void anAdminAuthoredColumnIsNoGameText() {
    assertThat(entityFields())
        .filteredOn(field -> ADMIN_AUTHORED.containsKey(name(field)))
        .noneMatch(field -> field.isAnnotatedWith(GameText.class));
  }

  @Test
  void theVendoredSchemaMarksGameTexts() throws IOException {
    assertThat(gameTextPointers(schema())).hasSizeGreaterThanOrEqualTo(39);
  }

  @Test
  void everyFieldFilledFromASchemaGameTextCarriesGameText() throws IOException {
    assertThat(
            violations(
                schema(), IMPORTED_GAME_TEXTS, NOT_IMPORTED, annotatedFields(), entityFieldNames()))
        .isEmpty();
  }

  @Test
  void aSchemaGameTextOnAnUnannotatedColumnFails() {
    JsonNode planted =
        JsonMapper.builder()
            .build()
            .readTree(
                """
                {"$defs": {"Thing": {"properties": {
                  "lore": {"type": "string", "x-gameText": true},
                  "tagline": {"type": "string", "x-gameText": true},
                  "name": {"type": "string"}}}}}
                """);

    List<String> violations =
        violations(
            planted,
            Map.of("/$defs/Thing/properties/lore", List.of("Thing.lore")),
            Set.of("/$defs/Gone/properties/text"),
            Set.of(),
            Set.of("Thing.lore"));

    assertThat(violations)
        .containsExactlyInAnyOrder(
            "/$defs/Thing/properties/lore fills Thing.lore, which lacks @GameText",
            "/$defs/Thing/properties/tagline is a game text the mapping does not list",
            "/$defs/Gone/properties/text is listed but is no game text of the schema");
  }

  @Test
  void noColumnCarriesBothClasses() {
    assertThat(entityFields())
        .noneMatch(field ->
            field.isAnnotatedWith(GameText.class) && field.isAnnotatedWith(GameMedia.class));
  }

  @Test
  void noColumnCarriesGameMedia() {
    assertThat(entityFields()).noneMatch(field -> field.isAnnotatedWith(GameMedia.class));
  }

  /**
   * Checks the mapping of a schema's game texts onto the entity fields.
   *
   * @param schema the catalogue schema
   * @param imported the entity fields each imported game-text property fills
   * @param notImported the game-text properties the Basetool does not store
   * @param annotated the entity fields that carry {@code @GameText}, as {@code Entity.field}
   * @param existing every entity field, as {@code Entity.field}
   * @return one message per violation; empty when the mapping holds
   */
  private static @NotNull List<String> violations(
      @NotNull JsonNode schema,
      @NotNull Map<String, List<String>> imported,
      @NotNull Set<String> notImported,
      @NotNull Set<String> annotated,
      @NotNull Set<String> existing) {
    Set<String> gameTexts = gameTextPointers(schema);
    Set<String> out = new TreeSet<>();
    for (String pointer : gameTexts) {
      if (!imported.containsKey(pointer) && !notImported.contains(pointer)) {
        out.add(pointer + " is a game text the mapping does not list");
      }
    }
    Set<String> listed = new TreeSet<>(imported.keySet());
    listed.addAll(notImported);
    for (String pointer : listed) {
      if (!gameTexts.contains(pointer)) {
        out.add(pointer + " is listed but is no game text of the schema");
      }
    }
    imported.forEach((pointer, fields) -> {
      for (String field : fields) {
        if (!existing.contains(field)) {
          out.add(pointer + " fills " + field + ", which does not exist");
        } else if (!annotated.contains(field)) {
          out.add(pointer + " fills " + field + ", which lacks @GameText");
        }
      }
    });
    return List.copyOf(out);
  }

  /**
   * Lists the JSON Pointers of every schema node marked {@code "x-gameText": true}.
   *
   * @param schema the schema
   * @return the pointers, sorted
   */
  private static @NotNull Set<String> gameTextPointers(@NotNull JsonNode schema) {
    Set<String> out = new TreeSet<>();
    collect(schema, "", out);
    return out;
  }

  /**
   * Collects the game-text pointers below one node.
   *
   * @param node the node
   * @param pointer its JSON Pointer
   * @param out the collected pointers
   */
  private static void collect(
      @NotNull JsonNode node, @NotNull String pointer, @NotNull Set<String> out) {
    if (node.isObject()) {
      if (node.path("x-gameText").asBoolean(false)) {
        out.add(pointer);
      }
      for (Map.Entry<String, JsonNode> member : node.properties()) {
        String token = member.getKey().replace("~", "~0").replace("/", "~1");
        collect(member.getValue(), pointer + "/" + token, out);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        collect(node.get(i), pointer + "/" + i, out);
      }
    }
  }

  /**
   * Reads the vendored schema.
   *
   * @return the schema
   * @throws IOException if it cannot be read
   */
  private static @NotNull JsonNode schema() throws IOException {
    try (InputStream in = GameTextClassificationTest.class.getResourceAsStream(SCHEMA)) {
      assertThat(in).as(SCHEMA).isNotNull();
      return JsonMapper.builder().build().readTree(in);
    }
  }

  /**
   * Names the entity fields that carry {@code @GameText}.
   *
   * @return the names, as {@code Entity.field}
   */
  private static @NotNull Set<String> annotatedFields() {
    Set<String> out = new TreeSet<>();
    for (JavaField field : entityFields()) {
      if (field.isAnnotatedWith(GameText.class)) {
        out.add(name(field));
      }
    }
    return out;
  }

  /**
   * Names every entity field.
   *
   * @return the names, as {@code Entity.field}
   */
  private static @NotNull Set<String> entityFieldNames() {
    Set<String> out = new TreeSet<>();
    entityFields().forEach(field -> out.add(name(field)));
    return out;
  }

  /**
   * Lists the fields of every catalogue entity.
   *
   * @return the fields
   */
  private static @NotNull List<JavaField> entityFields() {
    return CATALOGUE.stream()
        .filter(type -> type.isAnnotatedWith(Entity.class))
        .map(JavaClass::getFields)
        .flatMap(Set::stream)
        .toList();
  }

  /**
   * Names a field as {@code Entity.field}.
   *
   * @param field the field
   * @return the name
   */
  private static @NotNull String name(@NotNull JavaField field) {
    return field.getOwner().getSimpleName() + "." + field.getName();
  }
}
