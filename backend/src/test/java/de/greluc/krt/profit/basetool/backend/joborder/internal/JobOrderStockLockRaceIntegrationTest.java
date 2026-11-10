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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.LockRace;
import de.greluc.krt.profit.basetool.backend.support.LockRace.Caller;
import de.greluc.krt.profit.basetool.backend.support.LockRace.Held;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two transactions on PostgreSQL: a material handover or an item production on an earmarked Lager
 * row, and a delete of that row. The handover or production that waited for the row's lock answers
 * its not-found once the delete commits (ADR-0363).
 *
 * <p>Not {@code @Transactional}: every transaction commits, and the fixture is removed afterwards.
 */
@SpringBootTest
class JobOrderStockLockRaceIntegrationTest {

  private static final double STOCK = 20.0;
  private static final double REQUIRED = 5.0;

  @Autowired private JobOrderHandoverService handoverService;
  @Autowired private JobOrderItemProductionService productionService;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> orders = new ArrayList<>();
  private LockRace race;
  private User logistician;
  private UUID unitId;
  private UUID materialId;
  private UUID locationId;
  private UUID gameItemId;
  private UUID blueprintId;

  /**
   * An item order and its one production line.
   *
   * @param orderId the order
   * @param lineId the line
   */
  private record ItemOrder(@NotNull UUID orderId, @NotNull UUID lineId) {}

  @BeforeEach
  void setUp() {
    race = new LockRace(transactionTemplate, jdbc);
    String tag = UUID.randomUUID().toString().substring(0, 8);
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("order-lock-" + tag);
    logistician = userRepository.save(user);
    Squadron unit = new Squadron();
    unit.setName("Order lock " + tag);
    unit.setShorthand("O" + tag.substring(0, 4));
    unitId = squadronRepository.save(unit).getId();
    Material material = new Material();
    material.setName("Order lock ore " + tag);
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    materialId = materialRepository.save(material).getId();
    locationId = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, locationId, "Order lock depot " + tag);
    gameItemId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, name, kind, source_systems) VALUES (?, ?, 'GENERIC',"
            + " 'UEX_ONLY')",
        gameItemId,
        "Order lock helmet " + tag);
    blueprintId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO blueprint (id, scwiki_uuid, output_item_id, output_name) VALUES (?, ?, ?, ?)",
        blueprintId,
        UUID.randomUUID(),
        gameItemId,
        "Order lock helmet " + tag);
  }

  @AfterEach
  void tearDown() {
    race.close();
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", logistician.getId());
    orders.forEach(jobOrderRepository::deleteById);
    jdbc.update("DELETE FROM audit_event WHERE actor_user_id = ?", logistician.getId());
    jdbc.update("DELETE FROM blueprint WHERE id = ?", blueprintId);
    jdbc.update("DELETE FROM game_item WHERE id = ?", gameItemId);
    squadronRepository.deleteById(unitId);
    materialRepository.deleteById(materialId);
    UUID cityId =
        jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, locationId);
    jdbc.update("DELETE FROM location WHERE id = ?", locationId);
    jdbc.update("DELETE FROM city WHERE id = ?", cityId);
    userRepository.deleteById(logistician.getId());
  }

  @Test
  void aHandoverWhoseRowIsDeletedWhileItsLockWaitsAnswersNotFound() throws Exception {
    UUID order = materialOrder();
    UUID row = earmarkedRow(order);
    Held<Integer> delete = race.hold(null, () -> deleteRow(row));

    Future<JobOrderHandoverDto> handover =
        race.startWaiting(
            asLogistician(),
            () ->
                handoverService.createHandover(
                    order,
                    new JobOrderHandoverCreateDto(
                        Instant.now(),
                        "race",
                        "KARTELL",
                        List.of(new JobOrderHandoverItemCreateDto(row, REQUIRED, null, null)))));

    assertThat(delete.commit()).isOne();
    assertThatThrownBy(() -> LockRace.finish(handover))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(NotFoundException.class);
  }

  @Test
  void aProductionWhoseRowIsDeletedWhileItsLockWaitsAnswersNotFound() throws Exception {
    ItemOrder order = itemOrder();
    UUID row = earmarkedRow(order.orderId());
    Held<Integer> delete = race.hold(null, () -> deleteRow(row));

    Future<JobOrderItemDto> production =
        race.startWaiting(
            asLogistician(),
            () ->
                productionService.bookProduction(
                    order.orderId(),
                    order.lineId(),
                    new JobOrderItemProductionCreateDto(
                        1,
                        0L,
                        List.of(
                            new JobOrderItemProductionConsumptionDto(
                                row, materialId, REQUIRED, 0L)),
                        List.of(),
                        new JobOrderItemProductionCreateDto.BookInDto(
                            locationId, logistician.getId(), unitId, false, true))));

    assertThat(delete.commit()).isOne();
    assertThatThrownBy(() -> LockRace.finish(production))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(NotFoundException.class);
  }

  /**
   * Commits an open material order that needs the fixture's material.
   *
   * @return the order's id
   */
  private @NotNull UUID materialOrder() {
    UUID id =
        transactionTemplate.execute(_ -> {
          JobOrder order = newOrder(JobOrderType.MATERIAL);
          order.addMaterial(
              JobOrderMaterial.builder()
                  .material(materialRepository.getReferenceById(materialId))
                  .qualityTier(qualityTierRepository.findByCode("NONE").orElseThrow())
                  .amount(REQUIRED)
                  .build());
          return jobOrderRepository.save(order).getId();
        });
    orders.add(id);
    return id;
  }

  /**
   * Commits an open item order with one line of one unit that needs the fixture's material.
   *
   * @return the order and its line
   */
  private @NotNull ItemOrder itemOrder() {
    ItemOrder created =
        transactionTemplate.execute(_ -> {
          JobOrder order = newOrder(JobOrderType.ITEM);
          JobOrderItem line =
              JobOrderItem.builder()
                  .gameItem(gameItemRepository.getReferenceById(gameItemId))
                  .blueprint(blueprintRepository.getReferenceById(blueprintId))
                  .amount(1)
                  .build();
          line.getMaterials()
              .add(
                  JobOrderItemMaterial.builder()
                      .jobOrderItem(line)
                      .material(materialRepository.getReferenceById(materialId))
                      .requiredQuantity(REQUIRED)
                      .qualityTier(qualityTierRepository.findByCode("NONE").orElseThrow())
                      .build());
          order.addItem(line);
          JobOrder saved = jobOrderRepository.saveAndFlush(order);
          return new ItemOrder(saved.getId(), saved.getItems().iterator().next().getId());
        });
    orders.add(created.orderId());
    return created;
  }

  /**
   * Builds an open order of the fixture's unit.
   *
   * @param type the order's type
   * @return the unsaved order
   */
  private @NotNull JobOrder newOrder(@NotNull JobOrderType type) {
    Squadron unit = squadronRepository.findById(unitId).orElseThrow();
    return JobOrder.builder()
        .responsibleOrgUnit(unit)
        .requestingOrgUnit(unit)
        .handle("order-lock")
        .type(type)
        .build();
  }

  /**
   * Commits a shared row of the fixture's material, earmarked in full to an order.
   *
   * @param order the order
   * @return the row's id
   */
  private @NotNull UUID earmarkedRow(@NotNull UUID order) {
    return transactionTemplate.execute(_ -> {
      InventoryItem row = new InventoryItem();
      row.setUser(logistician);
      row.setMaterial(materialRepository.getReferenceById(materialId));
      row.setLocation(locationRepository.getReferenceById(locationId));
      row.setOwningOrgUnit(squadronRepository.getReferenceById(unitId));
      row.setQuality(500);
      row.setAmount(STOCK);
      row.setPersonal(false);
      InventoryAllocations.addJobOrder(row, order, STOCK, false);
      return inventoryItemRepository.save(row).getId();
    });
  }

  /**
   * Deletes a row in the caller's transaction.
   *
   * @param row the row
   * @return the number of deleted rows
   */
  private int deleteRow(@NotNull UUID row) {
    return jdbc.update("DELETE FROM inventory_item WHERE id = ?", row);
  }

  /**
   * The fixture's member, signed in as a Logistician.
   *
   * @return the caller
   */
  private @NotNull Caller asLogistician() {
    return Caller.of(logistician.getId(), Roles.LOGISTICIAN);
  }
}
