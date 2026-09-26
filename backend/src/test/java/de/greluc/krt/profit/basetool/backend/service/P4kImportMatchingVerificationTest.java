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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies against Postgres that a P4K import matches and merges existing master data: rows seeded
 * with canonical UUIDs are stripped of them, re-imported, and must re-match by name, class name,
 * code or key, backfill the UUID and re-resolve a blueprint ingredient without creating rows.
 *
 * <p>Runs in one rolled-back transaction; {@link JwtDecoder} is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class P4kImportMatchingVerificationTest {

  private static final String MFG_GUID = "f444bb54-a422-44a0-abdb-a0384f0f5593";
  private static final String ITEM_GUID = "0bad23a8-3cfc-4009-a338-6e232c816eb7";
  private static final String SHIP_GUID = "c03e7aef-6411-4e85-9c10-e89862884433";
  private static final String COMMODITY_GUID = "6cff1dcd-b2f7-4e4f-ba48-ffa884184b37";
  private static final String RESOURCE_GUID = "60f116f4-c02a-45b2-9ded-333747795124";
  private static final String BLUEPRINT_GUID = "8052cb17-bf11-4bd1-896a-6b6345900396";

  @Autowired private P4kImportService service;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private BlueprintRepository blueprintRepository;

  @MockitoBean private JwtDecoder jwtDecoder;

  /**
   * Compact catalog built from real DataForge identifiers; the descriptions/mass are enrichment.
   */
  private static byte[] catalog() {
    String json =
        """
        {
          "manufacturers": [
            {"guid":"%s","code":"UPS","name":"Upsiders","desc":"Footwear and lifestyle brand."}
          ],
          "items": [
            {"guid":"%s","className":"gys_undersuit_01_01_11","name":"Deep-Space Undersuit River Rock",
             "desc":"A deep-space undersuit.","mass":8.0}
          ],
          "ships": [
            {"guid":"%s","className":"DRAK_Clipper","name":"Drake Clipper","desc":"A light freighter."}
          ],
          "commodities": [
            {"guid":"%s","name":"Zeta-Prolanide","desc":"A volatile compound."},
            {"guid":"%s","name":"Protective Sheathing Resource","desc":"A crafting resource."}
          ],
          "blueprints": [
            {"guid":"%s","key":"BP_CRAFT_outlaw_legacy_armor_medium_core_01_01_09","craftTimeSeconds":190,
             "ingredients":[{"resourceGuid":"%s","quantityScu":0.05}]}
          ]
        }
        """
            .formatted(
                MFG_GUID,
                ITEM_GUID,
                SHIP_GUID,
                COMMODITY_GUID,
                RESOURCE_GUID,
                BLUEPRINT_GUID,
                RESOURCE_GUID);
    return json.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void reimport_matchesEveryType_backfillsUuidsAndResolvesIngredient_withoutCreatingRows() {
    byte[] bytes = catalog();

    P4kImportResultDto seeded = service.applyImport(bytes, true);
    assertEquals(1, seeded.manufacturers().created(), "manufacturer seeded");
    assertEquals(1, seeded.items().created(), "item seeded");
    assertEquals(1, seeded.ships().created(), "ship seeded");
    assertEquals(2, seeded.commodities().created(), "both commodities seeded");
    assertEquals(1, seeded.blueprints().created(), "blueprint seeded");

    Manufacturer mfg =
        manufacturerRepository
            .findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc("UPS")
            .orElseThrow();
    mfg.setScwikiUuid(null);
    manufacturerRepository.saveAndFlush(mfg);

    GameItem item = gameItemRepository.findByClassNameIgnoreCase("gys_undersuit_01_01_11").get(0);
    item.setExternalUuid(null);
    gameItemRepository.saveAndFlush(item);

    ShipType ship = shipTypeRepository.findByClassNameIgnoreCase("DRAK_Clipper").get(0);
    ship.setExternalUuid(null);
    shipTypeRepository.saveAndFlush(ship);

    Material zeta = materialRepository.findByNameIgnoreCase("Zeta-Prolanide").orElseThrow();
    zeta.setScwikiUuid(null);
    materialRepository.saveAndFlush(zeta);

    Blueprint blueprint =
        blueprintRepository.findByScwikiUuid(UUID.fromString(BLUEPRINT_GUID)).orElseThrow();
    blueprint.getIngredients().get(0).setMaterial(null);
    blueprintRepository.saveAndFlush(blueprint);

    P4kImportResultDto merged = service.applyImport(bytes, false);

    assertEquals(1, merged.manufacturers().matched(), "manufacturer re-matched by code");
    assertEquals(0, merged.manufacturers().created(), "no manufacturer re-created");
    assertTrue(merged.manufacturers().uuidBackfilled() >= 1, "manufacturer scwiki_uuid backfilled");

    assertEquals(1, merged.items().matched(), "item re-matched by class_name");
    assertEquals(0, merged.items().created(), "no item re-created");
    assertTrue(merged.items().uuidBackfilled() >= 1, "item external_uuid backfilled");

    assertEquals(1, merged.ships().matched(), "ship re-matched by class_name");
    assertTrue(merged.ships().uuidBackfilled() >= 1, "ship external_uuid backfilled");

    assertEquals(2, merged.commodities().matched(), "both commodities re-matched (name + uuid)");
    assertTrue(merged.commodities().uuidBackfilled() >= 1, "commodity scwiki_uuid backfilled");

    assertEquals(1, merged.blueprints().matched(), "blueprint re-matched by uuid");
    assertEquals(0, merged.blueprints().created(), "no blueprint re-created");
    assertTrue(merged.ingredientsResolved() >= 1, "the unresolved ingredient was re-linked");

    assertEquals(
        UUID.fromString(MFG_GUID),
        manufacturerRepository
            .findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc("UPS")
            .orElseThrow()
            .getScwikiUuid(),
        "manufacturer scwiki_uuid restored from the P4K guid");
    assertEquals(
        UUID.fromString(ITEM_GUID),
        gameItemRepository
            .findByClassNameIgnoreCase("gys_undersuit_01_01_11")
            .get(0)
            .getExternalUuid(),
        "item external_uuid restored from the P4K guid");
  }
}
