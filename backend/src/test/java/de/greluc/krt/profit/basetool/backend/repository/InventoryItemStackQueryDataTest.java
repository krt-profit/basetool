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
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryItemStackAggregate;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level regression coverage for the group-on-read stack queries ({@link
 * InventoryItemRepository#findGlobalStacks} / {@link InventoryItemRepository#findUserStacks},
 * ADR-0003, REQ-INV-002) against the real Postgres test schema (Testcontainers + Flyway via the
 * {@code test} profile).
 *
 * <p>The sibling {@code InventoryItemStackQueryTest} only smoke-tests these queries against an
 * empty table, so it cannot catch the trap this test pins down: the projection groups the
 * <em>nullable</em> {@code owningOrgUnit} association as a whole entity. A naive
 * constructor-expression projection over a nullable to-one renders an implicit INNER JOIN, which
 * silently drops every row where that association is {@code null} — the ownerless-personal stock a
 * user with no Staffel/SK records, and (before Variante C, REQ-INV-027, dropped the scalar {@code
 * jobOrder} / {@code mission} columns off the row) the vast majority of real Lager stock that
 * belongs to no job order and no mission. That made {@code /inventory/all} and {@code
 * /inventory/my} show "no entries" even though the aggregated overview listed the very same
 * material, which is why the query LEFT JOINs {@code owningOrgUnit}. Under the current model the
 * earmarks live in side tables and are no longer part of the stock identity, so an unearmarked
 * entry must also aggregate and surface; these tests seed exactly such rows — a non-personal item
 * earmarked to nothing and a personal item with a {@code null} owning org unit — and assert they
 * still surface.
 *
 * <p>The class is {@link Transactional} so each method rolls back: the seeded rows must never
 * commit to the shared Testcontainers database, otherwise the sibling empty-table smoke test (and
 * any other unscoped query) would observe this fixture. The query still sees the rows because they
 * are flushed within the test transaction before the read.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryItemStackQueryDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  /**
   * A squadron-owned, non-personal item that is earmarked to neither a job order nor a mission (the
   * common case: both allocation collections are empty) must surface in the global stack view.
   * Since Variante C (REQ-INV-027) keeps the earmarks in side tables and out of the stock identity,
   * this pins the grouped read down to the physical stock — an unearmarked entry must still
   * aggregate and appear rather than be silently dropped.
   */
  @Test
  void findGlobalStacks_includesNonPersonalItemWithoutJobOrderOrMission() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Quantanium-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    InventoryItem inv = new InventoryItem();
    inv.setUser(user);
    inv.setLocation(location);
    inv.setMaterial(material);
    inv.setQuality(800);
    inv.setAmount(100.0);
    inv.setPersonal(false);
    inv.setOwningOrgUnit(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
    inventoryItemRepository.save(inv);
    entityManager.flush();

    UUID materialId = material.getId();
    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findGlobalStacks(
            true, List.of(materialId), null, false, null, false, null, true, null, Set.of());

    assertThat(stacks)
        .as(
            "a non-personal squadron item with null jobOrder/mission must still appear in the"
                + " admin-wide global stack view")
        .hasSize(1);
    assertThat(stacks.get(0).material().getId()).isEqualTo(materialId);
    assertThat(stacks.get(0).totalAmount()).isEqualTo(100.0);
  }

  /**
   * A personal item is, by the inventory invariants, never earmarked to a job order or mission and
   * may have a {@code null} owning org unit (ownerless personal). It must still surface in the
   * owner's grouped "my inventory" view; the implicit-join trap on the nullable {@code
   * owningOrgUnit} would otherwise hide every ownerless-personal stack, which is why the projection
   * LEFT JOINs it.
   */
  @Test
  void findUserStacks_includesPersonalItemWithoutAssociations() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Astatine-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    InventoryItem inv = new InventoryItem();
    inv.setUser(user);
    inv.setLocation(location);
    inv.setMaterial(material);
    inv.setQuality(500);
    inv.setAmount(42.0);
    inv.setPersonal(true);
    inv.setOwningOrgUnit(null);
    inventoryItemRepository.save(inv);
    entityManager.flush();

    List<InventoryStackAggregate> stacks =
        inventoryItemRepository.findUserStacks(
            user.getId(), false, null, null, false, null, false, null, false, false);

    assertThat(stacks)
        .as(
            "a personal item with null jobOrder/mission/owningOrgUnit must still appear in the"
                + " owner's grouped inventory view")
        .hasSize(1);
    assertThat(stacks.get(0).material().getId()).isEqualTo(material.getId());
    assertThat(stacks.get(0).totalAmount()).isEqualTo(42.0);
  }

  /**
   * The mutually exclusive "Mein Lager" personal- / non-personal-entries-only filters narrow the
   * owner's grouped view: {@code personalOnly = true} returns only the caller's private stock
   * ({@code personal = true}), {@code nonPersonalOnly = true} returns only the shared stock ({@code
   * personal = false}), and both {@code false} keeps every stack. Seeds one personal and one shared
   * contribution at the same location/material and asserts each toggle keeps only its side.
   */
  @Test
  void findUserStacks_personalAndNonPersonalOnly_narrowToMatchingStock() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Quantanium-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    InventoryItem personal = new InventoryItem();
    personal.setUser(user);
    personal.setLocation(location);
    personal.setMaterial(material);
    personal.setQuality(500);
    personal.setAmount(10.0);
    personal.setPersonal(true);
    inventoryItemRepository.save(personal);

    InventoryItem shared = new InventoryItem();
    shared.setUser(user);
    shared.setLocation(location);
    shared.setMaterial(material);
    shared.setQuality(600);
    shared.setAmount(25.0);
    shared.setPersonal(false);
    inventoryItemRepository.save(shared);
    entityManager.flush();

    List<InventoryStackAggregate> personalOnly =
        inventoryItemRepository.findUserStacks(
            user.getId(), false, null, null, false, null, false, null, true, false);
    assertThat(personalOnly)
        .as("personalOnly=true must return only the caller's personal stock")
        .hasSize(1);
    assertThat(personalOnly.get(0).personal()).isTrue();
    assertThat(personalOnly.get(0).totalAmount()).isEqualTo(10.0);

    List<InventoryStackAggregate> nonPersonalOnly =
        inventoryItemRepository.findUserStacks(
            user.getId(), false, null, null, false, null, false, null, false, true);
    assertThat(nonPersonalOnly)
        .as("nonPersonalOnly=true must return only the caller's shared (non-personal) stock")
        .hasSize(1);
    assertThat(nonPersonalOnly.get(0).personal()).isFalse();
    assertThat(nonPersonalOnly.get(0).totalAmount()).isEqualTo(25.0);

    List<InventoryStackAggregate> all =
        inventoryItemRepository.findUserStacks(
            user.getId(), false, null, null, false, null, false, null, false, false);
    assertThat(all)
        .as("both toggles false must return the personal and the shared stack")
        .hasSize(2);
  }

  // --- game-item stock rows (V220, REQ-INV-029) ------------------------------

  /**
   * With {@code material_id} nullable since V220, the material stack projections must exclude
   * game-item rows explicitly ({@code i.material IS NOT NULL}): an item row surfacing as a
   * null-material group would NPE the grouped assembly in {@code InventoryAggregationService}
   * (design §4.4). The item stack siblings serve those rows instead, keyed without the quality
   * dimension. Seeds one material and one game-item row for the same owner/location and pins each
   * projection to exactly its own catalog population.
   */
  // covers REQ-INV-029 (material stacks exclude NULL-material rows; item stacks serve them)
  @Test
  void materialAndItemStackProjections_splitByCatalog_withAnItemRowPresent() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Quantanium-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    GameItem gameItem = new GameItem();
    gameItem.setName("Quantum-Drive-" + UUID.randomUUID());
    gameItemRepository.save(gameItem);

    InventoryItem materialRow = new InventoryItem();
    materialRow.setUser(user);
    materialRow.setLocation(location);
    materialRow.setMaterial(material);
    materialRow.setQuality(800);
    materialRow.setAmount(100.0);
    materialRow.setPersonal(false);
    inventoryItemRepository.save(materialRow);

    InventoryItem itemRow = new InventoryItem();
    itemRow.setUser(user);
    itemRow.setLocation(location);
    itemRow.setGameItem(gameItem);
    itemRow.setAmount(3.0);
    itemRow.setPersonal(false);
    inventoryItemRepository.save(itemRow);
    entityManager.flush();

    // The user-scoped material stacks return ONLY the material stack — never a null-material
    // group for the item row.
    List<InventoryStackAggregate> materialStacks =
        inventoryItemRepository.findUserStacks(
            user.getId(), false, null, null, false, null, false, null, false, false);
    assertThat(materialStacks)
        .as("the item row must not surface as a (null-material) stack in the material view")
        .hasSize(1);
    assertThat(materialStacks.get(0).material().getId()).isEqualTo(material.getId());

    // The global material stacks carry no null-material group either (admin all-scope sweep).
    List<InventoryStackAggregate> globalStacks =
        inventoryItemRepository.findGlobalStacks(
            false, null, null, false, null, false, null, true, null, Set.of());
    assertThat(globalStacks)
        .as("no global material stack may carry a null material with an item row present")
        .allSatisfy(stack -> assertThat(stack.material()).isNotNull());

    // The item stack siblings serve the game-item population, keyed without a quality dimension.
    List<InventoryItemStackAggregate> userItemStacks =
        inventoryItemRepository.findUserItemStacks(
            user.getId(), false, null, false, null, false, false);
    assertThat(userItemStacks).hasSize(1);
    assertThat(userItemStacks.get(0).gameItem().getId()).isEqualTo(gameItem.getId());
    assertThat(userItemStacks.get(0).totalAmount()).isEqualTo(3.0);

    List<InventoryItemStackAggregate> globalItemStacks =
        inventoryItemRepository.findGlobalItemStacks(
            true, List.of(gameItem.getId()), false, null, true, null, Set.of());
    assertThat(globalItemStacks).hasSize(1);
    assertThat(globalItemStacks.get(0).gameItem().getId()).isEqualTo(gameItem.getId());
  }

  /**
   * Executes {@link InventoryItemRepository#getAggregatedItemInventory} against real Postgres under
   * the exact multi-key sort the controller drives it with ({@code gameItem.name,asc;amount,desc},
   * REQ-INV-028/029). Spring Data appends that sort to the GROUP-BY query at render time; the query
   * deliberately keeps the implicit {@code i.gameItem} root path because an explicit join alias
   * would make the appended {@code gameItem.name} key spawn a second, ungrouped join and fail
   * PostgreSQL's functional-dependency check — a regression only a real database catches (the
   * mocked unit never renders the appended ORDER BY). Seeds two rows of one game item (must
   * collapse into a single SUM tuple), one row of a second item, and one material row (must be
   * excluded by {@code i.gameItem IS NOT NULL} — surfacing as a null-gameItem tuple would NPE the
   * grouped assembly downstream).
   */
  // covers REQ-INV-028/029 (aggregated item view renders the appended gameItem.name sort on
  // Postgres)
  @Test
  void getAggregatedItemInventory_aggregatesPerItem_sortsByName_andExcludesMaterialRows() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Quantanium-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    GameItem couplingItem = new GameItem();
    couplingItem.setName("AAA-Coupling-" + UUID.randomUUID());
    gameItemRepository.save(couplingItem);

    GameItem shieldItem = new GameItem();
    shieldItem.setName("BBB-Shield-" + UUID.randomUUID());
    gameItemRepository.save(shieldItem);

    InventoryItem couplingRowOne = new InventoryItem();
    couplingRowOne.setUser(user);
    couplingRowOne.setLocation(location);
    couplingRowOne.setGameItem(couplingItem);
    couplingRowOne.setAmount(2.0);
    couplingRowOne.setPersonal(false);
    inventoryItemRepository.save(couplingRowOne);

    InventoryItem couplingRowTwo = new InventoryItem();
    couplingRowTwo.setUser(user);
    couplingRowTwo.setLocation(location);
    couplingRowTwo.setGameItem(couplingItem);
    couplingRowTwo.setAmount(3.0);
    couplingRowTwo.setPersonal(false);
    inventoryItemRepository.save(couplingRowTwo);

    InventoryItem shieldRow = new InventoryItem();
    shieldRow.setUser(user);
    shieldRow.setLocation(location);
    shieldRow.setGameItem(shieldItem);
    shieldRow.setAmount(7.0);
    shieldRow.setPersonal(false);
    inventoryItemRepository.save(shieldRow);

    InventoryItem materialRow = new InventoryItem();
    materialRow.setUser(user);
    materialRow.setLocation(location);
    materialRow.setMaterial(material);
    materialRow.setQuality(800);
    materialRow.setAmount(100.0);
    materialRow.setPersonal(false);
    inventoryItemRepository.save(materialRow);
    entityManager.flush();

    // Drive the query exactly like InventoryAggregationService.getAggregatedItemInventory does
    // for an admin all-scope caller under the controller's ITEM_AGGREGATED_DEFAULT_SORT.
    Page<Object[]> page =
        inventoryItemRepository.getAggregatedItemInventory(
            true,
            null,
            Set.of(),
            PageRequest.of(
                0, 20, Sort.by(Sort.Order.asc("gameItem.name"), Sort.Order.desc("amount"))));

    assertThat(page.getContent())
        .as("every aggregated tuple must carry a game item — material rows are excluded")
        .allSatisfy(tuple -> assertThat(tuple[0]).isNotNull());

    List<Object[]> seeded =
        page.getContent().stream()
            .filter(
                tuple -> {
                  UUID id = ((GameItem) tuple[0]).getId();
                  return id.equals(couplingItem.getId()) || id.equals(shieldItem.getId());
                })
            .toList();
    assertThat(seeded)
        .as("each seeded game item must yield exactly one aggregated tuple")
        .hasSize(2);
    assertThat(((GameItem) seeded.get(0)[0]).getId())
        .as("gameItem.name asc puts the AAA item before the BBB item")
        .isEqualTo(couplingItem.getId());
    assertThat(((Number) seeded.get(0)[1]).doubleValue())
        .as("the two coupling rows must collapse into one SUM(amount) tuple")
        .isEqualTo(5.0);
    assertThat(((GameItem) seeded.get(1)[0]).getId()).isEqualTo(shieldItem.getId());
    assertThat(((Number) seeded.get(1)[1]).doubleValue()).isEqualTo(7.0);
  }

  // --- select-all flat entry-id queries (REQ-INV-034) ------------------------

  /**
   * The material select-all id query ({@link InventoryItemRepository#findUserEntryIds}) returns the
   * raw ids of <em>every</em> matching material entry the owner holds — the flat companion of
   * {@link InventoryItemRepository#findUserStacks} — never rolled up per stack. It must be
   * owner-scoped (no other user's rows), exclude game-item rows (the {@code i.material IS NOT NULL}
   * guard, V220), and return separate ids for two entries that share one stack (so a bulk check-out
   * can span the whole stack, not just its grouped row). Seeds two material entries at the same
   * location for the owner, one item entry for the owner (must be excluded) and one material entry
   * for another user (must be excluded).
   */
  // covers REQ-INV-034 (material select-all returns all own material entry ids, owner-scoped,
  // item rows excluded)
  @Test
  void findUserEntryIds_returnsAllOwnMaterialEntries_excludesOtherUsersAndItemRows() {
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("u-" + UUID.randomUUID());
    userRepository.save(owner);

    User other = new User();
    other.setId(UUID.randomUUID());
    other.setUsername("u-" + UUID.randomUUID());
    userRepository.save(other);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Quantanium-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    GameItem gameItem = new GameItem();
    gameItem.setName("Quantum-Drive-" + UUID.randomUUID());
    gameItemRepository.save(gameItem);

    InventoryItem ownerEntryOne = new InventoryItem();
    ownerEntryOne.setUser(owner);
    ownerEntryOne.setLocation(location);
    ownerEntryOne.setMaterial(material);
    ownerEntryOne.setQuality(800);
    ownerEntryOne.setAmount(10.0);
    ownerEntryOne.setPersonal(false);
    inventoryItemRepository.save(ownerEntryOne);

    InventoryItem ownerEntryTwo = new InventoryItem();
    ownerEntryTwo.setUser(owner);
    ownerEntryTwo.setLocation(location);
    ownerEntryTwo.setMaterial(material);
    ownerEntryTwo.setQuality(800);
    ownerEntryTwo.setAmount(5.0);
    ownerEntryTwo.setPersonal(false);
    inventoryItemRepository.save(ownerEntryTwo);

    InventoryItem ownerItemEntry = new InventoryItem();
    ownerItemEntry.setUser(owner);
    ownerItemEntry.setLocation(location);
    ownerItemEntry.setGameItem(gameItem);
    ownerItemEntry.setAmount(3.0);
    ownerItemEntry.setPersonal(false);
    inventoryItemRepository.save(ownerItemEntry);

    InventoryItem otherEntry = new InventoryItem();
    otherEntry.setUser(other);
    otherEntry.setLocation(location);
    otherEntry.setMaterial(material);
    otherEntry.setQuality(800);
    otherEntry.setAmount(99.0);
    otherEntry.setPersonal(false);
    inventoryItemRepository.save(otherEntry);
    entityManager.flush();

    List<UUID> ids =
        inventoryItemRepository.findUserEntryIds(
            owner.getId(), false, null, null, false, null, false, null, false, false);

    assertThat(ids)
        .as(
            "material select-all returns every own material entry id (both entries of the shared"
                + " stack), excluding the item row and the other user's row")
        .containsExactlyInAnyOrder(ownerEntryOne.getId(), ownerEntryTwo.getId());
  }

  /**
   * The material select-all id query honours the same mutually exclusive personal / non-personal
   * toggles as the grouped view: {@code personalOnly = true} returns only the caller's private
   * entry ids, {@code nonPersonalOnly = true} only the shared ones. Seeds one personal and one
   * shared material entry for the owner and pins each toggle to its own id.
   */
  // covers REQ-INV-034 (material select-all id query respects the personal-only toggles)
  @Test
  void findUserEntryIds_personalAndNonPersonalOnly_narrowToMatchingEntries() {
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("u-" + UUID.randomUUID());
    userRepository.save(owner);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Astatine-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    InventoryItem personal = new InventoryItem();
    personal.setUser(owner);
    personal.setLocation(location);
    personal.setMaterial(material);
    personal.setQuality(500);
    personal.setAmount(10.0);
    personal.setPersonal(true);
    inventoryItemRepository.save(personal);

    InventoryItem shared = new InventoryItem();
    shared.setUser(owner);
    shared.setLocation(location);
    shared.setMaterial(material);
    shared.setQuality(600);
    shared.setAmount(25.0);
    shared.setPersonal(false);
    inventoryItemRepository.save(shared);
    entityManager.flush();

    assertThat(
            inventoryItemRepository.findUserEntryIds(
                owner.getId(), false, null, null, false, null, false, null, true, false))
        .as("personalOnly=true returns only the private entry id")
        .containsExactly(personal.getId());
    assertThat(
            inventoryItemRepository.findUserEntryIds(
                owner.getId(), false, null, null, false, null, false, null, false, true))
        .as("nonPersonalOnly=true returns only the shared entry id")
        .containsExactly(shared.getId());
    assertThat(
            inventoryItemRepository.findUserEntryIds(
                owner.getId(), false, null, null, false, null, false, null, false, false))
        .as("both toggles false returns both entry ids")
        .containsExactlyInAnyOrder(personal.getId(), shared.getId());
  }

  /**
   * The game-item select-all id query ({@link InventoryItemRepository#findUserItemEntryIds})
   * returns the ids of every matching game-item entry the owner holds and excludes material rows
   * (the {@code i.gameItem IS NOT NULL} guard). Seeds two item entries and one material entry for
   * the owner and asserts only the item ids come back.
   */
  // covers REQ-INV-034 (item select-all returns all own item entry ids, material rows excluded)
  @Test
  void findUserItemEntryIds_returnsAllOwnItemEntries_excludesMaterialRows() {
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("u-" + UUID.randomUUID());
    userRepository.save(owner);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    Material material = new Material();
    material.setName("Quantanium-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    materialRepository.save(material);

    GameItem gameItem = new GameItem();
    gameItem.setName("Quantum-Drive-" + UUID.randomUUID());
    gameItemRepository.save(gameItem);

    InventoryItem itemEntryOne = new InventoryItem();
    itemEntryOne.setUser(owner);
    itemEntryOne.setLocation(location);
    itemEntryOne.setGameItem(gameItem);
    itemEntryOne.setAmount(2.0);
    itemEntryOne.setPersonal(false);
    inventoryItemRepository.save(itemEntryOne);

    InventoryItem itemEntryTwo = new InventoryItem();
    itemEntryTwo.setUser(owner);
    itemEntryTwo.setLocation(location);
    itemEntryTwo.setGameItem(gameItem);
    itemEntryTwo.setAmount(3.0);
    itemEntryTwo.setPersonal(false);
    inventoryItemRepository.save(itemEntryTwo);

    InventoryItem materialEntry = new InventoryItem();
    materialEntry.setUser(owner);
    materialEntry.setLocation(location);
    materialEntry.setMaterial(material);
    materialEntry.setQuality(800);
    materialEntry.setAmount(100.0);
    materialEntry.setPersonal(false);
    inventoryItemRepository.save(materialEntry);
    entityManager.flush();

    List<UUID> ids =
        inventoryItemRepository.findUserItemEntryIds(
            owner.getId(), false, null, false, null, false, false);

    assertThat(ids)
        .as("item select-all returns every own item entry id, excluding the material row")
        .containsExactlyInAnyOrder(itemEntryOne.getId(), itemEntryTwo.getId());
  }
}
