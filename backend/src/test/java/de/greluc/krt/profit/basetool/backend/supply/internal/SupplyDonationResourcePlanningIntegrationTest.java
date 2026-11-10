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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragments;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityProvenance;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Verifies exact donation source and merge resources through real public HTTP and PostgreSQL. */
@SpringBootTest
class SupplyDonationResourcePlanningIntegrationTest {
  @Autowired private WebApplicationContext context;
  @Autowired private TransactionTemplate transactions;
  @Autowired private UserRepository users;
  @Autowired private MaterialRepository materials;
  @Autowired private OrgUnitRepository units;
  @Autowired private SupplyUnitSettingRepository unitSettings;
  @Autowired private LocationRepository locations;
  @Autowired private CityRepository cities;
  @Autowired private JobOrderRepository orders;
  @Autowired private InventoryItemRepository rows;
  @Autowired private StockReads stock;
  @Autowired private StockQuantityProvenance provenance;
  @Autowired private StockLotWritePlans lotPlans;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SupplySettingsService settings;
  @Autowired private ObjectMapper json;
  private MockMvc mvc;
  private boolean enabled;
  private @Nullable UUID governingArea;

  /** The known donation source and the independent invalid linked row in its physical lot. */
  private record Fixture(
      UUID holder,
      UUID unit,
      UUID material,
      UUID location,
      UUID source,
      UUID unrelated,
      UUID invalidOrder) {}

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    enabled = settings.isEnabled();
    governingArea = settings.governingAreaId().orElse(null);
    settings.update(true, governingArea);
  }

  @AfterEach
  void restoreSetting() {
    settings.update(enabled, governingArea);
  }

  @Test
  void aKnownSourcePledgeLeavesAnUnrelatedInvalidOrdersRowUntouched() throws Exception {
    Fixture fixture = seed(false);
    pledge(fixture, true)
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201))
        .andExpect(jsonPath("$.status").value("PLEDGED"));

    transactions.executeWithoutResult(_ -> {
      assertThat(stock.findRow(fixture.source()).orElseThrow().donationReserved()).isEqualTo(1.0);
    });
    assertUntouched(fixture);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aReceiptGuardsOnlyItsActualOwningPoolMergeTarget(boolean actualMergeTarget)
      throws Exception {
    Fixture fixture = seed(actualMergeTarget);
    var pledged =
        pledge(fixture, false)
            .andExpect(result ->
                assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(201))
            .andReturn()
            .getResponse()
            .getContentAsString();
    DonationDto donation = json.readValue(pledged, DonationDto.class);
    var receipt =
        mvc.perform(
                post("/api/v1/supply/donations/{id}/receive", donation.id())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(fixture.holder().toString()))
                            .authorities(
                                new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new ReceiveDonationRequest(
                                1.0, fixture.location(), donation.version()))))
            .andExpect(result ->
                assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(actualMergeTarget ? 400 : 200));
    if (!actualMergeTarget) {
      receipt
          .andExpect(jsonPath("$.status").value("RECEIVED"))
          .andExpect(jsonPath("$.receivedAmount").value(1.0));
    }
    assertUntouched(fixture);
  }

  @Test
  void aMergedPledgedSourceIsReceivedWithoutCreatingPhysicalQuantity() throws Exception {
    Fixture fixture = seed(false);
    DonationDto donation =
        json.readValue(
            pledge(fixture, true)
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DonationDto.class);
    UUID survivor = mergePersonalStock(fixture);
    LotState before = lotState(fixture);
    assertThat(before.amount()).isEqualTo(5.0);
    assertThat(before.donation()).isEqualTo(1.0);
    assertThat(StockQuantityFragments.total(before.quantity())).isEqualTo(5000L);
    assertThat(stock.findRow(fixture.source())).isEmpty();
    assertThat(survivor).isNotEqualTo(fixture.source());
    transactions.executeWithoutResult(_ -> {
      var current = stock.findRow(survivor).orElseThrow();
      assertThat(current.getAmount()).isEqualTo(3.0);
      assertThat(current.donationReserved()).isEqualTo(1.0);
    });

    mvc.perform(
            post("/api/v1/supply/donations/{id}/receive", donation.id())
                .with(
                    jwt()
                        .jwt(token -> token.subject(fixture.holder().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new ReceiveDonationRequest(1.0, fixture.location(), donation.version()))))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200))
        .andExpect(jsonPath("$.status").value("RECEIVED"))
        .andExpect(jsonPath("$.receivedAmount").value(1.0));

    LotState after = lotState(fixture);
    assertThat(after.amount()).isEqualTo(5.0);
    assertThat(after.donation()).isZero();
    assertThat(StockQuantityFragments.total(after.quantity())).isEqualTo(5000L);
    assertThat(StockQuantityFragments.subtract(before.quantity(), after.quantity())).isEmpty();
    assertThat(StockQuantityFragments.subtract(after.quantity(), before.quantity())).isEmpty();
    transactions.executeWithoutResult(_ -> {
      var current = stock.findRow(survivor).orElseThrow();
      assertThat(current.getAmount()).isEqualTo(2.0);
      assertThat(current.donationReserved()).isZero();
      StockLot lot = lot(fixture);
      var poolRows =
          stock.lotRows(fixture.holder(), List.of(lot)).get(lot.key()).stream()
              .filter(row -> !row.countsAsPersonal())
              .filter(row -> {
                var pool = row.getOwningOrgUnit();
                return pool != null && pool.getId().equals(fixture.unit());
              })
              .toList();
      assertThat(poolRows).hasSize(1);
      assertThat(poolRows.getFirst().getAmount()).isEqualTo(1.0);
      assertThat(poolRows.getFirst().donationReserved()).isZero();
    });
    assertUntouched(fixture);
  }

  @Test
  void aMergedPledgeWithdrawalLocksItsCurrentCarrierLotBeforeMutatingTheRow() throws Exception {
    Fixture fixture = seed(false);
    DonationDto donation =
        json.readValue(
            pledge(fixture, true)
                .andExpect(result ->
                    assertThat(result.getResponse().getStatus())
                        .as(result.getResponse().getContentAsString())
                        .isEqualTo(201))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DonationDto.class);
    UUID survivor = mergePersonalStock(fixture);
    LotState before = lotState(fixture);
    assertThat(before.amount()).isEqualTo(5.0);
    assertThat(before.donation()).isEqualTo(1.0);
    assertThat(stock.donationEarmarkedRowIds(donation.id())).containsExactly(survivor);
    assertThat(stock.findRow(fixture.source())).isEmpty();
    String waiter = "donation-carrier-" + UUID.randomUUID();
    AtomicReference<Future<Void>> contender = new AtomicReference<>();
    try (var worker = Executors.newSingleThreadExecutor()) {
      transactions.executeWithoutResult(_ -> {
        lotPlans.prepareLotBatch(fixture.holder(), List.of(lot(fixture)), List.of());
        contender.set(
            worker.submit(() -> {
              transactions.executeWithoutResult(_ -> {
                jdbc.queryForObject(
                    "SELECT set_config('application_name', ?, true)", String.class, waiter);
                try {
                  mvc.perform(
                          post("/api/v1/supply/donations/{id}/withdraw", donation.id())
                              .with(
                                  jwt()
                                      .jwt(token -> token.subject(fixture.holder().toString()))
                                      .authorities(
                                          new SimpleGrantedAuthority("ROLE_ADMIN"),
                                          new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(
                                  json.writeValueAsString(new VersionRequest(donation.version()))))
                      .andExpect(result ->
                          assertThat(result.getResponse().getStatus())
                              .as(result.getResponse().getContentAsString())
                              .isEqualTo(200))
                      .andExpect(jsonPath("$.status").value("WITHDRAWN"));
                } catch (Exception failure) {
                  throw new AssertionError(failure);
                }
              });
              return null;
            }));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE application_name = ?"
                            + " AND wait_event_type = 'Lock' AND wait_event = 'advisory'",
                        Integer.class,
                        waiter)
                    > 0);
      });
      contender.get().get(15, TimeUnit.SECONDS);
    }
    LotState after = lotState(fixture);
    assertThat(after.amount()).isEqualTo(5.0);
    assertThat(after.donation()).isZero();
    assertThat(stock.donationEarmarkedRowIds(donation.id())).isEmpty();
    assertThat(StockQuantityFragments.total(after.quantity())).isEqualTo(5000L);
    assertThat(StockQuantityFragments.subtract(before.quantity(), after.quantity())).isEmpty();
    assertThat(StockQuantityFragments.subtract(after.quantity(), before.quantity())).isEmpty();
    transactions.executeWithoutResult(_ -> {
      var current = stock.findRow(survivor).orElseThrow();
      assertThat(current.getAmount()).isEqualTo(3.0);
      assertThat(current.donationReserved()).isZero();
    });
    assertUntouched(fixture);
  }

  /** A holder lot's complete physical quantity and pending donation reservation. */
  private record LotState(double amount, double donation, List<StockQuantityFragment> quantity) {}

  /** Creates and explicitly merges personal stock through the ordinary public Inventory action. */
  private UUID mergePersonalStock(Fixture fixture) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/inventory")
                    .with(
                        jwt()
                            .jwt(token -> token.subject(fixture.holder().toString()))
                            .authorities(
                                new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new InventoryItemCreateDto(
                                null,
                                fixture.material(),
                                null,
                                fixture.location(),
                                500,
                                1.0,
                                true,
                                false,
                                null,
                                null,
                                null,
                                true,
                                null,
                                null))))
            .andExpect(result ->
                assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(200))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).path("id").asString());
  }

  /** Reads scalar placement and original quantity intervals through published Inventory APIs. */
  private LotState lotState(Fixture fixture) {
    return transactions.execute(_ -> {
      StockLot lot = lot(fixture);
      var current = stock.lotRows(fixture.holder(), List.of(lot)).get(lot.key());
      return new LotState(
          current.stream().mapToDouble(row -> row.getAmount()).sum(),
          current.stream().mapToDouble(row -> row.donationReserved()).sum(),
          current.stream().flatMap(row -> provenance.quantityOf(row.getId()).stream()).toList());
    });
  }

  /** Resolves the fixture's exact physical identity without interpreting any mutation policy. */
  private StockLot lot(Fixture fixture) {
    return new StockLot(
        materials.findById(fixture.material()).orElseThrow(),
        null,
        locations.findById(fixture.location()).orElseThrow(),
        500,
        false);
  }

  /** Posts a normal free or source-bound pledge as the owning authenticated member. */
  private ResultActions pledge(Fixture fixture, boolean fromStock) throws Exception {
    return mvc.perform(
        post("/api/v1/supply/donations")
            .with(
                jwt()
                    .jwt(token -> token.subject(fixture.holder().toString()))
                    .authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    new PledgeRequest(
                        null,
                        fixture.unit(),
                        fromStock ? null : fixture.material(),
                        null,
                        500,
                        1.0,
                        fromStock ? fixture.source() : null))));
  }

  /** Checks physical amount, linked slice and version through the owning published stock read. */
  private void assertUntouched(Fixture fixture) {
    transactions.executeWithoutResult(_ -> {
      var unrelated = stock.findRow(fixture.unrelated()).orElseThrow();
      assertThat(unrelated.getAmount()).isEqualTo(2.0);
      assertThat(unrelated.jobOrderSliceAmount(fixture.invalidOrder())).isEqualTo(2.0);
      assertThat(unrelated.getVersion()).isZero();
    });
  }

  /**
   * Seeds an accepting pool and one precise invalid linked source outside its responsibility gate.
   */
  private Fixture seed(boolean actualMergeTarget) {
    return transactions.execute(_ -> {
      User holder = new User();
      holder.setId(UUID.randomUUID());
      holder.setUsername("donation-plan-" + UUID.randomUUID());
      holder.setApprovalStatus(ApprovalStatus.ACTIVE);
      holder.setInKeycloak(true);
      holder.setEnabledInKeycloak(true);
      holder = users.saveAndFlush(holder);
      OrgUnit receiving = unit();
      OrgUnit other = unit();
      SupplyUnitSetting setting = new SupplyUnitSetting();
      setting.setId(receiving.getId());
      setting.setAcceptsDonations(true);
      unitSettings.saveAndFlush(setting);
      Material material = new Material();
      material.setName("Donation input " + UUID.randomUUID());
      material.setType(MaterialType.RAW);
      material = materials.saveAndFlush(material);
      Location location = new Location();
      location.setName("Donation place " + UUID.randomUUID());
      location = locations.saveAndFlush(TestPlaces.onOwnCity(location, cities));
      JobOrder invalid = new JobOrder();
      invalid.setResponsibleOrgUnit(other);
      invalid.setRequestingOrgUnit(other);
      invalid = orders.saveAndFlush(invalid);
      InventoryItem source = row(holder, material, location, null);
      source.setPersonal(true);
      source = rows.saveAndFlush(source);
      InventoryItem unrelated =
          row(holder, material, location, actualMergeTarget ? receiving : other);
      InventoryAllocations.addJobOrder(unrelated, invalid.getId(), 2.0, false);
      unrelated = rows.saveAndFlush(unrelated);
      return new Fixture(
          holder.getId(),
          receiving.getId(),
          material.getId(),
          location.getId(),
          source.getId(),
          unrelated.getId(),
          invalid.getId());
    });
  }

  /** Creates an active unit outside the valid Profit order-processing hierarchy. */
  private OrgUnit unit() {
    Squadron unit = new Squadron();
    unit.setName("Donation pool " + UUID.randomUUID());
    unit.setShorthand("DP" + UUID.randomUUID().toString().substring(0, 6));
    return units.saveAndFlush(unit);
  }

  /** Creates the scalar physical stock fixture without exercising a mutation under test. */
  private static InventoryItem row(
      User holder, Material material, Location location, @Nullable OrgUnit pool) {
    InventoryItem row = new InventoryItem();
    row.setUser(holder);
    row.setMaterial(material);
    row.setLocation(location);
    row.setQuality(500);
    row.setAmount(2.0);
    row.setOwningOrgUnit(pool);
    return row;
  }
}
