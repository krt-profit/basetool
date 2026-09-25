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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemKind;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind;
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
 * Verifies against Postgres that {@link P4kImportService} seeds one row of every catalog type
 * without constraint violations, round-trips the P4K provenance columns and resolves references to
 * rows seeded in the same run.
 *
 * <p>Transactional, so seeded rows roll back; {@link JwtDecoder} is mocked. The mocked-repository
 * counterpart is {@link de.greluc.krt.profit.basetool.backend.service.P4kImportServiceTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class P4kImportServiceIntegrationTest {

  @Autowired private P4kImportService service;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private BlueprintRepository blueprintRepository;

  @MockitoBean private JwtDecoder jwtDecoder;

  /**
   * Encodes a catalog JSON string as the {@code byte[]} the import service consumes.
   *
   * @param json the catalog JSON
   * @return the UTF-8 bytes
   */
  private static byte[] upload(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void apply_withSeeding_persistsOneNewRowOfEveryTypeAgainstTheRealSchema() {
    UUID mfgGuid = UUID.randomUUID();
    UUID itemGuid = UUID.randomUUID();
    UUID shipGuid = UUID.randomUUID();
    UUID commodityGuid = UUID.randomUUID();
    UUID bpGuid = UUID.randomUUID();

    String json =
        """
        {
          "manufacturers": [
            {"guid":"%s","code":"ZPHR","name":"Zephyr Dynamics","desc":"Builds fast hulls."}
          ],
          "items": [
            {"guid":"%s","className":"wpn_zephyr_blade","name":"Zephyr Blade","mass":3.25,
             "desc":"A compact blade.","descDe":"Eine kompakte Klinge.","manufacturerGuid":"%s"}
          ],
          "ships": [
            {"guid":"%s","className":"ship_zephyr_courier","name":"Zephyr Courier",
             "desc":"A light courier.","manufacturerGuid":"%s"}
          ],
          "commodities": [
            {"guid":"%s","className":"zephyrium","name":"Zephyrium","desc":"Volatile ore."}
          ],
          "blueprints": [
            {"guid":"%s","key":"BP_CRAFT_ZEPHYR_BLADE","producedItemGuid":"%s","craftTimeSeconds":90,
             "ingredients":[
               {"resourceGuid":"%s","quantityScu":5.0},
               {"itemGuid":"%s","quantityUnits":2}
             ]}
          ]
        }
        """
            .formatted(
                mfgGuid,
                itemGuid,
                mfgGuid,
                shipGuid,
                mfgGuid,
                commodityGuid,
                bpGuid,
                itemGuid,
                commodityGuid,
                itemGuid);

    P4kImportResultDto result = service.applyImport(upload(json), true);

    assertFalse(result.dryRun(), "apply is not a dry run");
    assertNotNull(result.runId(), "an applied run is stamped with a run id");
    assertEquals(1, result.manufacturers().created(), "one manufacturer seeded");
    assertEquals(1, result.items().created(), "one item seeded");
    assertEquals(1, result.ships().created(), "one ship seeded");
    assertEquals(1, result.commodities().created(), "one commodity seeded");
    assertEquals(1, result.blueprints().created(), "one blueprint seeded");

    Manufacturer manufacturer = manufacturerRepository.findByScwikiUuid(mfgGuid).orElseThrow();
    assertEquals("Zephyr Dynamics", manufacturer.getName());
    assertEquals("ZPHR", manufacturer.getAbbreviation());
    assertEquals(mfgGuid, manufacturer.getP4kUuid());
    assertNotNull(manufacturer.getP4kSyncedAt());

    GameItem item = gameItemRepository.findByExternalUuid(itemGuid).orElseThrow();
    assertEquals("Zephyr Blade", item.getName());
    assertEquals(GameItemSourceSystem.P4K, item.getSourceSystems());
    assertEquals(GameItemKind.GENERIC, item.getKind());
    assertEquals(3.25, item.getMass());
    assertEquals("A compact blade.", item.getDescriptionEn());
    assertNotNull(item.getP4kSyncedAt());
    assertSame(
        manufacturer, item.getManufacturer(), "item links the same-pass seeded manufacturer");

    ShipType ship = shipTypeRepository.findByExternalUuid(shipGuid).orElseThrow();
    assertEquals("Zephyr Courier", ship.getName());
    assertEquals(GameItemSourceSystem.P4K, ship.getSourceSystems());
    assertEquals("ship_zephyr_courier", ship.getClassName());
    assertNotNull(ship.getP4kSyncedAt());
    assertSame(manufacturer, ship.getManufacturer());

    Material material = materialRepository.findByScwikiUuid(commodityGuid).orElseThrow();
    assertEquals("Zephyrium", material.getName());
    assertEquals(Boolean.FALSE, material.getIsVisible());
    assertEquals(MaterialType.NO_REFINE, material.getType());
    assertEquals(MaterialSourceSystem.P4K, material.getSourceSystems());
    assertNotNull(material.getP4kSyncedAt());

    Blueprint blueprint = blueprintRepository.findByScwikiUuid(bpGuid).orElseThrow();
    assertEquals("BP_CRAFT_ZEPHYR_BLADE", blueprint.getScwikiKey());
    assertEquals(90, blueprint.getCraftTimeSeconds());
    assertSame(
        item, blueprint.getOutputItem(), "output item resolved to the same-pass seeded item");
    assertNotNull(blueprint.getP4kSyncedAt());
    assertEquals(2, blueprint.getIngredients().size(), "both ingredient lines persisted");

    BlueprintIngredient resourceLine =
        blueprint.getIngredients().stream()
            .filter(i -> i.getKind() == BlueprintIngredientKind.RESOURCE)
            .findFirst()
            .orElseThrow();
    assertSame(
        material, resourceLine.getMaterial(), "RESOURCE line resolved to the seeded material");
    assertEquals(5.0, resourceLine.getQuantityScu());
    assertNull(resourceLine.getQuantityUnits());

    BlueprintIngredient itemLine =
        blueprint.getIngredients().stream()
            .filter(i -> i.getKind() == BlueprintIngredientKind.ITEM)
            .findFirst()
            .orElseThrow();
    assertSame(item, itemLine.getGameItem(), "ITEM line resolved to the seeded item");
    assertEquals(2, itemLine.getQuantityUnits());
    assertNull(itemLine.getQuantityScu());
  }
}
