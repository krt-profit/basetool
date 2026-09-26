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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies against PostgreSQL that material-only inventory reads exclude game-item rows and that a
 * {@code material.name} sort does not drop rows (REQ-INV-029). Each test rolls back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryItemCatalogQueryDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private JobOrderRepository jobOrderRepository;

  @PersistenceContext private EntityManager entityManager;

  private User user;
  private Material material;
  private GameItem gameItem;
  private Location location;
  private OrgUnit orgUnit;
  private InventoryItem materialRow;
  private InventoryItem itemRow;

  /**
   * Seeds one owner with one material row (quality 800, 100 SCU) and one game-item row (3 units) at
   * the same location — the mixed-catalog population every seam under test must split.
   */
  @BeforeEach
  void seedMixedCatalogRows() {
    user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    orgUnit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();

    location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    material = new Material();
    material.setName("Quantanium-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    gameItem = new GameItem();
    gameItem.setName("Quantum-Drive-" + UUID.randomUUID());
    gameItemRepository.save(gameItem);

    materialRow = new InventoryItem();
    materialRow.setUser(user);
    materialRow.setLocation(location);
    materialRow.setMaterial(material);
    materialRow.setQuality(800);
    materialRow.setAmount(100.0);
    materialRow.setPersonal(false);
    materialRow.setOwningOrgUnit(orgUnit);
    inventoryItemRepository.save(materialRow);

    itemRow = new InventoryItem();
    itemRow.setUser(user);
    itemRow.setLocation(location);
    itemRow.setGameItem(gameItem);
    itemRow.setAmount(3.0);
    itemRow.setPersonal(false);
    itemRow.setOwningOrgUnit(orgUnit);
    inventoryItemRepository.save(itemRow);
    entityManager.flush();
  }

  /**
   * Persists an ITEM-typed job order and earmarks both fixture rows to it in full — the mixed
   * allocation population the order-side seams must split by catalog.
   *
   * @return the order id.
   */
  private UUID seedOrderWithMixedEarmarks() {
    JobOrder order =
        jobOrderRepository.saveAndFlush(
            JobOrder.builder()
                .responsibleOrgUnit(orgUnit)
                .requestingOrgUnit(orgUnit)
                .handle("item-catalog-" + UUID.randomUUID())
                .status(JobOrderStatus.OPEN)
                .build());
    InventoryAllocations.addJobOrder(materialRow, order, 100.0, false);
    InventoryAllocations.addJobOrder(itemRow, order, 3.0, false);
    inventoryItemRepository.save(materialRow);
    inventoryItemRepository.save(itemRow);
    entityManager.flush();
    return order.getId();
  }

  @Test
  void flatUserLists_splitByCatalog_underTheirDefaultSorts() {
    Page<InventoryItem> materialRows =
        inventoryItemRepository.findMaterialRowsByUser(
            user, PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "material.name")));

    assertThat(materialRows.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(materialRow.getId());

    Page<InventoryItem> itemRows =
        inventoryItemRepository.findItemRowsByUser(
            user, PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "gameItem.name")));
    assertThat(itemRows.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(itemRow.getId());
  }

  @Test
  void findGlobalByFilters_excludesItemRows_underTheDefaultMaterialNameSort() {
    Page<InventoryItem> page =
        inventoryItemRepository.findGlobalByFilters(
            false,
            null,
            false,
            null,
            null,
            false,
            null,
            false,
            null,
            true,
            null,
            Set.of(),
            PageRequest.of(0, 200, Sort.by(Sort.Direction.ASC, "material.name")));

    assertThat(page.getContent())
        .extracting(InventoryItem::getId)
        .contains(materialRow.getId())
        .doesNotContain(itemRow.getId());
    assertThat(page.getContent()).allSatisfy(row -> assertThat(row.getMaterial()).isNotNull());
  }

  @Test
  void findReleasableForUser_returnsBothMaterialAndItemRows() {
    List<InventoryItem> releasable =
        inventoryItemRepository.findReleasableForUser(
            user.getId(), null, true, true, PageRequest.of(0, 50));

    assertThat(releasable)
        .extracting(InventoryItem::getId)
        .containsExactlyInAnyOrder(materialRow.getId(), itemRow.getId());
  }

  @Test
  void findReleasableForUser_filtersByGameItemName() {
    List<InventoryItem> releasable =
        inventoryItemRepository.findReleasableForUser(
            user.getId(), "%quantum-drive%", true, true, PageRequest.of(0, 50));

    assertThat(releasable).extracting(InventoryItem::getId).containsExactly(itemRow.getId());
  }

  @Test
  void findReleasableForUser_filtersByFullGameItemName() {
    GameItem widget = new GameItem();
    widget.setName("E2E Boerse Item Stock Widget");
    gameItemRepository.save(widget);
    InventoryItem widgetRow = new InventoryItem();
    widgetRow.setUser(user);
    widgetRow.setLocation(location);
    widgetRow.setGameItem(widget);
    widgetRow.setAmount(20.0);
    widgetRow.setPersonal(false);
    widgetRow.setOwningOrgUnit(orgUnit);
    inventoryItemRepository.save(widgetRow);
    entityManager.flush();

    List<InventoryItem> releasable =
        inventoryItemRepository.findReleasableForUser(
            user.getId(), "%e2e boerse item stock widget%", true, true, PageRequest.of(0, 50));

    assertThat(releasable).extracting(InventoryItem::getId).contains(widgetRow.getId());
  }

  @Test
  void findReleasableForUser_filtersByMaterialName() {
    List<InventoryItem> releasable =
        inventoryItemRepository.findReleasableForUser(
            user.getId(), "%quantanium%", true, true, PageRequest.of(0, 50));

    assertThat(releasable).extracting(InventoryItem::getId).containsExactly(materialRow.getId());
  }

  @Test
  void findReleasableForUser_kindFlagsRestrictRowsByCatalog() {
    List<InventoryItem> materialsOnly =
        inventoryItemRepository.findReleasableForUser(
            user.getId(), null, true, false, PageRequest.of(0, 50));
    List<InventoryItem> itemsOnly =
        inventoryItemRepository.findReleasableForUser(
            user.getId(), null, false, true, PageRequest.of(0, 50));

    assertThat(materialsOnly).extracting(InventoryItem::getId).containsExactly(materialRow.getId());
    assertThat(itemsOnly).extracting(InventoryItem::getId).containsExactly(itemRow.getId());
  }

  @Test
  void jobOrderRowSeams_splitEarmarksByCatalog() {
    UUID orderId = seedOrderWithMixedEarmarks();

    assertThat(inventoryItemRepository.findByJobOrderIdOrdered(orderId))
        .extracting(InventoryItem::getId)
        .containsExactly(materialRow.getId());

    assertThat(inventoryItemRepository.findGameItemRowsByJobOrderIdOrdered(orderId))
        .extracting(InventoryItem::getId)
        .containsExactly(itemRow.getId());
  }

  @Test
  void itemStackEntryQueries_serveTheGameItemStack() {
    Page<InventoryItem> myEntries =
        inventoryItemRepository.findUserItemStackEntries(
            user.getId(),
            gameItem.getId(),
            location.getId(),
            false,
            orgUnit.getId(),
            PageRequest.of(0, 20));
    Page<InventoryItem> globalEntries =
        inventoryItemRepository.findGlobalItemStackEntries(
            gameItem.getId(),
            user.getId(),
            location.getId(),
            orgUnit.getId(),
            true,
            null,
            Set.of(),
            PageRequest.of(0, 20));

    assertThat(myEntries.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(itemRow.getId());
    assertThat(globalEntries.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(itemRow.getId());
  }

  @Test
  void findMaterialStockRowsByJobOrderIds_excludesItemEarmarks() {
    UUID orderId = seedOrderWithMixedEarmarks();

    List<JobOrderMaterialStockRow> rows =
        inventoryItemRepository.findMaterialStockRowsByJobOrderIds(List.of(orderId));

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).materialId()).isEqualTo(material.getId());
    assertThat(rows).allSatisfy(row -> assertThat(row.materialId()).isNotNull());
  }
}
