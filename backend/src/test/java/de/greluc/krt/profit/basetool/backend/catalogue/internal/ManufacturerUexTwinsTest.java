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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerUexTwins.Evidence;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerUexTwins.Named;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerUexTwins.Row;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerUexTwins.Twin;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerUexTwins.WikiName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The UEX twin rule (REQ-DATA-045), on the twelve twins and their near misses the production rows
 * held.
 */
class ManufacturerUexTwinsTest {

  /** The P4K rows of the production twins: name, then the Wiki names of their identities. */
  private static final List<List<String>> P4K_ROWS =
      List.of(
          List.of("Behring", "Behring Applied Technology"),
          List.of("CBA", "Code Blue Apparel"),
          List.of("GNP", "Groupe Nouveau Paradigme"),
          List.of(
              "Musashi Industrial & Starflight Concern",
              "Musashi Industrial and Starflight Concern",
              "Musashi Industrial & Starflight Concern"),
          List.of("MYM", "MuyMuy"),
          List.of("Talon", "Talon Weapons Systems"),
          List.of("Virgil", "Virgil Ltd"),
          List.of("Wen/Cassel Propulsion", "Wen-Cassel Propulsion"),
          List.of("Vanduul", "Vanduul Clans"),
          List.of("UEE Navy", "United Empire of Earth Navy"),
          List.of("Grey's Market", "Grey's Market"),
          List.of("CBD", "Custom Built Duds"),
          List.of("Mirai", "Mirai"),
          List.of("Crusader Industries", "Crusader Industries"),
          List.of("Garrity Defense", "Garrity Defense"),
          List.of("Stegman's Clothing and Uniforms", "Stegman's Clothing and Uniforms"),
          List.of("United Empire of Earth", "United Empire of Earth"),
          List.of("microTech", "microTech"),
          List.of("Grindstone Boots", "Grindstone Boots"));

  /** The UEX rows of the production twins, in UEX company order. */
  private static final List<ProductionTwin> TWINS =
      List.of(
          new ProductionTwin("Behring Applied Technology", 29, "Behring", Evidence.WIKI_NAME),
          new ProductionTwin("Code Blue Apparel", 55, "CBA", Evidence.WIKI_NAME),
          new ProductionTwin("Groupe Nouveau Paradigme", 125, "GNP", Evidence.WIKI_NAME),
          new ProductionTwin(
              "Musashi Industrial and Starflight Concern",
              177,
              "Musashi Industrial & Starflight Concern",
              Evidence.NAME),
          new ProductionTwin("MuyMuy", 178, "MYM", Evidence.WIKI_NAME),
          new ProductionTwin("Talon Weapons Systems", 236, "Talon", Evidence.WIKI_NAME),
          new ProductionTwin("Virgil Ltd", 266, "Virgil", Evidence.WIKI_NAME),
          new ProductionTwin("Wen-Cassel Propulsion", 270, "Wen/Cassel Propulsion", Evidence.NAME),
          new ProductionTwin("Vanduul Clans", 280, "Vanduul", Evidence.WIKI_NAME),
          new ProductionTwin("United Empire of Earth Navy", 289, "UEE Navy", Evidence.WIKI_NAME),
          new ProductionTwin("Grey&apos;s Market", 318, "Grey's Market", Evidence.NAME),
          new ProductionTwin("Custom Built Duds", 324, "CBD", Evidence.WIKI_NAME));

  /** UEX rows the production held whose names start like a P4K row's but name another brand. */
  private static final Map<String, Integer> NEAR_MISSES =
      Map.of(
          "Crusader Security", 64,
          "Garrity Arms", 111,
          "Grindstone", 124,
          "Hurston Security", 138,
          "microTech Metroloop", 175,
          "Stegman's", 228,
          "United Resource Workers", 307,
          "United Planets Of Earth", 316);

  private final Map<String, UUID> ids = new HashMap<>();
  private final List<Row> rows = new ArrayList<>();
  private final List<WikiName> wikiNames = new ArrayList<>();

  private UUID id(String name) {
    return ids.computeIfAbsent(name, _ -> UUID.randomUUID());
  }

  private UUID uexId(String name) {
    return id("UEX " + name);
  }

  private void p4kRow(String name, String... wikiNamesOfRow) {
    rows.add(new Row(id(name), name, UUID.randomUUID(), UUID.randomUUID(), null, false));
    for (String wikiName : wikiNamesOfRow) {
      wikiNames.add(new WikiName(id(name), wikiName));
    }
  }

  private void uexRow(String name, int company) {
    rows.add(new Row(uexId(name), name, null, null, company, false));
  }

  private void productionRows() {
    for (List<String> row : P4K_ROWS) {
      p4kRow(row.getFirst(), row.subList(1, row.size()).toArray(String[]::new));
    }
    TWINS.forEach(twin -> uexRow(twin.name(), twin.company()));
    NEAR_MISSES.forEach(this::uexRow);
  }

  @Test
  void theTwelveProductionTwinsFindTheirP4kRowAndNoNearMissDoes() {
    productionRows();

    List<Twin> twins = ManufacturerUexTwins.of(rows, wikiNames).twins();

    assertThat(twins)
        .containsExactlyElementsOf(
            TWINS.stream()
                .map(twin ->
                    new Twin(
                        uexId(twin.name()), id(twin.survivor()), twin.company(), twin.evidence()))
                .toList());
  }

  @Test
  void aRowHoldingAnotherIdentityIsNoTwin() {
    p4kRow("Vanduul", "Vanduul Clans");
    UUID withWikiUuid = UUID.randomUUID();
    rows.add(new Row(withWikiUuid, "Vanduul Clans", null, UUID.randomUUID(), 280, false));
    UUID withP4kAlias = UUID.randomUUID();
    rows.add(new Row(withP4kAlias, "Vanduul Clans", null, null, 281, true));
    UUID withP4kGuid = UUID.randomUUID();
    rows.add(new Row(withP4kGuid, "Vanduul Clans", UUID.randomUUID(), null, 282, false));
    UUID withoutUex = UUID.randomUUID();
    rows.add(new Row(withoutUex, "Vanduul Clans", null, null, null, false));
    UUID withWikiIdentity = UUID.randomUUID();
    rows.add(new Row(withWikiIdentity, "Vanduul Clans", null, null, 283, false));
    wikiNames.add(new WikiName(withWikiIdentity, "Vanduul Clans"));

    ManufacturerUexTwins rule = ManufacturerUexTwins.of(rows, wikiNames);

    assertThat(rule.twins()).isEmpty();
    assertThat(List.of(withWikiUuid, withP4kAlias, withP4kGuid, withoutUex, withWikiIdentity))
        .allSatisfy(row -> assertThat(rule.twinOf(row)).isEmpty());
  }

  @Test
  void aNameTwoP4kRowsCarryNamesNone() {
    p4kRow("Vanduul", "Vanduul Clans");
    p4kRow("Vanduul Clans");
    p4kRow("Talon", "Talon Weapons Systems");
    p4kRow("Talon Weapon Systems Ltd", "Talon Weapons Systems");
    uexRow("Vanduul Clans", 280);
    uexRow("Talon Weapons Systems", 236);

    ManufacturerUexTwins rule = ManufacturerUexTwins.of(rows, wikiNames);

    assertThat(rule.twins()).isEmpty();
    assertThat(rule.named("Vanduul Clans")).isEmpty();
    assertThat(rule.named("Talon Weapons Systems")).isEmpty();
  }

  @Test
  void theWikiNameOfARowWithoutP4kGuidNamesNothing() {
    UUID wikiOnly = UUID.randomUUID();
    rows.add(new Row(wikiOnly, "Vanduul", null, UUID.randomUUID(), null, false));
    wikiNames.add(new WikiName(wikiOnly, "Vanduul Clans"));
    uexRow("Vanduul Clans", 280);

    ManufacturerUexTwins rule = ManufacturerUexTwins.of(rows, wikiNames);

    assertThat(rule.twins()).isEmpty();
    assertThat(rule.named("Vanduul Clans")).isEmpty();
  }

  @Test
  void aCompanyNameFindsTheP4kRowItNames() {
    productionRows();
    ManufacturerUexTwins rule = ManufacturerUexTwins.of(rows, wikiNames);

    assertThat(rule.named("VANDUUL  CLANS")).contains(new Named(id("Vanduul"), Evidence.WIKI_NAME));
    assertThat(rule.named("Grey&apos;s Market"))
        .contains(new Named(id("Grey's Market"), Evidence.NAME));
    assertThat(rule.named("Vanduul Clan")).isEmpty();
    assertThat(rule.named(" - / ")).isEmpty();
  }

  @Test
  void twinOfFindsOnlyATwin() {
    productionRows();
    ManufacturerUexTwins rule = ManufacturerUexTwins.of(rows, wikiNames);

    assertThat(rule.twinOf(uexId("MuyMuy")))
        .contains(new Twin(uexId("MuyMuy"), id("MYM"), 178, Evidence.WIKI_NAME));
    assertThat(rule.twinOf(uexId("Garrity Arms"))).isEmpty();
    assertThat(rule.twinOf(id("MYM"))).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Grey&apos;s Market | Grey's Market",
        "Musashi Industrial & Starflight Concern | Musashi Industrial and Starflight Concern",
        "Musashi Industrial &amp; Starflight Concern | musashi industrial AND starflight concern",
        "Wen-Cassel Propulsion | Wen/Cassel  Propulsion",
        "Grey&#39;s Market | Grey's Market",
        "'  Virgil Ltd. ' | virgil ltd"
      })
  void foldReadsSpellingsOfOneNameAlike(String left, String right) {
    assertThat(ManufacturerUexTwins.fold(left)).isEqualTo(ManufacturerUexTwins.fold(right));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Vanduul Clans | Vanduul",
        "Talon Weapons Systems | Talon Weapon Systems",
        "United Planets Of Earth | United Empire of Earth",
        "Stegman's | Stegman's Clothing and Uniforms"
      })
  void foldKeepsDifferentNamesApart(String left, String right) {
    assertThat(ManufacturerUexTwins.fold(left)).isNotEqualTo(ManufacturerUexTwins.fold(right));
  }

  /**
   * A UEX row the production held beside the P4K row of its brand.
   *
   * @param name the UEX row's name
   * @param company its UEX company
   * @param survivor the name of the P4K row it duplicates
   * @param evidence what names the P4K row
   */
  private record ProductionTwin(String name, int company, String survivor, Evidence evidence) {}
}
