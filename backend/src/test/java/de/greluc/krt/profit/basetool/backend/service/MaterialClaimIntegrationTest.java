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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateClaimDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateJobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end coverage of {@link MaterialClaimService} against the real Postgres test container: the
 * upsert persists and updates in place (one row per bucket+squadron), overclaim is rejected, and
 * the Phase-2 reassignment de-escalation withdraws claims via {@link
 * JobOrderService#reassignResponsibleOrgUnit}. Runs as an ADMIN so the service permission matrix
 * short-circuits to "allowed" and the test focuses on the invariants + reconciliation.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN"})
class MaterialClaimIntegrationTest {

  @Autowired private MaterialClaimService materialClaimService;
  @Autowired private JobOrderService jobOrderService;
  @Autowired private JobOrderQueryService jobOrderQueryService;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private MaterialClaimRepository materialClaimRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private record Fixture(UUID orderId, UUID materialId, UUID squadronAId, UUID squadronBId) {}

  private Fixture seed() {
    return transactionTemplate.execute(
        status -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);
          SpecialCommand sk = new SpecialCommand();
          sk.setName("Claim-SK-" + tag);
          sk.setShorthand("S" + tag);
          sk.setProfitEligible(true);
          sk = specialCommandRepository.save(sk);

          Squadron sqA = new Squadron();
          sqA.setName("Claim-A-" + tag);
          sqA.setShorthand("A" + tag);
          sqA.setProfitEligible(true);
          sqA = squadronRepository.save(sqA);

          Squadron sqB = new Squadron();
          sqB.setName("Claim-B-" + tag);
          sqB.setShorthand("B" + tag);
          sqB.setProfitEligible(true);
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
              JobOrderMaterial.builder().material(mat).minQuality(700).amount(10.0).build();
          order.addMaterial(line);
          order = jobOrderRepository.save(order);

          return new Fixture(order.getId(), mat.getId(), sqA.getId(), sqB.getId());
        });
  }

  @Test
  void upsert_persistsThenUpdatesInPlace_oneRowPerBucket() {
    Fixture f = seed();

    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 6.0));
    ClaimBucketDto afterFirst = onlyBucket(f.orderId());
    assertThat(afterFirst.requiredAmount()).isEqualTo(10.0);
    assertThat(afterFirst.claimedAmount()).isEqualTo(6.0);
    assertThat(afterFirst.openRemaining()).isEqualTo(4.0);

    // Re-posting the same squadron's claim updates in place rather than inserting a duplicate.
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 8.0));
    ClaimBucketDto afterUpdate = onlyBucket(f.orderId());
    assertThat(afterUpdate.claimedAmount()).isEqualTo(8.0);
    assertThat(afterUpdate.openRemaining()).isEqualTo(2.0);
    assertThat(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(f.orderId()))
        .hasSize(1);
  }

  @Test
  void overclaim_rejected() {
    Fixture f = seed();
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 8.0));

    // Squadron B requesting 5 would total 13 > the required 10.
    assertThatThrownBy(
            () ->
                materialClaimService.upsertClaim(
                    f.orderId(), claim(f.materialId(), f.squadronBId(), 5.0)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void nonProfitSquadron_cannotClaim() {
    // Only profit-eligible squadrons may sign up: a squadron the admin has not marked
    // profit-eligible is rejected with a 400 and no claim row is written, even though the order is
    // an open SK order with an unclaimed bucket and the caller is an admin.
    Fixture f = seed();
    UUID nonProfitSquadronId =
        transactionTemplate.execute(
            status -> {
              String tag = UUID.randomUUID().toString().substring(0, 8);
              Squadron sq = new Squadron();
              sq.setName("NonProfit-" + tag);
              sq.setShorthand("N" + tag);
              // profit-eligible defaults to false — left unset on purpose.
              return squadronRepository.save(sq).getId();
            });

    assertThatThrownBy(
            () ->
                materialClaimService.upsertClaim(
                    f.orderId(), claim(f.materialId(), nonProfitSquadronId, 5.0)))
        .isInstanceOf(BadRequestException.class);
    assertThat(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(f.orderId())).isEmpty();
  }

  @Test
  void deEscalationToSquadron_withdrawsAllClaims() {
    Fixture f = seed();
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 8.0));
    assertThat(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(f.orderId()))
        .isNotEmpty();

    // Reassign the SK order down to a (profit-eligible) squadron — the order becomes private, so
    // its
    // public claims are withdrawn by the reconciliation hook.
    jobOrderService.reassignResponsibleOrgUnit(f.orderId(), f.squadronAId());

    assertThat(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(f.orderId())).isEmpty();
  }

  @Test
  void getJobOrderById_skOrder_embedsClaimsAndOpenAmountOnMaterialRows() {
    // Phase 5 (#345): the order-detail DTO of a public SK order carries the per-bucket claims +
    // open-remaining on each material row.
    Fixture f = seed();
    materialClaimService.upsertClaim(f.orderId(), claim(f.materialId(), f.squadronAId(), 6.0));

    JobOrderDto dto = jobOrderQueryService.getJobOrderById(f.orderId());

    assertThat(dto.materials()).hasSize(1);
    assertThat(dto.materials().get(0).openAmount()).isEqualTo(4.0);
    assertThat(dto.materials().get(0).claims()).hasSize(1);
    assertThat(dto.materials().get(0).claims().get(0).amount()).isEqualTo(6.0);
  }

  @Test
  void getJobOrderById_privateOrder_leavesClaimFieldsEmpty() {
    // After de-escalation the order is private (squadron-responsible): no claim columns, so the DTO
    // carries an empty claim list and a null open-amount.
    Fixture f = seed();
    jobOrderService.reassignResponsibleOrgUnit(f.orderId(), f.squadronAId());

    JobOrderDto dto = jobOrderQueryService.getJobOrderById(f.orderId());

    assertThat(dto.materials()).hasSize(1);
    assertThat(dto.materials().get(0).openAmount()).isNull();
    assertThat(dto.materials().get(0).claims()).isEmpty();
  }

  @Test
  void updateJobOrder_droppingABucket_withdrawsOrphanClaimsWithoutConflict() {
    // Phase 7 (#347) end-to-end: editing an SK order to remove a material bucket auto-withdraws
    // that
    // bucket's claim (orphan reconciliation) while the surviving bucket's claim stays — and the
    // delete never collides with the order's @Version (claims are an independent aggregate).
    record Setup(UUID orderId, UUID matA, UUID matB, UUID sqA, UUID sqB) {}
    Setup s =
        transactionTemplate.execute(
            st -> {
              String tag = UUID.randomUUID().toString().substring(0, 8);
              SpecialCommand sk = new SpecialCommand();
              sk.setName("Orph-SK-" + tag);
              sk.setShorthand("O" + tag);
              sk.setProfitEligible(true);
              sk = specialCommandRepository.save(sk);
              Squadron sqA = new Squadron();
              sqA.setName("Orph-A-" + tag);
              sqA.setShorthand("A" + tag);
              sqA.setProfitEligible(true);
              sqA = squadronRepository.save(sqA);
              Squadron sqB = new Squadron();
              sqB.setName("Orph-B-" + tag);
              sqB.setShorthand("B" + tag);
              sqB.setProfitEligible(true);
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
              order.addMaterial(
                  JobOrderMaterial.builder().material(matA).minQuality(700).amount(10.0).build());
              order.addMaterial(
                  JobOrderMaterial.builder().material(matB).minQuality(700).amount(8.0).build());
              order = jobOrderRepository.save(order);
              return new Setup(order.getId(), matA.getId(), matB.getId(), sqA.getId(), sqB.getId());
            });

    materialClaimService.upsertClaim(
        s.orderId(), new CreateClaimDto(s.matA(), QualityRequirement.GOOD, s.sqA(), 5.0));
    materialClaimService.upsertClaim(
        s.orderId(), new CreateClaimDto(s.matB(), QualityRequirement.GOOD, s.sqB(), 4.0));
    assertThat(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(s.orderId()))
        .hasSize(2);

    // Edit the order to keep only material A (drops material B's bucket).
    CreateJobOrderDto update =
        new CreateJobOrderDto(
            null,
            null,
            "orph",
            null,
            List.of(new CreateJobOrderMaterialDto(s.matA(), 650, 10.0)),
            null);
    jobOrderService.updateJobOrder(s.orderId(), update);

    List<MaterialClaim> remaining =
        materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(s.orderId());
    assertThat(remaining).hasSize(1);
    assertThat(remaining.get(0).getMaterial().getId()).isEqualTo(s.matA());
  }

  private ClaimBucketDto onlyBucket(UUID orderId) {
    List<ClaimBucketDto> buckets = materialClaimService.getClaimBuckets(orderId);
    assertThat(buckets).hasSize(1);
    return buckets.get(0);
  }

  private CreateClaimDto claim(UUID materialId, UUID claimingOrgUnitId, double amount) {
    return new CreateClaimDto(materialId, QualityRequirement.GOOD, claimingOrgUnitId, amount);
  }
}
