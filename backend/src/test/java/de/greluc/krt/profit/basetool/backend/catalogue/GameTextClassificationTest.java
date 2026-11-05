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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Every catalogue column holding a game text carries {@link GameText}, and no column carries both
 * classes (REQ-P4K-033).
 */
class GameTextClassificationTest {

  /** The catalogue's entities, main sources only. */
  private static final JavaClasses CATALOGUE =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend.catalogue");

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
   * The entity fields filled from a catalogue field the p4kx contract marks {@code "x-gameText":
   * true}, keyed by that contract field.
   */
  private static final Map<String, List<String>> CONTRACT_GAME_TEXTS =
      Map.of(
          "manufacturers[].description", List.of("Manufacturer.description"),
          "items[].description", List.of("GameItem.descriptionEn", "GameItem.descriptionDe"),
          "ships[].description", List.of("ShipType.descriptionEn", "ShipType.descriptionDe"),
          "commodities[].description", List.of("Material.description"));

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
  void everyFieldFilledFromAContractGameTextCarriesGameText() {
    Set<String> annotated = new TreeSet<>();
    for (JavaField field : entityFields()) {
      if (field.isAnnotatedWith(GameText.class)) {
        annotated.add(name(field));
      }
    }
    CONTRACT_GAME_TEXTS.forEach((contractField, entityFields) ->
        assertThat(annotated).as("columns filled from " + contractField).containsAll(entityFields));
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
