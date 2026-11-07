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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * The P4K import writes the German name of items, ships and commodities, never a
 * missing-translation marker, the DTOs carry it, and search keeps running on the name
 * (REQ-P4K-023).
 */
@SpringBootTest
@Transactional
class P4kNamesTest {

  /** A {@code player} item whose German name differs from its English one. */
  private static final UUID ARMS = UUID.fromString("bef9cd69-24f0-434a-ba7a-2bf751228093");

  /** A {@code player} item whose German name is the game's missing-translation marker. */
  private static final UUID CUSTODIAN = UUID.fromString("00335844-8889-4c40-b4c5-e4d8496838cf");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ShipTypeMapper shipTypeMapper;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private MaterialMapper materialMapper;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  @Test
  void aCreatedItem_carriesItsGermanName_andAMissingTranslationIsNoName() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), true);

    GameItem arms = gameItemRepository.findByP4kUuid(ARMS).orElseThrow();
    assertThat(arms.getNameDe()).isEqualTo("ADP-MK-IV-Arme (Waldland)");
    assertThat(GameItemReferenceDto.of(arms).nameDe()).isEqualTo("ADP-MK-IV-Arme (Waldland)");
    assertThat(gameItemRepository.findByP4kUuid(CUSTODIAN))
        .hasValueSatisfying(item -> assertThat(item.getNameDe()).isNull());
  }

  @Test
  void aMatchedShipAndCommodity_takeTheGermanName() {
    Material borase = new Material();
    borase.setName("Borase Names Test");
    borase.setType(MaterialType.RAW);
    materialRepository.saveAndFlush(borase);
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode commodity =
        P4kTestCatalogues.record(
            root, "commodities", UUID.fromString("001c7bc6-2bb6-4f66-a8ef-a1c63e80ffc5"));
    ((ObjectNode) commodity.get("name")).put("en", "Borase Names Test");
    ((ObjectNode) commodity.get("name")).put("de", "Borase DE");
    ObjectNode ship = P4kTestCatalogues.record(root, "ships", P4kTestCatalogues.PLAYER_SHIP);
    ((ObjectNode) ship.get("name")).put("de", "Aegis Avenger Titan DE");

    runs.apply(P4kTestCatalogues.bytes(root), true);

    assertThat(borase.getNameDe()).isEqualTo("Borase DE");
    assertThat(materialMapper.toDto(borase).nameDe()).isEqualTo("Borase DE");
    assertThat(shipTypeRepository.findByP4kUuid(P4kTestCatalogues.PLAYER_SHIP))
        .hasValueSatisfying(row ->
            assertThat(shipTypeMapper.toDto(row).nameDe()).isEqualTo("Aegis Avenger Titan DE"));
  }

  @Test
  void aSearchForTheGermanNameAlone_findsNothing() {
    Material borase = new Material();
    borase.setName("Borase Search Test");
    borase.setNameDe("Nur Deutsch Suchtest");
    borase.setType(MaterialType.RAW);
    materialRepository.saveAndFlush(borase);

    assertThat(
            materialRepository
                .searchPicker(
                    "Nur Deutsch Suchtest", false, MaterialType.RAW, false, PageRequest.of(0, 10))
                .getContent())
        .isEmpty();
    assertThat(
            materialRepository
                .searchPicker(
                    "Borase Search Test", false, MaterialType.RAW, false, PageRequest.of(0, 10))
                .getContent())
        .isNotEmpty();
  }
}
