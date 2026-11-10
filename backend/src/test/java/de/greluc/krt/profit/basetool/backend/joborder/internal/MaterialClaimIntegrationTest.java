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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Integration tests of {@link MaterialClaimService} against Postgres: in-place upsert, overclaim
 * rejection, and claim preservation across {@link JobOrderService#reassignResponsibleOrgUnit}.
 */
@SpringBootTest
@WithMockUser(roles = {"ADMIN"})
class MaterialClaimIntegrationTest extends MaterialContributionTestSupport {

  @Test
  void upsert_persistsThenUpdatesInPlace_oneRowPerBucket() {
    Fixture f = seed();

    var first =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 6.0));
    ClaimBucketDto afterFirst = onlyBucket(f.orderId());
    assertThat(afterFirst.requiredAmount()).isEqualTo(10.0);
    assertThat(afterFirst.claimedAmount()).isEqualTo(6.0);
    assertThat(afterFirst.openRemaining()).isEqualTo(4.0);

    materialClaimService.upsertClaim(
        f.orderId(),
        new CreateClaimDto(f.materialId(), "GOOD", f.squadronAId(), 8.0, first.version()));
    ClaimBucketDto afterUpdate = onlyBucket(f.orderId());
    assertThat(afterUpdate.claimedAmount()).isEqualTo(8.0);
    assertThat(afterUpdate.openRemaining()).isEqualTo(2.0);
    assertThat(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(f.orderId()))
        .hasSize(1);
  }

  @Test
  void meaningfulEditWithoutObservedVersionConflicts() {
    Fixture f = seed();
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 4.0));

    assertThatThrownBy(() ->
            materialClaimService.upsertClaim(
                f.orderId(), claim(f.materialId(), f.squadronAId(), 6.0)))
        .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    assertThat(onlyBucket(f.orderId()).claimedAmount()).isEqualTo(4.0);
  }

  @Test
  void reducedDemandProportionallyCancelsOnlyNoLongerNeededPromises() {
    Fixture f = seed();
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 4.0));
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronBId(), 6.0));
    JobOrderDto observed = jobOrderQueryService.getJobOrderById(f.orderId());

    jobOrderService.updateJobOrder(
        f.orderId(),
        new CreateJobOrderDto(
            null,
            null,
            observed.handle(),
            null,
            List.of(new CreateJobOrderMaterialDto(f.materialId(), 650, 6.0)),
            observed.version()));

    assertThat(onlyBucket(f.orderId()).claims())
        .filteredOn(c -> c.claimingOrgUnit().id().equals(f.squadronAId()))
        .singleElement()
        .extracting(c -> c.amount())
        .isEqualTo(2.4);
    assertThat(onlyBucket(f.orderId()).claims())
        .filteredOn(c -> c.claimingOrgUnit().id().equals(f.squadronBId()))
        .singleElement()
        .extracting(c -> c.amount())
        .isEqualTo(3.6);
  }

  @Test
  void explicitlyProvidedThreeCreditsTheSelectedPromiseBeforeAnyCapacityReduction()
      throws Exception {
    Fixture f = seed();
    var a =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 4.0));
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronBId(), 6.0));
    assertThat(onlyBucket(f.orderId()).claims())
        .filteredOn(c -> c.id().equals(a.id()))
        .singleElement()
        .extracting(c -> c.version())
        .isEqualTo(a.version());
    StockFixture stock = stock(f, 3.0);
    Long rowVersion = stockReads.findRow(stock.rowId()).orElseThrow().getVersion();
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                    .springSecurity())
            .build();

    mvc.perform(
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
                        + f.orderId()
                        + "\",\"amount\":3,\"version\":"
                        + rowVersion
                        + ",\"provision\":{\"claimId\":\""
                        + a.id()
                        + "\",\"claimVersion\":"
                        + a.version()
                        + "}}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    assertThat(onlyBucket(f.orderId()).claims())
        .filteredOn(c -> c.claimingOrgUnit().id().equals(f.squadronAId()))
        .singleElement()
        .extracting(c -> c.amount())
        .isEqualTo(1.0);
    assertThat(onlyBucket(f.orderId()).claims())
        .filteredOn(c -> c.claimingOrgUnit().id().equals(f.squadronBId()))
        .singleElement()
        .extracting(c -> c.amount())
        .isEqualTo(6.0);
  }

  @Test
  void readableHistoryDistinguishesOutstandingPromiseAndRealProvision() throws Exception {
    Fixture f = seed();
    var claim =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 4.0));
    StockFixture stock = stock(f, 3.0);
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                    .springSecurity())
            .build();
    mvc.perform(
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
                        + f.orderId()
                        + "\",\"amount\":3,\"version\":"
                        + stockReads.findRow(stock.rowId()).orElseThrow().getVersion()
                        + ",\"provision\":{\"claimId\":\""
                        + claim.id()
                        + "\",\"claimVersion\":"
                        + claim.version()
                        + "}}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/orders/{id}/claims/history", f.orderId())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN"))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id")
                .value(claim.id().toString()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$[0].supporting")
                .value(true))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$[0].openAmount")
                .value(1.0))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$[0].creditedAmount")
                .value(3.0))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$[0].changes[1].reason")
                .value("PROVISION_CREDITED"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$[0].provisions[0].amount")
                .value(3.0));
  }

  @Test
  void falseHistoricalAttributionCanMoveAfterThePhysicalStockWasConsumed() throws Exception {
    Fixture f = seed();
    var a =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 4.0));
    var b =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronBId(), 6.0));
    StockFixture stock = stock(f, 3.0);
    provide(f, a, stock, 3.0);
    transactionTemplate.executeWithoutResult(_ -> {
      stockCommands.lockRow(stock.rowId()).orElseThrow();
      stockCommands.takeFromEarmarkedRow(
          stock.rowId(),
          f.orderId(),
          3.0,
          null,
          de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason.PRODUCTION);
    });
    assertThat(stockReads.findRow(stock.rowId())).isEmpty();
    var observed = contributionHistory(f.orderId(), a.id());
    var credit = observed.provisions().getFirst();
    var target = contributionHistory(f.orderId(), b.id());

    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    f.orderId(),
                    credit.id())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"kind\":\"ATTRIBUTION\",\"version\":"
                        + credit.version()
                        + ",\"claimVersion\":"
                        + observed.version()
                        + ",\"amount\":3,\"targetClaimId\":\""
                        + b.id()
                        + "\",\"targetClaimVersion\":"
                        + target.version()
                        + ",\"stockVersions\":[]}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    var correctedA = contributionHistory(f.orderId(), a.id());
    var correctedB = contributionHistory(f.orderId(), b.id());
    assertThat(correctedA.creditedAmount()).isZero();
    assertThat(correctedA.openAmount()).isEqualTo(4.0);
    assertThat(correctedB.creditedAmount()).isEqualTo(3.0);
    assertThat(correctedB.openAmount()).isEqualTo(3.0);
    assertThat(correctedA.changes().getLast().reason())
        .isEqualTo(
            de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange.Reason
                .FALSE_FACT_CORRECTED);
    assertThat(stockReads.findRow(stock.rowId())).isEmpty();
  }

  @Test
  void wrongProvisionQuantityCorrectsRetainedStockCreditAndRunningPromiseTogether()
      throws Exception {
    Fixture f = seed();
    var a =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 4.0));
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronBId(), 6.0));
    StockFixture stock = stock(f, 3.0);
    provide(f, a, stock, 3.0);
    var observed = contributionHistory(f.orderId(), a.id());
    var credit = observed.provisions().getFirst();
    Long rowVersion = stockReads.findRow(stock.rowId()).orElseThrow().getVersion();

    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    f.orderId(),
                    credit.id())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"kind\":\"QUANTITY\",\"version\":"
                        + credit.version()
                        + ",\"claimVersion\":"
                        + observed.version()
                        + ",\"amount\":2,\"targetClaimId\":null,\"targetClaimVersion\":null,"
                        + "\"stockVersions\":[{\"id\":\""
                        + stock.rowId()
                        + "\",\"version\":"
                        + rowVersion
                        + "}]}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    var corrected = contributionHistory(f.orderId(), a.id());
    assertThat(corrected.openAmount()).isEqualTo(2.0);
    assertThat(corrected.creditedAmount()).isEqualTo(2.0);
    assertThat(corrected.provisions().getFirst().quantityAmount()).isEqualTo(2.0);
    assertThat(onlyBucket(f.orderId()).claims())
        .filteredOn(c -> c.claimingOrgUnit().id().equals(f.squadronBId()))
        .singleElement()
        .extracting(c -> c.amount())
        .isEqualTo(6.0);
    assertThat(stockReads.findRow(stock.rowId()).orElseThrow().getAmount()).isEqualTo(2.0);
  }

  @Test
  void underreportedOriginalQuantityAddsOnlyTheTruthfulDeltaInTheSameCorrection() throws Exception {
    Fixture f = seed();
    var a =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 4.0));
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronBId(), 6.0));
    StockFixture stock = stock(f, 3.0);
    provide(f, a, stock, 3.0);
    var observed = contributionHistory(f.orderId(), a.id());
    var credit = observed.provisions().getFirst();
    Long rowVersion = stockReads.findRow(stock.rowId()).orElseThrow().getVersion();

    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    f.orderId(),
                    credit.id())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"kind\":\"QUANTITY\",\"version\":"
                        + credit.version()
                        + ",\"claimVersion\":"
                        + observed.version()
                        + ",\"amount\":4,\"targetClaimId\":null,\"targetClaimVersion\":null,"
                        + "\"stockVersions\":[{\"id\":\""
                        + stock.rowId()
                        + "\",\"version\":"
                        + rowVersion
                        + "}],\"targetStockRowId\":\""
                        + stock.rowId()
                        + "\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    var corrected = contributionHistory(f.orderId(), a.id());
    assertThat(corrected.openAmount()).isZero();
    assertThat(corrected.creditedAmount()).isEqualTo(4.0);
    assertThat(corrected.provisions().getFirst().quantityAmount()).isEqualTo(4.0);
    assertThat(stockReads.findRow(stock.rowId()).orElseThrow().getAmount()).isEqualTo(4.0);
    assertThat(onlyBucket(f.orderId()).claims())
        .filteredOn(c -> c.claimingOrgUnit().id().equals(f.squadronBId()))
        .singleElement()
        .extracting(c -> c.amount())
        .isEqualTo(6.0);
  }

  @Test
  void correctingAnExcessProvisionRemovesOnlyItsUncreditedTail() throws Exception {
    Fixture f = seed();
    var a =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 3.0));
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronBId(), 7.0));
    StockFixture stock = stock(f, 4.0);
    provide(f, a, stock, 4.0);
    var observed = contributionHistory(f.orderId(), a.id());
    var credit = observed.provisions().getFirst();
    assertThat(credit.amount()).isEqualTo(3.0);
    assertThat(credit.quantityAmount()).isEqualTo(4.0);
    Long rowVersion = stockReads.findRow(stock.rowId()).orElseThrow().getVersion();

    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    f.orderId(),
                    credit.id())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"kind\":\"QUANTITY\",\"version\":"
                        + credit.version()
                        + ",\"claimVersion\":"
                        + observed.version()
                        + ",\"amount\":3,\"targetClaimId\":null,\"targetClaimVersion\":null,"
                        + "\"stockVersions\":[{\"id\":\""
                        + stock.rowId()
                        + "\",\"version\":"
                        + rowVersion
                        + "}]}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    var corrected = contributionHistory(f.orderId(), a.id());
    assertThat(corrected.openAmount()).isZero();
    assertThat(corrected.creditedAmount()).isEqualTo(3.0);
    assertThat(corrected.provisions().getFirst().quantityAmount()).isEqualTo(3.0);
    assertThat(stockReads.findRow(stock.rowId()).orElseThrow().getAmount()).isEqualTo(3.0);
    assertThat(onlyBucket(f.orderId()).claims())
        .filteredOn(c -> c.claimingOrgUnit().id().equals(f.squadronBId()))
        .singleElement()
        .extracting(c -> c.amount())
        .isEqualTo(6.0);
  }

  @Test
  void historicalTargetOptionsAndCorrectionKeepEndedContributionsEnded() throws Exception {
    Fixture f = seed();
    var a =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 4.0));
    var b =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronBId(), 6.0));
    StockFixture stock = stock(f, 3.0);
    provide(f, a, stock, 3.0);
    String orderBody =
        mvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/orders/{id}", f.orderId())
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
    long orderVersion = json.readTree(orderBody).path("version").asLong();
    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/orders/{id}/status", f.orderId())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\",\"version\":" + orderVersion + "}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    var original = contributionHistory(f.orderId(), a.id());
    var target = contributionHistory(f.orderId(), b.id());
    var credit = original.provisions().getFirst();

    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/attribution-options",
                    f.orderId(),
                    credit.id())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN"))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$[0].claimId")
                .value(b.id().toString()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].ended")
                .value(true))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$[0].maxAmount")
                .value(3.0));
    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    f.orderId(),
                    credit.id())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"kind\":\"ATTRIBUTION\",\"version\":"
                        + credit.version()
                        + ",\"claimVersion\":"
                        + original.version()
                        + ",\"amount\":3,\"targetClaimId\":\""
                        + b.id()
                        + "\",\"targetClaimVersion\":"
                        + target.version()
                        + ",\"stockVersions\":[]}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    var correctedA = contributionHistory(f.orderId(), a.id());
    var correctedB = contributionHistory(f.orderId(), b.id());
    assertThat(correctedA.endedAt()).isNotNull();
    assertThat(correctedB.endedAt()).isNotNull();
    assertThat(correctedA.openAmount()).isZero();
    assertThat(correctedB.openAmount()).isZero();
    assertThat(correctedA.creditedAmount()).isZero();
    assertThat(correctedB.creditedAmount()).isEqualTo(3.0);
  }

  @Test
  void correctingAnOriginalReceiptAlsoCorrectsItsLaterCreditedUncreditedTail() throws Exception {
    Fixture f = seed();
    var a =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 3.0));
    var b =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronBId(), 7.0));
    StockFixture stock = stock(f, 4.0);
    provide(f, a, stock, 4.0);
    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
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
                        + f.orderId()
                        + "\",\"amount\":1,\"version\":"
                        + stockReads.findRow(stock.rowId()).orElseThrow().getVersion()
                        + "}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    var currentB = contributionHistory(f.orderId(), b.id());
    provide(
        f,
        new de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto(
            b.id(),
            b.claimingOrgUnit(),
            currentB.openAmount(),
            b.claimedByUserId(),
            b.claimedAt(),
            currentB.version()),
        stock,
        1.0);
    assertThat(contributionHistory(f.orderId(), b.id()).creditedAmount()).isEqualTo(1.0);
    var original = contributionHistory(f.orderId(), a.id());
    var credit = original.provisions().getFirst();

    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    f.orderId(),
                    credit.id())
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    "{\"kind\":\"QUANTITY\",\"version\":"
                        + credit.version()
                        + ",\"claimVersion\":"
                        + original.version()
                        + ",\"amount\":3,\"targetClaimId\":null,\"targetClaimVersion\":null,"
                        + "\"stockVersions\":[{\"id\":\""
                        + stock.rowId()
                        + "\",\"version\":"
                        + stockReads.findRow(stock.rowId()).orElseThrow().getVersion()
                        + "}]}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    var correctedA = contributionHistory(f.orderId(), a.id());
    var correctedB = contributionHistory(f.orderId(), b.id());
    assertThat(correctedA.creditedAmount()).isEqualTo(3.0);
    assertThat(correctedA.openAmount()).isZero();
    assertThat(correctedB.creditedAmount()).isZero();
    assertThat(correctedB.openAmount()).isEqualTo(6.0);
    assertThat(correctedB.provisions().getFirst().quantityAmount()).isZero();
    assertThat(stockReads.findRow(stock.rowId()).orElseThrow().getAmount()).isEqualTo(3.0);
  }

  @Test
  void overclaim_rejected() {
    Fixture f = seed();
    materialClaimService.upsertClaim(
        f.orderId(), new CreateClaimDto(f.materialId(), "GOOD", f.squadronAId(), 8.0, null));

    assertThatThrownBy(() ->
            materialClaimService.upsertClaim(
                f.orderId(), claim(f.materialId(), f.squadronBId(), 5.0)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void nonProfitSquadron_cannotClaim() {
    Fixture f = seed();
    UUID nonProfitSquadronId =
        transactionTemplate.execute(status -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);
          Squadron sq = new Squadron();
          sq.setName("NonProfit-" + tag);
          sq.setShorthand("N" + tag);
          return squadronRepository.save(sq).getId();
        });

    assertThatThrownBy(() ->
            materialClaimService.upsertClaim(
                f.orderId(), claim(f.materialId(), nonProfitSquadronId, 5.0)))
        .isInstanceOf(BadRequestException.class);
    assertThat(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(f.orderId())).isEmpty();
  }

  @Test
  void responsibilityTransfer_preservesTheOpenClaimAndItsFacts() {
    Fixture f = seed();
    var created =
        materialClaimService.upsertClaim(
            f.orderId(), new CreateClaimDto(f.materialId(), "GOOD", f.squadronAId(), 8.0, null));
    UUID originalResponsible =
        jobOrderQueryService.getJobOrderById(f.orderId()).responsibleOrgUnit().id();

    jobOrderService.reassignResponsibleOrgUnit(f.orderId(), f.squadronAId());
    jobOrderService.reassignResponsibleOrgUnit(f.orderId(), originalResponsible);

    assertThat(materialClaimService.getClaimBuckets(f.orderId()))
        .singleElement()
        .satisfies(bucket ->
            assertThat(bucket.claims())
                .singleElement()
                .satisfies(retained -> {
                  assertThat(retained.id()).isEqualTo(created.id());
                  assertThat(retained.amount()).isEqualTo(8.0);
                  assertThat(retained.claimingOrgUnit().id()).isEqualTo(f.squadronAId());
                }));
  }

  @Test
  void getJobOrderById_skOrder_embedsClaimsAndOpenAmountOnMaterialRows() {
    Fixture f = seed();
    var first =
        materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 6.0));

    JobOrderDto dto = jobOrderQueryService.getJobOrderById(f.orderId());

    assertThat(dto.materials()).hasSize(1);
    assertThat(dto.materials().get(0).openAmount()).isEqualTo(4.0);
    assertThat(dto.materials().get(0).claims()).hasSize(1);
    assertThat(dto.materials().get(0).claims().get(0).amount()).isEqualTo(6.0);
  }

  @Test
  void getJobOrderById_privateOrder_leavesClaimFieldsEmpty() {
    Fixture f = seed();
    jobOrderService.reassignResponsibleOrgUnit(f.orderId(), f.squadronAId());

    JobOrderDto dto = jobOrderQueryService.getJobOrderById(f.orderId());

    assertThat(dto.materials()).hasSize(1);
    assertThat(dto.materials().get(0).openAmount()).isNull();
    assertThat(dto.materials().get(0).claims()).isEmpty();
  }

  @Test
  void updateJobOrder_droppingABucket_withdrawsOrphanClaimsWithoutConflict() {
    record Setup(UUID orderId, UUID matA, UUID matB, UUID sqA, UUID sqB) {}
    Setup s =
        transactionTemplate.execute(st -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);
          SpecialCommand sk = new SpecialCommand();
          sk.setName("Orph-SK-" + tag);
          sk.setShorthand("O" + tag);
          withinProfit(sk, persistedProfitArea(orgUnitRepository));
          sk = specialCommandRepository.save(sk);
          Squadron sqA = new Squadron();
          sqA.setName("Orph-A-" + tag);
          sqA.setShorthand("A" + tag);
          withinProfit(sqA, persistedProfitArea(orgUnitRepository));
          sqA = squadronRepository.save(sqA);
          Squadron sqB = new Squadron();
          sqB.setName("Orph-B-" + tag);
          sqB.setShorthand("B" + tag);
          withinProfit(sqB, persistedProfitArea(orgUnitRepository));
          sqB = squadronRepository.save(sqB);
          Material matA = new Material();
          matA.setName("OrphMatA-" + tag);
          matA.setType(MaterialType.RAW);
          matA = materialRepository.save(matA);
          Material matB = new Material();
          matB.setName("OrphMatB-" + tag);
          matB.setType(MaterialType.RAW);
          matB = materialRepository.save(matB);
          JobOrder order =
              JobOrder.builder()
                  .responsibleOrgUnit(sk)
                  .requestingOrgUnit(sqA)
                  .handle("orph")
                  .status(JobOrderStatus.OPEN)
                  .build();
          QualityTier good = qualityTierRepository.findByCode("GOOD").orElseThrow();
          order.addMaterial(
              JobOrderMaterial.builder().material(matA).qualityTier(good).amount(10.0).build());
          order.addMaterial(
              JobOrderMaterial.builder().material(matB).qualityTier(good).amount(8.0).build());
          order = jobOrderRepository.save(order);
          return new Setup(order.getId(), matA.getId(), matB.getId(), sqA.getId(), sqB.getId());
        });

    materialClaimService.upsertClaim(
        s.orderId(), new CreateClaimDto(s.matA(), "GOOD", s.sqA(), 5.0, null));
    materialClaimService.upsertClaim(
        s.orderId(), new CreateClaimDto(s.matB(), "GOOD", s.sqB(), 4.0, null));
    assertThat(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(s.orderId()))
        .hasSize(2);

    CreateJobOrderDto update =
        new CreateJobOrderDto(
            null,
            null,
            "orph",
            null,
            List.of(new CreateJobOrderMaterialDto(s.matA(), 650, 10.0)),
            jobOrderRepository.findById(s.orderId()).orElseThrow().getVersion());
    jobOrderService.updateJobOrder(s.orderId(), update);

    List<MaterialClaim> remaining =
        materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(s.orderId());
    assertThat(remaining).hasSize(1);
    assertThat(remaining.get(0).getMaterial().getId()).isEqualTo(s.matA());
  }
}
