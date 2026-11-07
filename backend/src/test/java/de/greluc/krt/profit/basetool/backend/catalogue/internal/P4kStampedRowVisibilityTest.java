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

import de.greluc.krt.profit.basetool.backend.blueprint.internal.BlueprintProductDto;
import de.greluc.krt.profit.basetool.backend.blueprint.internal.BlueprintProductService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * A row the P4K stamped or an admin hid leaves every picker, while an existing reference keeps
 * rendering with the "no longer in the game" notice (REQ-P4K-013).
 */
@SpringBootTest
@Transactional
class P4kStampedRowVisibilityTest {

  /** The stamp the tests give a row. */
  private static final Instant STAMPED = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private P4kImportService importService;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private P4kImportChangeRepository changeRepository;
  @Autowired private InventoryItemMapper inventoryItemMapper;
  @Autowired private ShipTypeMapper shipTypeMapper;
  @Autowired private BlueprintProductService productService;

  private GameItem craftable(String name) {
    GameItem item = new GameItem();
    item.setName(name);
    gameItemRepository.save(item);
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setOutputName(name);
    blueprint.setOutputItem(item);
    blueprintRepository.save(blueprint);
    return item;
  }

  @Test
  void aStampedItem_leavesTheItemPickers_andAReferenceToItCarriesTheNotice() {
    GameItem live = craftable("Visibility live rifle");
    GameItem stamped = craftable("Visibility stamped rifle");
    stamped.setP4kUuid(UUID.randomUUID());
    stamped.setP4kDeletedAt(STAMPED);
    gameItemRepository.flush();

    List<UUID> offered =
        blueprintRepository
            .findItemsWithActiveBlueprint("visibility", PageRequest.of(0, 50))
            .map(GameItem::getId)
            .getContent();

    assertThat(offered).contains(live.getId()).doesNotContain(stamped.getId());
    assertThat(inventoryItemMapper.gameItemToReferenceDto(stamped).noLongerInGame()).isTrue();
    assertThat(inventoryItemMapper.gameItemToReferenceDto(live).noLongerInGame()).isFalse();
    assertThat(GameItemReferenceDto.of(stamped).noLongerInGame()).isTrue();
    assertThat(GameItemReferenceDto.of(live).noLongerInGame()).isFalse();
  }

  @Test
  void aStampedShipUexStillLists_isHidden_andReportedStillLive() {
    List<UUID> listed = P4kSweepFixtures.guids(19);
    P4kSweepFixtures fixtures = new P4kSweepFixtures(gameItemRepository, shipTypeRepository);
    fixtures.knownShips(listed);
    ShipType gone = fixtures.knownShip(UUID.randomUUID());
    gone.setUexVehicleId(987_656);
    ObjectNode root = P4kTestCatalogues.tree();
    P4kSweepFixtures.listOnly(root, "ships", P4kTestCatalogues.PLAYER_SHIP, listed);

    P4kImportResultDto result =
        new P4kImportRuns(importService, jobRepository).apply(P4kTestCatalogues.bytes(root), false);
    shipTypeRepository.flush();

    assertThat(
            shipTypeRepository
                .searchPickableByName("Sweep ", PageRequest.of(0, 100))
                .map(ShipType::getId)
                .getContent())
        .hasSize(19);
    assertThat(shipTypeRepository.searchPickableByName("Known ship", PageRequest.of(0, 100)))
        .extracting(ShipType::getId)
        .doesNotContain(gone.getId())
        .isEmpty();
    assertThat(shipTypeMapper.toDto(gone).noLongerInGame()).isTrue();
    assertThat(changeRepository.findByRunId(result.runId()))
        .filteredOn(c -> c.getChangeType() == P4kChangeType.VANISHED)
        .singleElement()
        .satisfies(c -> {
          assertThat(c.getGuid()).isEqualTo(gone.getP4kUuid());
          assertThat(c.isStillLiveElsewhere()).isTrue();
        });
  }

  @Test
  void aStampedOrHiddenRecipe_offersNoNewReference_butItsOwnerStillReadsIt() {
    Blueprint live = recipe("Visibility live helmet");
    Blueprint vanished = recipe("Visibility vanished helmet");
    vanished.setP4kDeletedAt(STAMPED);
    Blueprint hidden = recipe("Visibility hidden helmet");
    hidden.setP4kHiddenAt(STAMPED);
    blueprintRepository.flush();
    String vanishedKey = vanished.getProducedItemGuid().toString();
    String hiddenKey = hidden.getProducedItemGuid().toString();

    assertThat(productService.searchProducts("visibility", 50, UUID.randomUUID()))
        .extracting(BlueprintProductDto::productKey)
        .contains(live.getProducedItemGuid().toString())
        .doesNotContain(vanishedKey, hiddenKey);
    assertThat(productService.resolveByProductKey(vanishedKey)).isEmpty();
    assertThat(productService.resolveByProductKey(hiddenKey)).isEmpty();
    assertThat(
            blueprintRepository.findExchangeKeyRows(
                List.of(
                    live.getProducedItemGuid(),
                    vanished.getProducedItemGuid(),
                    hidden.getProducedItemGuid()),
                List.of(-1),
                List.of("-")))
        .extracting(BlueprintRepository.ExchangeBlueprintKeyRow::producedItemGuid)
        .containsExactly(live.getProducedItemGuid());
    assertThat(productService.resolveRecipe(hiddenKey)).isPresent();
    assertThat(productService.resolveRepresentativeBlueprints(Set.of(vanishedKey)))
        .containsKey(vanishedKey);
  }

  /**
   * Saves a recipe keyed by a produced-item GUID of its own.
   *
   * @param name the output name
   * @return the recipe
   */
  private Blueprint recipe(String name) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setProducedItemGuid(UUID.randomUUID());
    blueprint.setOutputName(name);
    return blueprintRepository.save(blueprint);
  }

  @Test
  void aHiddenMaterial_leavesTheMaterialPicker() {
    Material hidden = new Material();
    hidden.setName("Visibility hidden ore");
    hidden.setType(MaterialType.NO_REFINE);
    hidden.setP4kHiddenAt(STAMPED);
    materialRepository.saveAndFlush(hidden);

    assertThat(
            materialRepository
                .searchPicker(
                    "Visibility hidden", false, MaterialType.RAW, false, PageRequest.of(0, 10))
                .getContent())
        .isEmpty();
    assertThat(materialRepository.findAllReference()).noneMatch(m -> m.id().equals(hidden.getId()));
  }
}
