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

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-level coverage for the one-time PIECE stock-merge backfill migration {@code
 * V216__merge_piece_inventory_rows.sql} (REQ-INV-026, ADR-0097) against the real Postgres test
 * schema (Testcontainers + Flyway via the {@code test} profile). The migration mutates existing
 * prod data on deploy and is a second, SQL-only implementation of the runtime merge, so its logic —
 * the NULL-as-equal grouping, the note concatenation, the offer exclusion and the SCU carve-out —
 * is pinned here rather than only trusted to apply.
 *
 * <p>The class is {@link Transactional} so the seeded rows roll back and never pollute the shared
 * container. The migration already ran (on the empty table) at context startup; the test
 * re-executes its raw SQL via {@link JdbcTemplate} (same transaction / connection as the flushed
 * fixture) to exercise the logic on seeded duplicates. That is safe because the statement is
 * idempotent.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PieceInventoryMergeMigrationDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialExchangeOfferRepository offerRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @PersistenceContext private EntityManager entityManager;

  /**
   * Loads the raw SQL of the backfill migration from the classpath so the test drives the actual
   * shipped statement (no duplicated SQL that could drift from the migration).
   *
   * @return the migration's SQL body.
   * @throws IOException if the migration resource cannot be read.
   */
  private static String backfillSql() throws IOException {
    try (InputStream in =
        PieceInventoryMergeMigrationDataTest.class.getResourceAsStream(
            "/db/migration/V216__merge_piece_inventory_rows.sql")) {
      assertThat(in).as("migration resource on the classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void backfill_mergesPieceRows_butLeavesScuAndOfferBackedRowsUntouched() throws IOException {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    OrgUnit orgUnit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();

    Location locA = location("Hub-A-" + UUID.randomUUID());
    Location locB = location("Hub-B-" + UUID.randomUUID());
    Material piece = material("Component-" + UUID.randomUUID(), QuantityType.PIECE);
    Material scu = material("Quantanium-" + UUID.randomUUID(), QuantityType.SCU);

    // Three PIECE rows at locA share the stock identity and must merge into one.
    InventoryItem p1 = row(user, piece, locA, orgUnit, 5.0, "note-a", true);
    InventoryItem p2 = row(user, piece, locA, orgUnit, 3.0, "note-b", false);
    InventoryItem p3 = row(user, piece, locA, orgUnit, 2.0, "note-a", true);

    // Two SCU rows sharing an identity are the control — they must stay append-only (untouched).
    InventoryItem s1 = row(user, scu, locA, orgUnit, 1.5, "scu-x", false);
    InventoryItem s2 = row(user, scu, locA, orgUnit, 2.5, "scu-y", false);

    // At locB: one offer-backed PIECE row plus two plain PIECE siblings of the same identity. The
    // offer-backed row must be excluded from the merge (stay separate); the two siblings merge.
    InventoryItem o1 = row(user, piece, locB, orgUnit, 4.0, "offered", false);
    InventoryItem o2 = row(user, piece, locB, orgUnit, 1.0, "plain-1", false);
    InventoryItem o3 = row(user, piece, locB, orgUnit, 1.0, "plain-2", false);
    entityManager.flush();

    offerRepository.save(
        MaterialExchangeOffer.builder()
            .kind(MaterialExchangeOfferKind.MATERIAL)
            .inventoryItem(o1)
            .owner(user)
            .owningOrgUnit(orgUnit)
            .offeredAmount(4.0)
            .status(MaterialExchangeOfferStatus.ACTIVE)
            .releasedAt(Instant.parse("2026-07-13T00:00:00Z"))
            .build());
    entityManager.flush();

    jdbcTemplate.update(backfillSql());
    entityManager.flush();
    entityManager.clear();

    // The three PIECE rows collapse to exactly one survivor with the summed amount, both distinct
    // notes and delivered reset to false.
    List<InventoryItem> pieceSurvivors = survivors(p1.getId(), p2.getId(), p3.getId());
    assertThat(pieceSurvivors).hasSize(1);
    InventoryItem merged = pieceSurvivors.get(0);
    assertThat(merged.getAmount()).isEqualTo(10.0);
    assertThat(merged.getNote()).contains("note-a").contains("note-b");
    assertThat(merged.getDelivered()).isFalse();

    // SCU rows are untouched: both still exist with their original amounts.
    assertThat(survivors(s1.getId(), s2.getId())).hasSize(2);

    // The offer-backed row survives unchanged; its two plain siblings merge into one.
    Optional<InventoryItem> offered = inventoryItemRepository.findById(o1.getId());
    assertThat(offered).isPresent();
    assertThat(offered.get().getAmount()).isEqualTo(4.0);
    assertThat(survivors(o2.getId(), o3.getId())).hasSize(1);
    assertThat(survivors(o2.getId(), o3.getId()).get(0).getAmount()).isEqualTo(2.0);
  }

  /**
   * Re-reads the given ids from the database (the context was cleared after the native backfill)
   * and returns those that still exist — the merge survivors.
   *
   * @param ids the pre-merge row ids to check.
   * @return the still-present rows.
   */
  private List<InventoryItem> survivors(UUID... ids) {
    return Stream.of(ids)
        .map(inventoryItemRepository::findById)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
  }

  /**
   * Persists a location with the given display name.
   *
   * @param name the location name.
   * @return the saved location.
   */
  private Location location(String name) {
    Location location = new Location();
    location.setName(name);
    return locationRepository.save(location);
  }

  /**
   * Persists a material with the given name and quantity type.
   *
   * @param name the material name.
   * @param quantityType the material's quantity type (PIECE or SCU).
   * @return the saved material.
   */
  private Material material(String name, QuantityType quantityType) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    material.setQuantityType(quantityType);
    return materialRepository.save(material);
  }

  /**
   * Persists a non-personal, order/mission-free inventory row at quality 800 for the given owner,
   * material, location and org unit.
   *
   * @param user the owner.
   * @param material the material.
   * @param location the storage location.
   * @param orgUnit the owning org-unit pool.
   * @param amount the amount.
   * @param note the note (may be {@code null}).
   * @param delivered the delivered marker.
   * @return the saved row.
   */
  private InventoryItem row(
      User user,
      Material material,
      Location location,
      OrgUnit orgUnit,
      double amount,
      String note,
      boolean delivered) {
    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setMaterial(material);
    item.setLocation(location);
    item.setOwningOrgUnit(orgUnit);
    item.setQuality(800);
    item.setAmount(amount);
    item.setPersonal(false);
    item.setJobOrder(null);
    item.setMission(null);
    item.setNote(note);
    item.setDelivered(delivered);
    return inventoryItemRepository.save(item);
  }
}
