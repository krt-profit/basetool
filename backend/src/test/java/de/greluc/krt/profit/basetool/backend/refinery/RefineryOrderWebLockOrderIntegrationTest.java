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

package de.greluc.krt.profit.basetool.backend.refinery;

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.withinProfit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryGood;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrderRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A web save, cancel or store of a refinery order while another writer commits a section the web
 * write does not touch (REQ-REFINERY-029): the web write locks the order before it reads it, so the
 * concurrent commit is either seen or waits, and never turns into a 409. The concurrent writer
 * commits on its own connection, right when the request's read-write transaction first loads the
 * order.
 */
@SpringBootTest
class RefineryOrderWebLockOrderIntegrationTest {

  /** How long the request waits for the concurrent writer before it goes on without it. */
  private static final long WRITER_GRACE_MILLIS = 1_000;

  private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean();

  private static final FirstWriteLoadHook HOOK = new FirstWriteLoadHook();

  @Autowired private WebApplicationContext context;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Autowired private SpaceStationRepository spaceStationRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TransactionTemplate transactions;
  @Autowired private OrgUnitRepository orgUnits;
  @Autowired private QualityTierRepository qualityTiers;
  @Autowired private JobOrderRepository jobOrders;
  @Autowired private StockReads stock;
  private final List<UUID> linkedOrders = new ArrayList<>();
  private final List<UUID> responsibleUnits = new ArrayList<>();
  private final List<UUID> extraMaterials = new ArrayList<>();

  private final ExecutorService pool = Executors.newSingleThreadExecutor();

  private final AtomicReference<Future<Integer>> writer = new AtomicReference<>();

  private MockMvc mockMvc;
  private User owner;
  private SpaceStation spaceStation;
  private Location station;
  private Material ore;
  private UUID orderId;

  @BeforeEach
  void setUp() {
    if (HOOK_REGISTERED.compareAndSet(false, true)) {
      entityManagerFactory
          .unwrap(SessionFactoryImplementor.class)
          .getServiceRegistry()
          .requireService(EventListenerRegistry.class)
          .appendListeners(EventType.POST_LOAD, HOOK);
    }
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    Squadron iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("ref-lock-" + UUID.randomUUID().toString().substring(0, 6));
    owner = userRepository.saveAndFlush(owner);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(owner.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(owner);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.saveAndFlush(membership);

    spaceStation = new SpaceStation();
    spaceStation.setName("Lock Station " + UUID.randomUUID());
    spaceStation.setHasRefineryTerminal(true);
    spaceStation = spaceStationRepository.saveAndFlush(spaceStation);
    station = new Location();
    station.setName("Lock Depot " + UUID.randomUUID());
    station.setSpaceStation(spaceStation);
    station = locationRepository.saveAndFlush(station);

    ore = new Material();
    ore.setName("Lock Ore " + UUID.randomUUID());
    ore.setType(MaterialType.RAW);
    ore = materialRepository.saveAndFlush(ore);

    RefineryOrder order = new RefineryOrder();
    order.setOwner(owner);
    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setStartedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    order.setDurationMinutes(60L);
    RefineryGood good = new RefineryGood();
    good.setInputMaterial(ore);
    good.setInputQuantity(10);
    good.setOutputMaterial(ore);
    good.setOutputQuantity(10);
    good.setQuality(100);
    good.setRefineryOrder(order);
    order.getGoods().add(good);
    orderId = refineryOrderRepository.saveAndFlush(order).getId();
    HOOK.arm(this::startConcurrentFinanceWrite);
  }

  @AfterEach
  void tearDown() {
    HOOK.disarm();
    pool.shutdownNow();
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", owner.getId());
    linkedOrders.forEach(id -> {
      jdbc.update("DELETE FROM job_order_material WHERE job_order_id = ?", id);
      jdbc.update("DELETE FROM job_order WHERE id = ?", id);
    });
    responsibleUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    jdbc.update("DELETE FROM refinery_good WHERE refinery_order_id = ?", orderId);
    jdbc.update("DELETE FROM refinery_order WHERE id = ?", orderId);
    jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", owner.getId());
    jdbc.update("DELETE FROM app_user WHERE id = ?", owner.getId());
    jdbc.update("DELETE FROM location WHERE id = ?", station.getId());
    jdbc.update("DELETE FROM space_station WHERE id = ?", spaceStation.getId());
    jdbc.update("DELETE FROM material WHERE id = ?", ore.getId());
    extraMaterials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
  }

  @Test
  void aGameDataSaveSucceedsWhileAnotherWriterCommitsTheFinances() throws Exception {
    ObjectNode body = objectMapper.createObjectNode();
    body.putObject("location").put("id", station.getId().toString());
    body.put("durationMinutes", 90);
    ObjectNode good = body.putArray("goods").addObject();
    good.putObject("inputMaterial").put("id", ore.getId().toString());
    good.put("inputQuantity", 10);
    good.putObject("outputMaterial").put("id", ore.getId().toString());
    good.put("outputQuantity", 10);
    good.put("quality", 100);
    body.putArray("parts").add("GAME_DATA");
    body.put("gameDataVersion", 0);

    mockMvc
        .perform(
            put("/api/v1/refinery-orders/" + orderId)
                .with(asOwner())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isOk());

    Map<String, Object> row = rowAfterTheWriter();
    assertThat(row.get("duration_minutes")).isEqualTo(90L);
    assertThat(row.get("game_data_version")).isEqualTo(1L);
    assertThat(row.get("finances_version")).isEqualTo(1L);
    assertThat(row.get("other_expenses")).isEqualTo(77d);
  }

  @Test
  void aCancelSucceedsWhileAnotherWriterCommitsTheFinances() throws Exception {
    mockMvc
        .perform(delete("/api/v1/refinery-orders/" + orderId).with(asOwner()))
        .andExpect(status().isOk());

    Map<String, Object> row = rowAfterTheWriter();
    assertThat(row.get("status")).isEqualTo("CANCELED");
    assertThat(row.get("other_expenses")).isEqualTo(77d);
  }

  @Test
  void aStoreSucceedsWhileAnotherWriterCommitsTheFinances() throws Exception {
    ObjectNode store = objectMapper.createObjectNode();
    ObjectNode item = store.putArray("items").addObject();
    item.put("materialId", ore.getId().toString());
    item.put("locationId", station.getId().toString());
    item.put("quality", 100);
    item.put("amount", 10);

    mockMvc
        .perform(
            post("/api/v1/refinery-orders/" + orderId + "/store")
                .with(asOwner())
                .contentType(MediaType.APPLICATION_JSON)
                .content(store.toString()))
        .andExpect(status().isOk());

    Map<String, Object> row = rowAfterTheWriter();
    assertThat(row.get("stored_at")).isNotNull();
    assertThat(row.get("other_expenses")).isEqualTo(77d);
  }

  @Test
  void aMultiMaterialStoreBooksEveryLinkedRequirementInOneTransaction() throws Exception {
    HOOK.disarm();
    Material second = new Material();
    second.setName("Second yield " + UUID.randomUUID());
    second.setType(MaterialType.RAW);
    second = materialRepository.saveAndFlush(second);
    UUID secondId = second.getId();
    extraMaterials.add(secondId);
    UUID target =
        transactions.execute(_ -> {
          SpecialCommand responsible = new SpecialCommand();
          responsible.setName("Yield processor " + UUID.randomUUID());
          responsible.setShorthand("YP" + UUID.randomUUID().toString().substring(0, 6));
          withinProfit(responsible, persistedProfitArea(orgUnits));
          responsible = orgUnits.saveAndFlush(responsible);
          responsibleUnits.add(responsible.getId());
          JobOrder order = new JobOrder();
          order.setRequestingOrgUnit(
              squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
          order.setResponsibleOrgUnit(responsible);
          for (UUID materialId : List.of(ore.getId(), secondId)) {
            JobOrderMaterial demand = new JobOrderMaterial();
            demand.setMaterial(materialRepository.findById(materialId).orElseThrow());
            demand.setQualityTier(qualityTiers.findByCode("NONE").orElseThrow());
            demand.setAmount(10.0);
            order.addMaterial(demand);
          }
          UUID targetId = jobOrders.saveAndFlush(order).getId();
          linkedOrders.add(targetId);
          RefineryOrder refinery = refineryOrderRepository.findById(orderId).orElseThrow();
          RefineryGood good = new RefineryGood();
          good.setInputMaterial(materialRepository.findById(secondId).orElseThrow());
          good.setInputQuantity(10);
          good.setOutputMaterial(materialRepository.findById(secondId).orElseThrow());
          good.setOutputQuantity(10);
          good.setQuality(100);
          good.setRefineryOrder(refinery);
          refinery.getGoods().add(good);
          refineryOrderRepository.saveAndFlush(refinery);
          return targetId;
        });
    ObjectNode body = objectMapper.createObjectNode();
    for (UUID materialId : List.of(ore.getId(), secondId)) {
      ObjectNode item = body.withArray("items").addObject();
      item.put("materialId", materialId.toString());
      item.put("locationId", station.getId().toString());
      item.put("quality", 100);
      item.put("amount", 3);
      item.put("jobOrderId", target.toString());
    }

    mockMvc
        .perform(
            post("/api/v1/refinery-orders/" + orderId + "/store")
                .with(
                    jwt()
                        .jwt(token -> token.subject(owner.getId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isOk());

    assertThat(stock.materialRowsOfOrder(target))
        .hasSize(2)
        .allSatisfy(row -> assertThat(row.amount()).isEqualTo(3.0));
  }

  /**
   * Starts the concurrent finance write on its own connection and gives it a short grace period:
   * without a lock on the order it commits at once, behind the request's lock it waits.
   */
  private void startConcurrentFinanceWrite() {
    Future<Integer> started =
        pool.submit(() ->
            jdbc.update(
                "UPDATE refinery_order SET other_expenses = 77,"
                    + " finances_version = finances_version + 1, version = version + 1"
                    + " WHERE id = ?",
                orderId));
    writer.set(started);
    try {
      started.get(WRITER_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException _) {
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> rowAfterTheWriter() throws Exception {
    Future<Integer> started = writer.get();
    assertThat(started).as("the concurrent writer ran").isNotNull();
    assertThat(started.get(30, TimeUnit.SECONDS)).isEqualTo(1);
    return jdbc.queryForMap(
        "SELECT status, duration_minutes, other_expenses, stored_at, game_data_version,"
            + " finances_version FROM refinery_order WHERE id = ?",
        orderId);
  }

  private RequestPostProcessor asOwner() {
    return jwt()
        .jwt(builder -> builder.subject(owner.getId().toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("REFINERY_READ"),
            new SimpleGrantedAuthority("REFINERY_WRITE"));
  }

  /**
   * Runs an armed action once, on the first load of a refinery order inside a read-write
   * transaction; loads in the read-only access checks pass by.
   */
  private static final class FirstWriteLoadHook implements PostLoadEventListener {

    private final AtomicReference<Runnable> armed = new AtomicReference<>();

    void arm(@NotNull Runnable action) {
      armed.set(action);
    }

    void disarm() {
      armed.set(null);
    }

    @Override
    public void onPostLoad(PostLoadEvent event) {
      if (event.getEntity() instanceof RefineryOrder
          && TransactionSynchronizationManager.isActualTransactionActive()
          && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
        Runnable action = armed.getAndSet(null);
        if (action != null) {
          action.run();
        }
      }
    }
  }
}
