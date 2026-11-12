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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItem;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import de.greluc.krt.profit.basetool.backend.supply.api.PurchaseReason;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyNeeds;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyNeed;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyNeedRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Verifies exact purchase acceptance merge resources through public HTTP and PostgreSQL. */
@SpringBootTest
class PurchasingDeliveryResourcePlanningIntegrationTest {
  @Autowired private WebApplicationContext context;
  @Autowired private TransactionTemplate transactions;
  @Autowired private SystemSettings systemSettings;
  @Autowired private UserRepository users;
  @Autowired private OrgUnitRepository units;
  @Autowired private MaterialRepository materials;
  @Autowired private LocationRepository locations;
  @Autowired private CityRepository cities;
  @Autowired private JobOrderRepository orders;
  @Autowired private InventoryItemRepository rows;
  @Autowired private StockReads stock;
  @Autowired private SupplyNeedRepository needs;
  @Autowired private SupplyNeeds supplyNeeds;
  @Autowired private PurchasingPartnerRepository partners;
  @Autowired private PurchasingDealRepository deals;
  @Autowired private PurchasingDealDeliveryRepository deliveries;
  @Autowired private ObjectMapper json;
  private MockMvc mvc;
  private String enabledBefore;

  /** The real acceptance, need and untouched order-linked physical resource identities. */
  private record Fixture(
      UUID actor,
      UUID pool,
      UUID deal,
      UUID delivery,
      Long version,
      UUID need,
      UUID material,
      UUID location,
      UUID unrelated,
      UUID invalidOrder) {}

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    enabledBefore = systemSettings.getSettingValue("purchasing.enabled").orElse("false");
    systemSettings.putSettingValue("purchasing.enabled", "true");
  }

  @AfterEach
  void restoreSwitch() {
    systemSettings.putSettingValue("purchasing.enabled", enabledBefore);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void acceptanceGuardsItsActualPoolMergeTargetAndCreditsOnlyAcceptedGoods(boolean actualMerge)
      throws Exception {
    Fixture fixture = seed(actualMerge);
    var result =
        mvc.perform(
            post(
                    "/api/v1/purchasing/deals/{deal}/deliveries/{delivery}/accept",
                    fixture.deal(),
                    fixture.delivery())
                .with(
                    jwt()
                        .jwt(token -> token.subject(fixture.actor().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new AcceptDeliveryRequest(
                            1.0, 0.0, null, 500, fixture.location(), fixture.version()))));
    result.andExpect(response ->
        assertThat(response.getResponse().getStatus())
            .as(response.getResponse().getContentAsString())
            .isEqualTo(actualMerge ? 400 : 200));
    if (!actualMerge) {
      result
          .andExpect(jsonPath("$.acceptedAmount").value(1.0))
          .andExpect(jsonPath("$.deliveries[0].status").value("ACCEPTED"));
    }
    var observed =
        mvc.perform(
                get("/api/v1/purchasing/deals/{deal}", fixture.deal())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(fixture.actor().toString()))
                            .authorities(
                                new SimpleGrantedAuthority("ROLE_ADMIN"),
                                new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.deliveries[0].status").value(actualMerge ? "RECORDED" : "ACCEPTED"));
    if (actualMerge) {
      observed.andExpect(jsonPath("$.deliveries[0].version").value(fixture.version()));
    }
    transactions.executeWithoutResult(_ -> {
      var untouched = stock.findRow(fixture.unrelated()).orElseThrow();
      assertThat(untouched.getAmount()).isEqualTo(2.0);
      assertThat(untouched.jobOrderSliceAmount(fixture.invalidOrder())).isEqualTo(2.0);
      assertThat(untouched.getVersion()).isZero();
      var need = supplyNeeds.find(fixture.need()).orElseThrow();
      assertThat(need.fulfilledAmount()).isEqualTo(actualMerge ? 0.0 : 1.0);
      assertThat(need.purchasedAmount()).isEqualTo(actualMerge ? 0.0 : 1.0);
      var lot =
          new StockLot(
              materials.findById(fixture.material()).orElseThrow(),
              null,
              locations.findById(fixture.location()).orElseThrow(),
              500,
              false);
      var pooled =
          stock.lotRows(fixture.actor(), List.of(lot)).get(lot.key()).stream()
              .filter(row ->
                  row.getOwningOrgUnit() != null
                      && fixture.pool().equals(row.getOwningOrgUnit().getId()))
              .toList();
      assertThat(pooled)
          .singleElement()
          .satisfies(booked -> {
            if (actualMerge) {
              assertThat(booked.getId()).isEqualTo(fixture.unrelated());
              assertThat(booked.getAmount()).isEqualTo(2.0);
              return;
            }
            assertThat(booked.getAmount()).isEqualTo(1.0);
            assertThat(booked.getUser().getId()).isEqualTo(fixture.actor());
            assertThat(booked.getOwningOrgUnit().getId()).isEqualTo(fixture.pool());
            assertThat(booked.getPersonal()).isFalse();
            assertThat(booked.getStolen()).isFalse();
            assertThat(booked.jobOrderReserved()).isZero();
          });
    });
  }

  /** Seeds a real need and delivery beside one precise invalid linked merge candidate. */
  private Fixture seed(boolean actualMerge) {
    return transactions.execute(_ -> {
      User actor = user();
      User delegate = user();
      OrgUnit pool = unit();
      OrgUnit other = unit();
      Material material = new Material();
      material.setName("Purchase planning " + UUID.randomUUID());
      material.setType(MaterialType.RAW);
      material.setQuantityType(QuantityType.SCU);
      material = materials.saveAndFlush(material);
      Location location = new Location();
      location.setName("Purchase place " + UUID.randomUUID());
      location = locations.saveAndFlush(TestPlaces.onOwnCity(location, cities));
      JobOrder invalid = new JobOrder();
      invalid.setResponsibleOrgUnit(other);
      invalid.setRequestingOrgUnit(other);
      invalid = orders.saveAndFlush(invalid);
      InventoryItem unrelated = new InventoryItem();
      unrelated.setUser(actor);
      unrelated.setMaterial(material);
      unrelated.setLocation(location);
      unrelated.setQuality(500);
      unrelated.setAmount(2.0);
      unrelated.setOwningOrgUnit(actualMerge ? pool : other);
      InventoryAllocations.addJobOrder(unrelated, invalid.getId(), 2.0, false);
      unrelated = rows.saveAndFlush(unrelated);
      SupplyNeed need = new SupplyNeed();
      need.setOrgUnitId(pool.getId());
      need.setMaterial(material);
      need.setAmount(5.0);
      need.setPurchasable(true);
      need.setMaxUnitPrice(100L);
      need.setPurchaseReason(PurchaseReason.OTHER);
      need = needs.saveAndFlush(need);
      PurchasingPartner partner = new PurchasingPartner();
      partner.setKind(PartnerKind.ORG);
      partner.setName("Purchase partner " + UUID.randomUUID());
      partner.setChannel(PartnerChannel.OTHER);
      partner.setContactVia(PartnerContact.OTHER);
      partner.setStatus(PartnerStatus.ACTIVE);
      partner = partners.saveAndFlush(partner);
      PurchasingDeal deal = new PurchasingDeal();
      deal.setNeedId(need.getId());
      deal.setRaisingOrgUnitId(pool.getId());
      deal.setPartnerId(partner.getId());
      deal.setDelegateUserId(delegate.getId());
      deal.setMaterial(material);
      deal.setAmount(2.0);
      deal.setUnitPrice(100);
      deal.setTotal(200);
      deal.setPaymentModel(PaymentModel.ON_DELIVERY);
      deal.setRiskClass(RiskClassCode.A);
      deal.setStatus(DealStatus.IN_DELIVERY);
      deal = deals.saveAndFlush(deal);
      PurchasingDealDelivery delivery = new PurchasingDealDelivery();
      delivery.setDealId(deal.getId());
      delivery.setAmount(1.0);
      delivery.setRecordedBy(delegate.getId());
      delivery = deliveries.saveAndFlush(delivery);
      return new Fixture(
          actor.getId(),
          pool.getId(),
          deal.getId(),
          delivery.getId(),
          delivery.getVersion(),
          need.getId(),
          material.getId(),
          location.getId(),
          unrelated.getId(),
          invalid.getId());
    });
  }

  /** Persists an active synthetic acceptance participant. */
  private User user() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("purchase-plan-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setEnabledInKeycloak(true);
    return users.saveAndFlush(user);
  }

  /** Persists a pool outside the valid Profit order-processing hierarchy. */
  private OrgUnit unit() {
    Squadron unit = new Squadron();
    unit.setName("Purchase pool " + UUID.randomUUID());
    unit.setShorthand("PP" + UUID.randomUUID().toString().substring(0, 6));
    return units.saveAndFlush(unit);
  }
}
