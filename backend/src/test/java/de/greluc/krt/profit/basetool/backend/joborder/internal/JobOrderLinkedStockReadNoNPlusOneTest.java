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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Verifies against real Postgres that the job order's reads of the Lager stock earmarked to it
 * issue the same number of SQL statements whether the order holds a few or many rows (REQ-DATA-003,
 * REQ-ORDERS-028): the material collection, the item stock, the linked and the orphaned rows as
 * Lager DTOs, and the batched stock index.
 */
@SpringBootTest
class JobOrderLinkedStockReadNoNPlusOneTest {

  private static final int FEW = 3;

  private static final int MANY = 25;

  private static final int STATEMENT_BOUND = 15;

  @Autowired private JobOrderStockReadService stockReadService;
  @Autowired private JobOrderQueryService queryService;
  @Autowired private JobOrderStockProjectionService projectionService;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private Squadron unit;

  private final List<Object> created = new ArrayList<>();

  /**
   * One seeded order with its earmarked rows.
   *
   * @param order the order every row is earmarked to
   * @param material the material of the material rows
   */
  private record Seed(JobOrder order, Material material) {}

  @BeforeEach
  void signInAsAdmin() {
    String tag = UUID.randomUUID().toString().substring(0, 6);
    Squadron squadron = new Squadron();
    squadron.setName("Linked Unit " + tag);
    squadron.setShorthand("L" + tag);
    squadron.setProfitEligible(true);
    unit = squadronRepository.save(squadron);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("linked-stock-reads")
                    .header("alg", "none")
                    .subject(UUID.randomUUID().toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  @AfterEach
  void signOutAndCleanUp() {
    SecurityContextHolder.clearContext();
    for (Object entity : created.reversed()) {
      switch (entity) {
        case InventoryItem item -> inventoryItemRepository.deleteById(item.getId());
        case JobOrder order -> jobOrderRepository.deleteById(order.getId());
        case Material material -> materialRepository.deleteById(material.getId());
        case GameItem gameItem -> gameItemRepository.deleteById(gameItem.getId());
        case Location location -> locationRepository.deleteById(location.getId());
        case User user -> userRepository.deleteById(user.getId());
        default -> throw new IllegalStateException("unexpected " + entity);
      }
    }
    created.clear();
    squadronRepository.deleteById(unit.getId());
  }

  @Test
  void theOrderLinkedReadsStayStatementBoundedWhateverTheNumberOfRows() {
    Seed few = seed(FEW);
    Seed many = seed(MANY);

    Map<String, Function<Seed, Integer>> reads = new LinkedHashMap<>();
    reads.put(
        "material collection",
        s -> {
          var entries = stockReadService.getMaterialCollection(s.order().getId());
          assertThat(entries)
              .allSatisfy(
                  e -> {
                    assertThat(e.materialName()).isEqualTo(s.material().getName());
                    assertThat(e.allocatedQuantity()).isEqualTo(3.0);
                    assertThat(e.delivered()).isFalse();
                  });
          return entries.size();
        });
    reads.put(
        "item stock",
        s -> {
          var groups = stockReadService.getItemStockForJobOrder(s.order().getId());
          assertThat(groups).hasSize(1);
          assertThat(groups.getFirst().gameItem().name()).startsWith("Linked Item");
          assertThat(groups.getFirst().entries())
              .allSatisfy(e -> assertThat(e.allocatedQuantity()).isEqualTo(2L));
          return groups.getFirst().entries().size();
        });
    reads.put(
        "linked rows of the material",
        s -> {
          var rows =
              queryService.getInventoryItemsForJobOrderMaterial(
                  s.order().getId(), s.material().getId());
          assertThat(rows).allSatisfy(r -> assertThat(r.jobOrderAllocations()).hasSize(1));
          return rows.size();
        });
    reads.put(
        "orphaned rows",
        s -> {
          var rows = queryService.getOrphanedLinkedInventory(s.order().getId());
          assertThat(rows).extracting(r -> r.gameItem() == null).contains(true, false);
          return rows.size() / 2;
        });
    reads.put(
        "stock index",
        s ->
            projectionService
                .loadOrderLinkedStockIndex(List.of(s.order().getId()))
                .suppliesFor(s.order().getId(), s.material().getId())
                .size());

    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    for (Map.Entry<String, Function<Seed, Integer>> read : reads.entrySet()) {
      stats.clear();
      int fewRows = read.getValue().apply(few);
      long fewStatements = stats.getPrepareStatementCount();
      stats.clear();
      int manyRows = read.getValue().apply(many);
      long manyStatements = stats.getPrepareStatementCount();

      assertThat(fewRows).as(read.getKey() + " rows for the small order").isEqualTo(FEW);
      assertThat(manyRows).as(read.getKey() + " rows for the large order").isEqualTo(MANY);
      assertThat(manyStatements)
          .as(read.getKey() + " statements for " + MANY + " rows against " + FEW + " (N+1)")
          .isEqualTo(fewStatements)
          .isLessThanOrEqualTo(STATEMENT_BOUND);
    }
  }

  private Seed seed(int rows) {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("linked-" + tag);
    owner = userRepository.save(owner);
    created.add(owner);
    Material material = new Material();
    material.setName("Linked Ore " + tag);
    material.setType(MaterialType.REFINED);
    material.setQuantityType(QuantityType.SCU);
    material = materialRepository.save(material);
    created.add(material);
    GameItem gameItem = new GameItem();
    gameItem.setName("Linked Item " + tag);
    gameItem = gameItemRepository.save(gameItem);
    created.add(gameItem);
    Location location = new Location();
    location.setName("Linked Depot " + tag);
    location = locationRepository.save(location);
    created.add(location);
    JobOrder order =
        jobOrderRepository.save(
            JobOrder.builder()
                .responsibleOrgUnit(unit)
                .requestingOrgUnit(unit)
                .handle("linked-" + tag)
                .status(JobOrderStatus.OPEN)
                .build());
    created.add(order);
    for (int i = 0; i < rows; i++) {
      InventoryItem materialRow = new InventoryItem();
      materialRow.setUser(owner);
      materialRow.setMaterial(material);
      materialRow.setLocation(location);
      materialRow.setQuality(500);
      materialRow.setAmount(10.0);
      materialRow.setPersonal(false);
      materialRow.setStolen(false);
      InventoryAllocations.addJobOrder(materialRow, order.getId(), 3.0, false);
      created.add(inventoryItemRepository.save(materialRow));
      InventoryItem itemRow = new InventoryItem();
      itemRow.setUser(owner);
      itemRow.setGameItem(gameItem);
      itemRow.setLocation(location);
      itemRow.setAmount(5.0);
      itemRow.setPersonal(false);
      itemRow.setStolen(false);
      InventoryAllocations.addJobOrder(itemRow, order.getId(), 2.0, false);
      created.add(inventoryItemRepository.save(itemRow));
    }
    return new Seed(order, material);
  }
}
