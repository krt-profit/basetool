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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Provides real PostgreSQL order, contribution and stock facts for public workflow tests. */
abstract class MaterialContributionTestSupport {
  /** Resolves the designated Profit root and concrete fixture units. */
  @Autowired protected OrgUnitRepository orgUnitRepository;

  /** Encodes public request payloads and retained history responses. */
  @Autowired protected tools.jackson.databind.ObjectMapper json;

  /** Builds the real backend HTTP security harness. */
  @Autowired protected org.springframework.web.context.WebApplicationContext context;

  /** Creates genuine physical stock through the Inventory module API. */
  @Autowired
  protected de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands stockCommands;

  /** Reads current Inventory row versions and physical quantities. */
  @Autowired protected de.greluc.krt.profit.basetool.backend.inventory.api.StockReads stockReads;

  /** Persists active holder and contextual writer accounts. */
  @Autowired protected de.greluc.krt.profit.basetool.backend.identity.api.UserRepository users;

  /** Resolves inserted locations for ordinary stock bookings. */
  @Autowired
  protected de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository locations;

  /** Inserts minimal valid location catalogue facts. */
  @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbc;

  /** Executes material promise writes and current bucket reads. */
  @Autowired protected MaterialClaimService materialClaimService;

  /** Applies ordinary demand and responsibility writes. */
  @Autowired protected JobOrderService jobOrderService;

  /** Loads order DTOs carrying their observed scoped versions. */
  @Autowired protected JobOrderQueryService jobOrderQueryService;

  /** Persists concrete material-demand order fixtures. */
  @Autowired protected JobOrderRepository jobOrderRepository;

  /** Reads retained fixture contributions for invariant assertions. */
  @Autowired protected MaterialClaimRepository materialClaimRepository;

  /** Persists eligible supporting Staffeln. */
  @Autowired protected SquadronRepository squadronRepository;

  /** Persists responsible Spezialkommando fixtures. */
  @Autowired protected SpecialCommandRepository specialCommandRepository;

  /** Persists the concrete material required by an order. */
  @Autowired protected MaterialRepository materialRepository;

  /** Resolves the canonical GOOD requirement quality. */
  @Autowired protected QualityTierRepository qualityTierRepository;

  /** Commits fixture facts used by independent public transactions. */
  @Autowired protected TransactionTemplate transactionTemplate;

  /**
   * The persisted ten-unit order and two distinct eligible supporting Staffeln.
   *
   * @param orderId the material order
   * @param materialId its required material
   * @param squadronAId the first eligible Staffel
   * @param squadronBId the second eligible Staffel
   */
  protected record Fixture(UUID orderId, UUID materialId, UUID squadronAId, UUID squadronBId) {}

  /**
   * Commits a ten-unit material order under a Spezialkommando and two supporting Staffeln.
   *
   * @return independently persisted order, material and unit identities
   */
  protected Fixture seed() {
    return transactionTemplate.execute(status -> {
      String tag = UUID.randomUUID().toString().substring(0, 8);
      SpecialCommand sk = new SpecialCommand();
      sk.setName("Claim-SK-" + tag);
      sk.setShorthand("S" + tag);
      withinProfit(sk, persistedProfitArea(orgUnitRepository));
      sk = specialCommandRepository.save(sk);

      Squadron sqA = new Squadron();
      sqA.setName("Claim-A-" + tag);
      sqA.setShorthand("A" + tag);
      withinProfit(sqA, persistedProfitArea(orgUnitRepository));
      sqA = squadronRepository.save(sqA);

      Squadron sqB = new Squadron();
      sqB.setName("Claim-B-" + tag);
      sqB.setShorthand("B" + tag);
      withinProfit(sqB, persistedProfitArea(orgUnitRepository));
      sqB = squadronRepository.save(sqB);

      Material mat = new Material();
      mat.setName("ClaimMat-" + tag);
      mat.setType(MaterialType.RAW);
      mat = materialRepository.save(mat);

      JobOrder order =
          JobOrder.builder()
              .responsibleOrgUnit(sk)
              .requestingOrgUnit(sqA)
              .handle("claim-test")
              .status(JobOrderStatus.OPEN)
              .build();
      JobOrderMaterial line =
          JobOrderMaterial.builder()
              .material(mat)
              .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
              .amount(10.0)
              .build();
      order.addMaterial(line);
      order = jobOrderRepository.save(order);

      return new Fixture(order.getId(), mat.getId(), sqA.getId(), sqB.getId());
    });
  }

  /**
   * Builds the public backend HTTP harness with the application's real security filters.
   *
   * @return the authenticated public API test boundary
   */
  protected org.springframework.test.web.servlet.MockMvc mvc() {
    return org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
        .apply(
            org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                .springSecurity())
        .build();
  }

  /**
   * Attributes an ordinary Inventory allocation to the explicitly selected open contribution.
   *
   * @param fixture the destination order and material
   * @param claim the selected contribution and its observed version
   * @param stock the current physical placement and holder
   * @param amount the allocated quantity
   * @throws Exception when the public HTTP operation fails
   */
  protected void provide(
      Fixture fixture,
      de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto claim,
      StockFixture stock,
      double amount)
      throws Exception {
    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/inventory/{id}/allocation", stock.rowId())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"field\":\"JOB_ORDER\",\"targetId\":\""
                        + fixture.orderId()
                        + "\",\"amount\":"
                        + amount
                        + ",\"version\":"
                        + stockReads.findRow(stock.rowId()).orElseThrow().getVersion()
                        + ",\"provision\":{\"claimId\":\""
                        + claim.id()
                        + "\",\"claimVersion\":"
                        + claim.version()
                        + "}}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  }

  /**
   * Reads one retained contribution through the authenticated public history API.
   *
   * @param orderId the concrete order
   * @param claimId the retained contribution
   * @return the current public contribution facts
   * @throws Exception when the public history request fails
   */
  protected de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionHistoryDto
      contributionHistory(UUID orderId, UUID claimId) throws Exception {
    String body =
        mvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/orders/{id}/claims/history", orderId)
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.jwt()
                            .authorities(
                                new org.springframework.security.core.authority
                                    .SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionHistoryDto>
        history =
            json.readValue(
                body,
                new tools.jackson.core.type.TypeReference<
                    List<
                        de.greluc.krt.profit.basetool.backend.joborder.api
                            .MaterialContributionHistoryDto>>() {});
    return history.stream().filter(row -> row.id().equals(claimId)).findFirst().orElseThrow();
  }

  /**
   * The physical row created by a genuine stock booking.
   *
   * @param rowId its current storage identity
   * @param holderId its holder account
   */
  protected record StockFixture(UUID rowId, UUID holderId) {}

  /**
   * Commits genuine GOOD-quality stock through Inventory into the responsible unit's pool.
   *
   * @param fixture the order's material and responsible unit
   * @param amount the genuinely booked quantity
   * @return the current physical placement and holder
   */
  protected StockFixture stock(Fixture fixture, double amount) {
    return transactionTemplate.execute(_ -> {
      var holder = new de.greluc.krt.profit.basetool.backend.identity.api.User();
      holder.setId(UUID.randomUUID());
      holder.setUsername("provision-" + UUID.randomUUID());
      holder.setDisplayName("Provision holder");
      holder = users.saveAndFlush(holder);
      UUID locationId = UUID.randomUUID();
      de.greluc.krt.profit.basetool.backend.support.TestPlaces.insertLocation(
          jdbc, locationId, "Provision " + locationId);
      jdbc.update("UPDATE location SET version = 0 WHERE id = ?", locationId);
      var location = locations.findById(locationId).orElseThrow();
      var order = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
      UUID rowId =
          stockCommands.bookIntoPool(
              holder.getId(),
              order.getResponsibleOrgUnit(),
              new de.greluc.krt.profit.basetool.backend.inventory.api.StockLot(
                  materialRepository.getReferenceById(fixture.materialId()),
                  null,
                  location,
                  650,
                  false),
              amount,
              de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason.PURCHASE);
      return new StockFixture(rowId, holder.getId());
    });
  }

  /**
   * Reads and verifies the order's single canonical material bucket.
   *
   * @param orderId the concrete order
   * @return its public required, covered and outstanding quantities
   */
  protected ClaimBucketDto onlyBucket(UUID orderId) {
    List<ClaimBucketDto> buckets = materialClaimService.getClaimBuckets(orderId);
    assertThat(buckets).hasSize(1);
    return buckets.get(0);
  }

  /**
   * Constructs a deliberate first promise in the canonical GOOD quality bucket.
   *
   * @param materialId the required material
   * @param claimingOrgUnitId the contributing Staffel
   * @param amount the still-outstanding promise
   * @return the initial-create payload without an existing contribution version
   */
  protected CreateClaimDto claim(UUID materialId, UUID claimingOrgUnitId, double amount) {
    return new CreateClaimDto(materialId, "GOOD", claimingOrgUnitId, amount, null);
  }
}
