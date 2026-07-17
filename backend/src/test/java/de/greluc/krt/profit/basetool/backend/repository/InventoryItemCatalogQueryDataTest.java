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
 * Data-level regressions for the §4.4 remediation list of the item-inventory design (V220,
 * REQ-INV-029, ADR-0101) against the real Postgres test schema: with {@code material_id} nullable,
 * every historically material-only read seam must exclude game-item rows explicitly, and the
 * runtime {@code material.name} sort — smuggled in via the Pageable rather than the JPQL — must not
 * decide row visibility through its implicit-join trap. Each test seeds a NULL-material (item) row
 * next to a material row and pins the seam to its own catalog population; before the remediation
 * several of these queries silently dropped rows (releasable picker, flat lists under the default
 * sort) or would have 500ed their consumer with a null material (Materialsammlung, order stock
 * index).
 *
 * <p>{@link Transactional} so each method rolls back — the seeded rows never commit to the shared
 * Testcontainers database.
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

  // covers REQ-INV-029 (flat user list splits by catalog; default material.name sort is join-safe)
  @Test
  void flatUserLists_splitByCatalog_underTheirDefaultSorts() {
    // When — the material flat list under its DEFAULT material.name sort (the runtime-appended
    // attribute path whose implicit inner join decided row visibility before the NOT-NULL guard)
    Page<InventoryItem> materialRows =
        inventoryItemRepository.findMaterialRowsByUser(
            user, PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "material.name")));

    // Then — exactly the material row: present under the sort, with the item row excluded
    assertThat(materialRows.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(materialRow.getId());

    // And the item flat list serves the game-item rows under its own gameItem.name sort
    Page<InventoryItem> itemRows =
        inventoryItemRepository.findItemRowsByUser(
            user, PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "gameItem.name")));
    assertThat(itemRows.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(itemRow.getId());
  }

  // covers REQ-INV-029 (global flat list keeps the material-only contract under the default sort)
  @Test
  void findGlobalByFilters_excludesItemRows_underTheDefaultMaterialNameSort() {
    // When — the admin-wide flat list under the default material.name sort
    Page<InventoryItem> page =
        inventoryItemRepository.findGlobalByFilters(
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

    // Then — the material row is visible under the sort and no returned row is an item row
    assertThat(page.getContent())
        .extracting(InventoryItem::getId)
        .contains(materialRow.getId())
        .doesNotContain(itemRow.getId());
    assertThat(page.getContent()).allSatisfy(row -> assertThat(row.getMaterial()).isNotNull());
  }

  // covers REQ-INV-029 (Materialbörse releasable picker stays material-only until design §8)
  @Test
  void findReleasableForUser_excludesItemRows() {
    // When — the caller's releasable rows, unfiltered (the LEFT-JOIN rewrite with the explicit
    // material guard; the pre-fix LOWER(i.material.name) inner join dropped NULL-material rows
    // for the WRONG reason and would have leaked them once the guard was forgotten)
    List<InventoryItem> releasable =
        inventoryItemRepository.findReleasableForUser(user.getId(), null, PageRequest.of(0, 50));

    // Then
    assertThat(releasable).extracting(InventoryItem::getId).containsExactly(materialRow.getId());
  }

  // covers REQ-INV-029/031 (Materialsammlung stays material-only; item earmarks get their own seam)
  @Test
  void jobOrderRowSeams_splitEarmarksByCatalog() {
    // Given both fixture rows earmarked to one ITEM order (the production auto-earmark shape)
    UUID orderId = seedOrderWithMixedEarmarks();

    // When / Then — the Materialsammlung seam returns only the material earmark (its consumer
    // dereferences material.getName() unconditionally, so an item row here would 500 the order
    // page)...
    assertThat(inventoryItemRepository.findByJobOrderIdOrdered(orderId))
        .extracting(InventoryItem::getId)
        .containsExactly(materialRow.getId());

    // ...and the game-item sibling returns only the item earmark (the orphaned-link warning's
    // item branch).
    assertThat(inventoryItemRepository.findGameItemRowsByJobOrderIdOrdered(orderId))
        .extracting(InventoryItem::getId)
        .containsExactly(itemRow.getId());
  }

  // covers REQ-INV-005/029 (item stacks drill down through their gameItem-keyed entry queries)
  @Test
  void itemStackEntryQueries_serveTheGameItemStack() {
    // When — the owner's and the global item drill-downs for the fixture stack (no quality key)
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

    // Then — both return exactly the item row; the material sibling never leaks in
    assertThat(myEntries.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(itemRow.getId());
    assertThat(globalEntries.getContent())
        .extracting(InventoryItem::getId)
        .containsExactly(itemRow.getId());
  }

  // covers REQ-INV-029 (order stock index: no null-materialId projection rows -> no 500)
  @Test
  void findMaterialStockRowsByJobOrderIds_excludesItemEarmarks() {
    // Given both fixture rows earmarked to one order
    UUID orderId = seedOrderWithMixedEarmarks();

    // When — the batched stock projection behind the paged order list
    List<JobOrderMaterialStockRow> rows =
        inventoryItemRepository.findMaterialStockRowsByJobOrderIds(List.of(orderId));

    // Then — only the material allocation surfaces; a null materialId row would NPE the
    // consumer's Collectors.groupingBy and 500 the paged order list (design §4.4)
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).materialId()).isEqualTo(material.getId());
    assertThat(rows).allSatisfy(row -> assertThat(row.materialId()).isNotNull());
  }
}
