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

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.withinProfit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.livesync.api.LivesyncMetricNames;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Verifies complete production resource planning through real PostgreSQL and the public API. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class JobOrderProductionResourcePlanningIntegrationTest {
  @Autowired private WebApplicationContext context;
  @Autowired private TransactionTemplate transactions;
  @Autowired private OrgUnitRepository orgUnits;
  @Autowired private UserRepository users;
  @Autowired private LocationRepository locations;
  @Autowired private CityRepository cities;
  @Autowired private MaterialRepository materials;
  @Autowired private GameItemRepository gameItems;
  @Autowired private BlueprintRepository blueprints;
  @Autowired private QualityTierRepository tiers;
  @Autowired private JobOrderRepository orders;
  @Autowired private InventoryItemRepository inventory;
  @Autowired private StockReads stock;
  @Autowired private StockCommands commands;
  @Autowired private StockLotWritePlans lotPlans;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  private MockMvc mvc;

  private record Source(UUID rowId, UUID materialId, Long version) {}

  private record Fixture(
      UUID orderId,
      UUID lineId,
      Long version,
      UUID holderId,
      UUID locationId,
      List<Source> sources) {}

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void aLinkedStockChangeWithoutAnyClaimPublishesTheCommittedOrderRefresh() {
    Fixture fixture = seed();
    Source source = fixture.sources().getFirst();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM material_claim WHERE job_order_id = ?",
                Integer.class,
                fixture.orderId()))
        .isZero();
    double before = orderFrames();

    transactions.executeWithoutResult(
        _ -> commands.bookOutRow(fixture.holderId(), source.rowId(), 1.0));

    transactions.executeWithoutResult(_ -> {
      var remaining = stock.findRow(source.rowId()).orElseThrow();
      assertThat(remaining.getAmount()).isEqualTo(2.0);
      assertThat(remaining.jobOrderSliceAmount(fixture.orderId())).isEqualTo(2.0);
    });
    assertThat(orderFrames()).isGreaterThan(before);
  }

  @Test
  void aLinkedGameItemChangeWithoutAnyClaimPublishesTheCommittedOrderRefresh() {
    Fixture fixture = seed();
    UUID finished =
        transactions.execute(_ -> {
          var order = orders.findById(fixture.orderId()).orElseThrow();
          InventoryItem row = new InventoryItem();
          row.setUser(users.findById(fixture.holderId()).orElseThrow());
          row.setGameItem(order.getItems().iterator().next().getGameItem());
          row.setLocation(locations.findById(fixture.locationId()).orElseThrow());
          row.setAmount(2.0);
          row.setPersonal(false);
          InventoryAllocations.addJobOrder(row, fixture.orderId(), 2.0, false);
          return inventory.saveAndFlush(row).getId();
        });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM material_claim WHERE job_order_id = ?",
                Integer.class,
                fixture.orderId()))
        .isZero();
    double before = orderFrames();

    transactions.executeWithoutResult(_ -> commands.bookOutRow(fixture.holderId(), finished, 1.0));

    transactions.executeWithoutResult(_ -> {
      var remaining = stock.findRow(finished).orElseThrow();
      assertThat(remaining.getAmount()).isEqualTo(1.0);
      assertThat(remaining.jobOrderSliceAmount(fixture.orderId())).isEqualTo(1.0);
    });
    assertThat(orderFrames()).isGreaterThan(before);
  }

  @Test
  void preparedDeclinedUnchangedAndDryActionsPublishNoOrderRefresh() {
    Fixture fixture = seed();
    Source source = fixture.sources().getFirst();
    jdbc.update(
        "UPDATE inventory_item_job_order_allocation SET amount = 2 WHERE inventory_item_id = ?",
        source.rowId());
    double before = orderFrames();
    transactions.executeWithoutResult(_ -> {
      commands.prepareWrites(List.of(source.rowId()), List.of(), List.of());
    });
    transactions.executeWithoutResult(_ -> {
      StockLot lot = sourceLot(fixture, source);
      List<StockLotWritePlans.Intent> intents =
          List.of(
              new StockLotWritePlans.SetLotAmount(
                  0, lot, new BigDecimal("3"), new BigDecimal("2")));
      var prepared = lotPlans.prepareLotBatch(fixture.holderId(), List.of(lot), intents);
      lotPlans.confirmLotBatch(prepared, List.of(), false);
    });
    transactions.executeWithoutResult(_ -> {
      StockLot lot = sourceLot(fixture, source);
      List<StockLotWritePlans.Intent> intents =
          List.of(
              new StockLotWritePlans.SetLotAmount(
                  0, lot, new BigDecimal("3"), new BigDecimal("4")));
      var prepared = lotPlans.prepareLotBatch(fixture.holderId(), List.of(lot), intents);
      var confirmed = lotPlans.confirmLotBatch(prepared, intents, true);
      lotPlans.executePreparedLot(confirmed, 0, true);
    });

    assertThat(orderFrames()).isEqualTo(before);
    transactions.executeWithoutResult(_ -> {
      var unchanged = stock.findRow(source.rowId()).orElseThrow();
      assertThat(unchanged.getAmount()).isEqualTo(3.0);
      assertThat(unchanged.jobOrderSliceAmount(fixture.orderId())).isEqualTo(2.0);
      assertThat(unchanged.getVersion()).isEqualTo(source.version());
    });
  }

  @Test
  void aFlushedMarkerRoundTripPublishesItsChangedExposedRowVersion() {
    Fixture fixture = seed();
    Source source = fixture.sources().getFirst();
    double before = orderFrames();

    transactions.executeWithoutResult(_ -> {
      StockLot ordinary = sourceLot(fixture, source);
      StockLot stolen =
          new StockLot(ordinary.material(), null, ordinary.location(), ordinary.quality(), true);
      commands.prepareWrites(
          List.of(source.rowId()),
          List.of(
              new StockCommands.StockBookInScope(
                  fixture.holderId(), stolen, List.of(fixture.orderId()), false, null, false)),
          List.of());
      commands.markStolen(fixture.holderId(), source.rowId(), true, null);
      assertThat(stock.lotRows(fixture.holderId(), List.of(stolen)).get(stolen.key()))
          .singleElement()
          .satisfies(row -> assertThat(row.getStolen()).isTrue());
      commands.markStolen(fixture.holderId(), source.rowId(), false, null);
    });

    assertThat(orderFrames()).isGreaterThan(before);
    transactions.executeWithoutResult(_ -> {
      var unchanged = stock.findRow(source.rowId()).orElseThrow();
      assertThat(unchanged.getAmount()).isEqualTo(3.0);
      assertThat(unchanged.jobOrderSliceAmount(fixture.orderId())).isEqualTo(3.0);
      assertThat(unchanged.getStolen()).isFalse();
      assertThat(unchanged.getVersion()).isGreaterThan(source.version());
    });
  }

  @Test
  void aDeclinedWaiterDoesNotPublishAnotherTransactionsStockChangeAgain() throws Exception {
    Fixture fixture = seed();
    Source source = fixture.sources().getFirst();
    jdbc.update(
        "UPDATE inventory_item_job_order_allocation SET amount = 2 WHERE inventory_item_id = ?",
        source.rowId());
    double before = orderFrames();
    String waiter = "declined-live-plan-" + UUID.randomUUID();
    AtomicReference<Future<Throwable>> contender = new AtomicReference<>();
    try (var workers = Executors.newSingleThreadExecutor()) {
      transactions.executeWithoutResult(_ -> {
        commands.prepareWrites(List.of(source.rowId()), List.of(), List.of());
        contender.set(
            workers.submit(() -> {
              try {
                transactions.executeWithoutResult(_ -> {
                  nameConnection(waiter);
                  StockLot lot = sourceLot(fixture, source);
                  List<StockLotWritePlans.Intent> intents =
                      List.of(
                          new StockLotWritePlans.SetLotAmount(
                              0, lot, new BigDecimal("3"), new BigDecimal("2")));
                  var prepared =
                      lotPlans.prepareLotBatch(fixture.holderId(), List.of(lot), intents);
                  assertThat(prepared.lockedRows().get(lot.key()))
                      .singleElement()
                      .satisfies(row -> assertThat(row.getAmount()).isEqualTo(2.0));
                  lotPlans.confirmLotBatch(prepared, List.of(), false);
                });
                return null;
              } catch (RuntimeException conflict) {
                return conflict;
              }
            }));
        awaitGuardWait(waiter);
        commands.bookOutRow(fixture.holderId(), source.rowId(), 1.0);
      });
      assertThat(contender.get().get(15, TimeUnit.SECONDS)).isNull();
    }
    assertThat(orderFrames()).isEqualTo(before + 1);
    assertThat(stock.findRow(source.rowId()).orElseThrow().getAmount()).isEqualTo(2.0);
  }

  /** Reads the public committed order-frame acceptance metric. */
  private double orderFrames() {
    return meterRegistry
        .counter(
            LivesyncMetricNames.LIVESYNC_PUBLISH_ACCEPTED,
            LivesyncMetricNames.TAG_TOPIC_CLASS,
            LiveSyncTopicClass.ORDER.metricLabel())
        .count();
  }

  /** Returns one seeded source's exact physical lot without granting write authority. */
  private StockLot sourceLot(Fixture fixture, Source source) {
    return new StockLot(
        materials.findById(source.materialId()).orElseThrow(),
        null,
        locations.findById(fixture.locationId()).orElseThrow(),
        100,
        false);
  }

  @Test
  void aPreparedScuMarkLeavesAnUnrelatedInvalidOrdersRowUntouched() {
    Fixture fixture = seed();
    Source source = fixture.sources().getFirst();
    UUID invalidOrder =
        transactions.execute(_ -> {
          SpecialCommand invalid = new SpecialCommand();
          invalid.setName("Unrelated invalid processor " + UUID.randomUUID());
          invalid.setShorthand("UP" + UUID.randomUUID().toString().substring(0, 6));
          invalid = orgUnits.saveAndFlush(invalid);
          JobOrder order = new JobOrder();
          order.setResponsibleOrgUnit(invalid);
          order.setRequestingOrgUnit(
              orders.findById(fixture.orderId()).orElseThrow().getRequestingOrgUnit());
          order = orders.saveAndFlush(order);
          InventoryItem unrelated = new InventoryItem();
          unrelated.setUser(users.findById(fixture.holderId()).orElseThrow());
          unrelated.setMaterial(materials.findById(source.materialId()).orElseThrow());
          unrelated.setLocation(locations.findById(fixture.locationId()).orElseThrow());
          unrelated.setQuality(100);
          unrelated.setAmount(2.0);
          InventoryAllocations.addJobOrder(unrelated, order.getId(), 2.0, false);
          inventory.saveAndFlush(unrelated);
          return order.getId();
        });

    transactions.executeWithoutResult(_ -> {
      var destination =
          new StockCommands.StockBookInScope(
              fixture.holderId(),
              new StockLot(
                  materials.findById(source.materialId()).orElseThrow(),
                  null,
                  locations.findById(fixture.locationId()).orElseThrow(),
                  100,
                  true),
              List.of(fixture.orderId()),
              false,
              null,
              false);
      commands.prepareWrites(List.of(source.rowId()), List.of(destination), List.of());
      commands.markStolen(fixture.holderId(), source.rowId(), true, null);
    });

    assertThat(stock.findRow(source.rowId()).orElseThrow().getStolen()).isTrue();
    assertThat(stock.materialRowsOfOrder(invalidOrder))
        .singleElement()
        .satisfies(row -> assertThat(row.amount()).isEqualTo(2.0));
  }

  @Test
  void aChangedSourceAllocationConflictsAfterWaitingForTheMaterialGuard() throws Exception {
    Fixture fixture = seed();
    Source source = fixture.sources().getFirst();
    String waiter = "planning-source-" + UUID.randomUUID();
    AtomicReference<Future<Throwable>> contender = new AtomicReference<>();
    try (var workers = Executors.newSingleThreadExecutor()) {
      transactions.executeWithoutResult(_ -> {
        commands.prepareWrites(List.of(source.rowId()), List.of(), List.of());
        contender.set(
            workers.submit(() -> {
              try {
                transactions.executeWithoutResult(_ -> {
                  nameConnection(waiter);
                  commands.prepareWrites(List.of(source.rowId()), List.of(), List.of());
                });
                return null;
              } catch (RuntimeException conflict) {
                return conflict;
              }
            }));
        awaitGuardWait(waiter);
        commands.releaseJobOrderEarmark(source.rowId(), fixture.orderId());
      });
      assertThat(contender.get().get(15, TimeUnit.SECONDS))
          .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
    assertThat(stock.findRow(source.rowId()).orElseThrow().getAmount()).isEqualTo(3.0);
    assertThat(stock.materialRowsOfOrder(fixture.orderId()))
        .noneMatch(row -> row.id().equals(source.rowId()));
  }

  @Test
  void anInsertedMergeDestinationConflictsAndAnExplicitRetryMergesIt() throws Exception {
    Fixture fixture = seed();
    String waiter = "planning-target-" + UUID.randomUUID();
    AtomicReference<Future<Throwable>> contender = new AtomicReference<>();
    try (var workers = Executors.newSingleThreadExecutor()) {
      transactions.executeWithoutResult(_ -> {
        var booking = producedBooking(fixture);
        commands.prepareWrites(List.of(), List.of(booking), List.of());
        contender.set(
            workers.submit(() -> {
              try {
                transactions.executeWithoutResult(_ -> {
                  nameConnection(waiter);
                  commands.prepareWrites(List.of(), List.of(producedBooking(fixture)), List.of());
                });
                return null;
              } catch (RuntimeException conflict) {
                return conflict;
              }
            }));
        awaitGuardWait(waiter);
        bookProduced(fixture);
      });
      assertThat(contender.get().get(15, TimeUnit.SECONDS))
          .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
    transactions.executeWithoutResult(_ -> bookProduced(fixture));
    assertThat(stock.gameItemRowsOfOrder(fixture.orderId()))
        .singleElement()
        .satisfies(row -> assertThat(row.amount()).isEqualTo(2.0));
  }

  private StockCommands.StockBookInScope producedBooking(Fixture fixture) {
    JobOrder order = orders.findById(fixture.orderId()).orElseThrow();
    return new StockCommands.StockBookInScope(
        fixture.holderId(),
        new StockLot(
            null,
            order.getItems().iterator().next().getGameItem(),
            locations.findById(fixture.locationId()).orElseThrow(),
            null,
            false),
        List.of(fixture.orderId()),
        false,
        null,
        true);
  }

  private void bookProduced(Fixture fixture) {
    JobOrder order = orders.findById(fixture.orderId()).orElseThrow();
    commands.bookInFromProduction(
        order.getId(),
        order.getDisplayId(),
        order.getItems().iterator().next().getGameItem(),
        1,
        users.findById(fixture.holderId()).orElseThrow(),
        null,
        locations.findById(fixture.locationId()).orElseThrow(),
        false,
        true);
  }

  private void nameConnection(String name) {
    jdbc.queryForObject("SELECT set_config('application_name', ?, true)", String.class, name);
  }

  private void awaitGuardWait(String name) {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() ->
            jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE application_name = ?"
                        + " AND wait_event_type = 'Lock' AND wait_event = 'advisory'",
                    Integer.class,
                    name)
                > 0);
  }

  @Test
  void productionConsumesEveryMaterialAndBooksTheFinishedDestinationInOneAction() throws Exception {
    Fixture fixture = seed();
    ObjectNode body = json.createObjectNode();
    body.put("amount", 1);
    body.put("version", fixture.version());
    for (Source source : fixture.sources()) {
      ObjectNode consumption = body.withArray("consumption").addObject();
      consumption.put("inventoryItemId", source.rowId().toString());
      consumption.put("materialId", source.materialId().toString());
      consumption.put("version", source.version());
      consumption.put("amount", 3);
    }
    ObjectNode bookIn = body.putObject("bookIn");
    bookIn.put("ownerUserId", fixture.holderId().toString());
    bookIn.put("locationId", fixture.locationId().toString());

    mvc.perform(
            post("/api/v1/orders/{id}/items/{line}/production", fixture.orderId(), fixture.lineId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(fixture.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201))
        .andExpect(jsonPath("$.manufacturedAmount").value(1));

    assertThat(stock.materialRowsOfOrder(fixture.orderId())).isEmpty();
    assertThat(stock.gameItemRowsOfOrder(fixture.orderId()))
        .singleElement()
        .satisfies(row -> assertThat(row.amount()).isEqualTo(1.0));
  }

  private Fixture seed() {
    return transactions.execute(_ -> {
      SpecialCommand responsible = new SpecialCommand();
      responsible.setName("Production planner " + UUID.randomUUID());
      responsible.setShorthand("PP" + UUID.randomUUID().toString().substring(0, 6));
      withinProfit(responsible, persistedProfitArea(orgUnits));
      responsible = orgUnits.saveAndFlush(responsible);
      User holder = new User();
      holder.setId(UUID.randomUUID());
      holder.setUsername("production-plan-" + UUID.randomUUID());
      holder = users.saveAndFlush(holder);
      Location location = new Location();
      location.setName("Production target " + UUID.randomUUID());
      location = locations.saveAndFlush(TestPlaces.onOwnCity(location, cities));
      GameItem item = new GameItem();
      item.setName("Planned output " + UUID.randomUUID());
      item = gameItems.saveAndFlush(item);
      Blueprint blueprint = new Blueprint();
      blueprint.setOutputItem(item);
      blueprint.setOutputName(item.getName());
      blueprint = blueprints.saveAndFlush(blueprint);
      JobOrder order = new JobOrder();
      order.setResponsibleOrgUnit(responsible);
      order.setRequestingOrgUnit(responsible);
      order.setType(JobOrderType.ITEM);
      JobOrderItem line = new JobOrderItem();
      line.setJobOrder(order);
      line.setGameItem(item);
      line.setBlueprint(blueprint);
      line.setAmount(2);
      for (int index = 0; index < 2; index++) {
        Material material = new Material();
        material.setName("Production input " + UUID.randomUUID());
        material.setType(MaterialType.RAW);
        material = materials.saveAndFlush(material);
        JobOrderItemMaterial requirement = new JobOrderItemMaterial();
        requirement.setMaterial(material);
        requirement.setQualityTier(tiers.findByCode("NONE").orElseThrow());
        requirement.setRequiredQuantity(6.0);
        line.addMaterial(requirement);
      }
      order.getItems().add(line);
      order = orders.saveAndFlush(order);
      List<Source> sources = new ArrayList<>();
      for (JobOrderItemMaterial requirement : line.getMaterials()) {
        InventoryItem row = new InventoryItem();
        row.setUser(holder);
        row.setMaterial(requirement.getMaterial());
        row.setLocation(location);
        row.setQuality(100);
        row.setAmount(3.0);
        InventoryAllocations.addJobOrder(row, order.getId(), 3.0, false);
        row = inventory.saveAndFlush(row);
        sources.add(new Source(row.getId(), row.getMaterial().getId(), row.getVersion()));
      }
      return new Fixture(
          order.getId(),
          line.getId(),
          line.getVersion(),
          holder.getId(),
          location.getId(),
          List.copyOf(sources));
    });
  }
}
