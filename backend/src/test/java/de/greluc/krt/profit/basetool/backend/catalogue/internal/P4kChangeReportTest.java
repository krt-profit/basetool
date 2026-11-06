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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every run keeps a change report of identifiers, short names and fact values, never a game text
 * (REQ-P4K-014).
 */
@SpringBootTest
@Transactional
class P4kChangeReportTest {

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private P4kImportChangeRepository changeRepository;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  @Test
  void aRenamedItem_anAddedShip_andAVanishedBlueprint_eachWriteOneRowWithTheirValues() {
    GameItem shield = new GameItem();
    shield.setName("Report shield");
    shield.setP4kUuid(P4kTestCatalogues.SHIELD);
    shield.setNameKey("item_NameSHLD_Old_Name");
    gameItemRepository.save(shield);
    Blueprint gone = new Blueprint();
    gone.setScwikiUuid(UUID.randomUUID());
    gone.setScwikiKey("BP_REPORT_GONE");
    gone.setOutputName("Report gone blueprint");
    gone.setP4kUuid(UUID.randomUUID());
    blueprintRepository.save(gone);

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.fixtureBytes(), true, true);
    List<P4kImportChange> report = changeRepository.findByRunId(result.runId());

    assertThat(report)
        .filteredOn(c -> c.getChangeType() == P4kChangeType.CHANGED)
        .filteredOn(c -> P4kTestCatalogues.SHIELD.equals(c.getGuid()))
        .filteredOn(c -> "name_key".equals(c.getFieldName()))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.getOldValue()).isEqualTo("item_NameSHLD_Old_Name");
          assertThat(c.getNewValue()).isEqualTo("item_NameSHLD_GODI_S01_AllStop");
        });
    assertThat(report)
        .filteredOn(c -> c.getChangeType() == P4kChangeType.ADDED)
        .filteredOn(c -> c.getKind() == P4kKind.SHIP)
        .singleElement()
        .satisfies(c -> assertThat(c.getGuid()).isEqualTo(P4kTestCatalogues.PLAYER_SHIP));
    assertThat(report)
        .filteredOn(c -> c.getChangeType() == P4kChangeType.VANISHED)
        .filteredOn(c -> c.getKind() == P4kKind.BLUEPRINT)
        .singleElement()
        .satisfies(c -> {
          assertThat(c.getGuid()).isEqualTo(gone.getP4kUuid());
          assertThat(c.getClassName()).isEqualTo("BP_REPORT_GONE");
          assertThat(c.getNameEn()).isEqualTo("Report gone blueprint");
        });
  }

  @Test
  void noReportRowHoldsADescriptionOrADisplayType() {
    GameItem shield = new GameItem();
    shield.setName("Text shield");
    shield.setP4kUuid(P4kTestCatalogues.SHIELD);
    gameItemRepository.save(shield);
    ObjectNode root = P4kTestCatalogues.tree();
    List<String> gameTexts = gameTexts(root);

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), true);
    List<P4kImportChange> report = changeRepository.findByRunId(result.runId());

    assertThat(gameTexts).isNotEmpty();
    assertThat(report)
        .filteredOn(c -> P4kTestCatalogues.SHIELD.equals(c.getGuid()))
        .extracting(P4kImportChange::getFieldName)
        .contains("description_en", "description_de");
    assertThat(report)
        .flatExtracting(c -> Stream.of(c.getOldValue(), c.getNewValue()).toList())
        .filteredOn(Objects::nonNull)
        .noneMatch(gameTexts::contains);
  }

  /**
   * Collects every description and display-type text of the catalogue's records.
   *
   * @param root the catalogue tree
   * @return the texts in English and German
   */
  private static List<String> gameTexts(ObjectNode root) {
    return Stream.of("manufacturers", "items", "ships", "commodities", "blueprints")
        .flatMap(kind -> root.get(kind).valueStream())
        .flatMap(record -> Stream.of(record.get("description"), record.get("displayType")))
        .filter(text -> text != null && text.isObject())
        .flatMap(text -> Stream.of(text.get("en"), text.get("de")))
        .filter(value -> value != null && value.isString())
        .map(JsonNode::asString)
        .filter(value -> !value.isBlank())
        .toList();
  }
}
