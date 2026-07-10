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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateClaimDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Mockito unit tests for {@link MaterialClaimService}: open-remaining math for both order kinds,
 * the SK-only / terminal-freeze / overclaim / unknown-bucket guards, the upsert (insert vs.
 * update), the permission matrix, and the reconciliation hooks.
 */
@ExtendWith(MockitoExtension.class)
class MaterialClaimServiceTest {

  @Mock private MaterialClaimRepository materialClaimRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private UserRepository userRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private MaterialMapper materialMapper;
  @Mock private SquadronMapper squadronMapper;

  @Mock private AuditService auditService;

  /**
   * Deferred self-reference the {@link MaterialClaimService#upsertClaim} orchestrator uses to open
   * a fresh {@code REQUIRES_NEW} transaction per attempt. In-process there is no Spring proxy, so
   * tests point {@code self.getObject()} at the service under test (or a spy) so the orchestrator
   * invokes the real within-transaction body.
   */
  @Mock private ObjectProvider<MaterialClaimService> self;

  @InjectMocks private MaterialClaimService service;

  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID MATERIAL_ID = UUID.randomUUID();
  private static final UUID SK_ID = UUID.randomUUID();
  private static final UUID SQUADRON_A = UUID.randomUUID();
  private static final UUID SQUADRON_B = UUID.randomUUID();

  private Material material;
  private SpecialCommand responsibleSk;
  private Squadron squadronA;
  private Squadron squadronB;

  @BeforeEach
  void setUp() {
    material = new Material();
    material.setId(MATERIAL_ID);
    material.setName("Aslarite");

    responsibleSk = new SpecialCommand();
    responsibleSk.setId(SK_ID);
    responsibleSk.setShorthand("SKX");

    squadronA = new Squadron();
    squadronA.setId(SQUADRON_A);
    squadronA.setShorthand("ALF");
    squadronA.setProfitEligible(true);

    squadronB = new Squadron();
    squadronB.setId(SQUADRON_B);
    squadronB.setShorthand("BRV");
    squadronB.setProfitEligible(true);
  }

  // ---------------------------------------------------------------
  // open-remaining math
  // ---------------------------------------------------------------

  @Nested
  class GetClaimBucketsTests {

    @Test
    void materialOrder_computesRequiredClaimedOpenRemaining() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(ORDER_ID))
          .thenReturn(
              List.of(
                  claim(order, QualityRequirement.GOOD, squadronA, 3.0),
                  claim(order, QualityRequirement.GOOD, squadronB, 2.0)));

      List<ClaimBucketDto> buckets = service.getClaimBuckets(ORDER_ID);

      assertEquals(1, buckets.size());
      ClaimBucketDto bucket = buckets.get(0);
      assertEquals(QualityRequirement.GOOD, bucket.qualityRequirement());
      assertEquals(10.0, bucket.requiredAmount());
      assertEquals(5.0, bucket.claimedAmount());
      assertEquals(5.0, bucket.openRemaining());
      assertEquals(2, bucket.claims().size());
    }

    @Test
    void itemOrder_sumsRequiredPerQualityBucket() {
      JobOrder order = new JobOrder();
      order.setId(ORDER_ID);
      order.setType(JobOrderType.ITEM);
      order.setResponsibleOrgUnit(responsibleSk);
      order.setStatus(JobOrderStatus.OPEN);
      JobOrderItem item = new JobOrderItem();
      item.setMaterials(
          new HashSet<>(
              Set.of(
                  itemMaterial(material, QualityRequirement.GOOD, 4.0),
                  itemMaterial(material, QualityRequirement.GOOD, 6.0))));
      order.setItems(new HashSet<>(Set.of(item)));
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(ORDER_ID))
          .thenReturn(List.of());

      List<ClaimBucketDto> buckets = service.getClaimBuckets(ORDER_ID);

      assertEquals(1, buckets.size());
      assertEquals(10.0, buckets.get(0).requiredAmount());
      assertEquals(0.0, buckets.get(0).claimedAmount());
      assertEquals(10.0, buckets.get(0).openRemaining());
    }

    @Test
    void unknownOrder_throwsNotFound() {
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(NotFoundException.class, () -> service.getClaimBuckets(ORDER_ID));
    }
  }

  // ---------------------------------------------------------------
  // upsert guards
  // ---------------------------------------------------------------

  @Nested
  class UpsertGuardTests {

    @BeforeEach
    void delegateSelfToRealService() {
      // upsertClaim now runs each attempt through self.getObject() so the retry gets a fresh
      // REQUIRES_NEW transaction. In-process there is no proxy, so point self at the service under
      // test — the orchestrator then invokes the real within-transaction body.
      when(self.getObject()).thenReturn(service);
    }

    @Test
    void nonSkOrder_rejected() {
      JobOrder order = materialOrder(squadronA, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

      assertThrows(
          BadRequestException.class, () -> service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 5.0)));
      verify(materialClaimRepository, never()).save(any());
    }

    @Test
    void terminalOrder_rejected() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.COMPLETED, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

      assertThrows(
          BadRequestException.class, () -> service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 5.0)));
      verify(materialClaimRepository, never()).save(any());
    }

    @Test
    void unknownBucket_rejected() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      adminCaller();

      // NONE bucket does not exist on this GOOD-only order.
      CreateClaimDto dto =
          new CreateClaimDto(MATERIAL_ID, QualityRequirement.NONE, SQUADRON_A, 5.0);
      assertThrows(BadRequestException.class, () -> service.upsertClaim(ORDER_ID, dto));
      verify(materialClaimRepository, never()).save(any());
    }

    @Test
    void overclaim_rejected() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      adminCaller();
      // Squadron B already holds 8 on the bucket; A requesting 5 would total 13 > 10.
      when(materialClaimRepository.findByJobOrderIdAndMaterialIdAndQualityRequirement(
              ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD))
          .thenReturn(List.of(claim(order, QualityRequirement.GOOD, squadronB, 8.0)));

      assertThrows(
          BadRequestException.class, () -> service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 5.0)));
      verify(materialClaimRepository, never()).save(any());
    }

    @Test
    void exactlyFillingRemaining_isAccepted() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      adminCaller();
      when(materialClaimRepository.findByJobOrderIdAndMaterialIdAndQualityRequirement(
              ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD))
          .thenReturn(List.of(claim(order, QualityRequirement.GOOD, squadronB, 8.0)));
      when(materialClaimRepository
              .findByJobOrderIdAndMaterialIdAndQualityRequirementAndClaimingOrgUnitId(
                  ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD, SQUADRON_A))
          .thenReturn(Optional.empty());
      when(orgUnitRepository.findById(SQUADRON_A)).thenReturn(Optional.of(squadronA));
      when(authHelperService.currentUserId()).thenReturn(Optional.empty());
      when(materialClaimRepository.save(any(MaterialClaim.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 2.0));

      verify(materialClaimRepository).save(any(MaterialClaim.class));
    }

    @Test
    void nonProfitEligibleSquadron_rejected() {
      // Only Profit-side squadrons may sign up for SK-order material — a squadron the admin has not
      // marked profit-eligible is rejected even when every other invariant (open SK order, known
      // bucket, no overclaim, admin caller) passes.
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      adminCaller();
      when(materialClaimRepository.findByJobOrderIdAndMaterialIdAndQualityRequirement(
              ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD))
          .thenReturn(List.of());
      when(materialClaimRepository
              .findByJobOrderIdAndMaterialIdAndQualityRequirementAndClaimingOrgUnitId(
                  ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD, SQUADRON_A))
          .thenReturn(Optional.empty());
      Squadron nonProfit = new Squadron();
      nonProfit.setId(SQUADRON_A);
      nonProfit.setShorthand("NPF");
      // profit-eligible defaults to false — left unset on purpose.
      when(orgUnitRepository.findById(SQUADRON_A)).thenReturn(Optional.of(nonProfit));

      assertThrows(
          BadRequestException.class, () -> service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 4.0)));
      verify(materialClaimRepository, never()).save(any());
    }
  }

  // ---------------------------------------------------------------
  // upsert insert vs. update
  // ---------------------------------------------------------------

  @Nested
  class UpsertInsertUpdateTests {

    @BeforeEach
    void delegateSelfToRealService() {
      when(self.getObject()).thenReturn(service);
    }

    @Test
    void noExistingClaim_inserts() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      adminCaller();
      when(materialClaimRepository.findByJobOrderIdAndMaterialIdAndQualityRequirement(
              ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD))
          .thenReturn(List.of());
      when(materialClaimRepository
              .findByJobOrderIdAndMaterialIdAndQualityRequirementAndClaimingOrgUnitId(
                  ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD, SQUADRON_A))
          .thenReturn(Optional.empty());
      when(orgUnitRepository.findById(SQUADRON_A)).thenReturn(Optional.of(squadronA));
      when(authHelperService.currentUserId()).thenReturn(Optional.empty());
      when(materialClaimRepository.save(any(MaterialClaim.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 4.0));

      verify(orgUnitRepository).findById(SQUADRON_A);
      verify(materialClaimRepository).save(any(MaterialClaim.class));
    }

    @Test
    void existingClaim_updatesAmountInPlace() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      MaterialClaim existing = claim(order, QualityRequirement.GOOD, squadronA, 3.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      adminCaller();
      when(materialClaimRepository.findByJobOrderIdAndMaterialIdAndQualityRequirement(
              ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD))
          .thenReturn(List.of(existing));
      when(materialClaimRepository
              .findByJobOrderIdAndMaterialIdAndQualityRequirementAndClaimingOrgUnitId(
                  ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD, SQUADRON_A))
          .thenReturn(Optional.of(existing));
      when(authHelperService.currentUserId()).thenReturn(Optional.empty());
      when(materialClaimRepository.save(any(MaterialClaim.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 6.0));

      assertEquals(6.0, existing.getAmount(), "existing claim's amount is updated in place");
      verify(orgUnitRepository, never()).findById(any());
      verify(materialClaimRepository).save(existing);
    }
  }

  // ---------------------------------------------------------------
  // permission matrix
  // ---------------------------------------------------------------

  @Nested
  class PermissionTests {

    @BeforeEach
    void delegateSelfToRealService() {
      when(self.getObject()).thenReturn(service);
    }

    @Test
    void neitherOwnSquadronNorSkAuthority_forbidden() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(authHelperService.isAdmin()).thenReturn(false);
      when(ownerScopeService.hasRoleInOrgUnit(SK_ID, "LOGISTICIAN")).thenReturn(false);
      when(authHelperService.canEditOrgUnit(SQUADRON_A)).thenReturn(false);

      assertThrows(
          org.springframework.security.access.AccessDeniedException.class,
          () -> service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 4.0)));
      verify(materialClaimRepository, never()).save(any());
    }

    @Test
    void ownSquadronLogistician_allowed() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(authHelperService.isAdmin()).thenReturn(false);
      when(ownerScopeService.hasRoleInOrgUnit(SK_ID, "LOGISTICIAN")).thenReturn(false);
      when(authHelperService.canEditOrgUnit(SQUADRON_A)).thenReturn(true);
      when(materialClaimRepository.findByJobOrderIdAndMaterialIdAndQualityRequirement(
              ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD))
          .thenReturn(List.of());
      when(materialClaimRepository
              .findByJobOrderIdAndMaterialIdAndQualityRequirementAndClaimingOrgUnitId(
                  ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD, SQUADRON_A))
          .thenReturn(Optional.empty());
      when(orgUnitRepository.findById(SQUADRON_A)).thenReturn(Optional.of(squadronA));
      when(authHelperService.currentUserId()).thenReturn(Optional.empty());
      when(materialClaimRepository.save(any(MaterialClaim.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 4.0));

      verify(materialClaimRepository).save(any(MaterialClaim.class));
    }

    @Test
    void responsibleSkLogisticianOrLead_mayManageForeignSquadronClaim() {
      // An authority of the responsible SK manages any claim. Both an SK logistician
      // (is_logistician) and an SK lead (is_lead, which is automatically a logistician of its SK
      // via
      // CustomJwtGrantedAuthoritiesConverter) surface here as the contextual LOGISTICIAN@skId.
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(authHelperService.isAdmin()).thenReturn(false);
      when(ownerScopeService.hasRoleInOrgUnit(SK_ID, "LOGISTICIAN")).thenReturn(true);
      when(materialClaimRepository.findByJobOrderIdAndMaterialIdAndQualityRequirement(
              ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD))
          .thenReturn(List.of());
      when(materialClaimRepository
              .findByJobOrderIdAndMaterialIdAndQualityRequirementAndClaimingOrgUnitId(
                  ORDER_ID, MATERIAL_ID, QualityRequirement.GOOD, SQUADRON_A))
          .thenReturn(Optional.empty());
      when(orgUnitRepository.findById(SQUADRON_A)).thenReturn(Optional.of(squadronA));
      when(authHelperService.currentUserId()).thenReturn(Optional.empty());
      when(materialClaimRepository.save(any(MaterialClaim.class)))
          .thenAnswer(inv -> inv.getArgument(0));

      service.upsertClaim(ORDER_ID, dto(SQUADRON_A, 4.0));

      verify(materialClaimRepository).save(any(MaterialClaim.class));
    }
  }

  // ---------------------------------------------------------------
  // concurrency: find-or-create race retry (last-writer-wins)
  // ---------------------------------------------------------------

  /**
   * Pins the concurrency contract of the claim upsert: two logisticians of the same squadron
   * lodging the first claim on one bucket race on the unique index / {@code @Version}, and the
   * loser must retry in a fresh transaction rather than surface a 500 — so last-writer-wins
   * actually holds. Drives the orchestrator ({@link MaterialClaimService#upsertClaim}) with a spied
   * within-transaction body to simulate the race deterministically (a real-Postgres reproduction
   * lives in {@code MaterialClaimConcurrencyTest}).
   */
  @Nested
  class UpsertClaimConcurrencyTests {

    private final CreateClaimDto payload = dto(SQUADRON_A, 4.0);

    @Test
    void retriesInAFreshTransaction_whenTheInsertRaceLoses() {
      MaterialClaimService spied = spy(service);
      when(self.getObject()).thenReturn(spied);
      ClaimDto expected = sampleClaimDto();
      // First attempt loses the INSERT race (unique index), retry finds the winner's row and wins.
      doThrow(new DataIntegrityViolationException("uq_material_claim_bucket_org_unit"))
          .doReturn(expected)
          .when(spied)
          .upsertClaimWithinTransaction(ORDER_ID, payload);

      ClaimDto result = service.upsertClaim(ORDER_ID, payload);

      assertEquals(expected, result, "the winning retry's claim must be returned");
      verify(spied, times(2)).upsertClaimWithinTransaction(ORDER_ID, payload);
    }

    @Test
    void retriesInAFreshTransaction_whenTheUpdateRaceLoses() {
      MaterialClaimService spied = spy(service);
      when(self.getObject()).thenReturn(spied);
      ClaimDto expected = sampleClaimDto();
      // First attempt loses the @Version race on the existing row, retry reloads and wins.
      doThrow(new ObjectOptimisticLockingFailureException(MaterialClaim.class, null))
          .doReturn(expected)
          .when(spied)
          .upsertClaimWithinTransaction(ORDER_ID, payload);

      ClaimDto result = service.upsertClaim(ORDER_ID, payload);

      assertEquals(expected, result);
      verify(spied, times(2)).upsertClaimWithinTransaction(ORDER_ID, payload);
    }

    @Test
    void propagatesConflict_whenEveryAttemptLosesTheRace() {
      MaterialClaimService spied = spy(service);
      when(self.getObject()).thenReturn(spied);
      // A pathological, never-winning race: the bounded retry gives up and surfaces the conflict so
      // the exception maps to a truthful 409, never a 500.
      doThrow(new ObjectOptimisticLockingFailureException(MaterialClaim.class, null))
          .when(spied)
          .upsertClaimWithinTransaction(ORDER_ID, payload);

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> service.upsertClaim(ORDER_ID, payload));
      // Exactly MAX_UPSERT_ATTEMPTS attempts (four swallowed + one propagating).
      verify(spied, times(5)).upsertClaimWithinTransaction(ORDER_ID, payload);
    }

    private ClaimDto sampleClaimDto() {
      return new ClaimDto(UUID.randomUUID(), null, 4.0, null, null, 0L);
    }
  }

  // ---------------------------------------------------------------
  // withdraw + reconciliation
  // ---------------------------------------------------------------

  @Nested
  class WithdrawAndReconciliationTests {

    @Test
    void withdrawClaim_foreignOrder_throwsNotFound() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      JobOrder otherOrder = new JobOrder();
      otherOrder.setId(UUID.randomUUID());
      MaterialClaim claim = claim(otherOrder, QualityRequirement.GOOD, squadronA, 3.0);
      UUID claimId = UUID.randomUUID();
      claim.setId(claimId);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(materialClaimRepository.findById(claimId)).thenReturn(Optional.of(claim));

      assertThrows(NotFoundException.class, () -> service.withdrawClaim(ORDER_ID, claimId));
      verify(materialClaimRepository, never()).delete(any());
    }

    @Test
    void withdrawClaim_happyPath_deletes() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      MaterialClaim claim = claim(order, QualityRequirement.GOOD, squadronA, 3.0);
      UUID claimId = UUID.randomUUID();
      claim.setId(claimId);
      when(jobOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(materialClaimRepository.findById(claimId)).thenReturn(Optional.of(claim));
      when(authHelperService.isAdmin()).thenReturn(true);

      service.withdrawClaim(ORDER_ID, claimId);

      verify(materialClaimRepository).delete(claim);
    }

    @Test
    void withdrawAllForOrder_deletesEveryClaim() {
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      List<MaterialClaim> claims =
          List.of(
              claim(order, QualityRequirement.GOOD, squadronA, 3.0),
              claim(order, QualityRequirement.GOOD, squadronB, 2.0));
      when(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(ORDER_ID))
          .thenReturn(claims);

      service.withdrawAllForOrderWithinTransaction(order);

      verify(materialClaimRepository).deleteAll(claims);
    }

    @Test
    void withdrawOrphanedClaims_deletesOnlyClaimsWhoseBucketIsGone() {
      // Order now only requires the GOOD bucket; a stale NONE-bucket claim is orphaned.
      JobOrder order = materialOrder(responsibleSk, JobOrderStatus.OPEN, 700, 10.0);
      MaterialClaim live = claim(order, QualityRequirement.GOOD, squadronA, 3.0);
      MaterialClaim orphan = claim(order, QualityRequirement.NONE, squadronB, 2.0);
      when(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(ORDER_ID))
          .thenReturn(List.of(live, orphan));

      service.withdrawOrphanedClaimsWithinTransaction(order);

      verify(materialClaimRepository).deleteAll(List.of(orphan));
    }

    @Test
    void withdrawOrphanedClaims_itemOrder_usesItemDerivedBuckets() {
      // Phase 7 (#347): the orphan computation is kind-agnostic — for an ITEM order the surviving
      // buckets come from the item-derived materials, not from a JobOrderMaterial list.
      JobOrder order = new JobOrder();
      order.setId(ORDER_ID);
      order.setType(JobOrderType.ITEM);
      order.setResponsibleOrgUnit(responsibleSk);
      order.setStatus(JobOrderStatus.OPEN);
      JobOrderItem item = new JobOrderItem();
      // Only the GOOD bucket survives on the (edited) item order.
      item.setMaterials(
          new HashSet<>(Set.of(itemMaterial(material, QualityRequirement.GOOD, 10.0))));
      order.setItems(new HashSet<>(Set.of(item)));
      MaterialClaim live = claim(order, QualityRequirement.GOOD, squadronA, 3.0);
      MaterialClaim orphan = claim(order, QualityRequirement.NONE, squadronB, 2.0);
      when(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(ORDER_ID))
          .thenReturn(List.of(live, orphan));

      service.withdrawOrphanedClaimsWithinTransaction(order);

      verify(materialClaimRepository).deleteAll(List.of(orphan));
    }
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  private void adminCaller() {
    when(authHelperService.isAdmin()).thenReturn(true);
  }

  private CreateClaimDto dto(UUID claimingOrgUnitId, double amount) {
    return new CreateClaimDto(MATERIAL_ID, QualityRequirement.GOOD, claimingOrgUnitId, amount);
  }

  private JobOrder materialOrder(
      OrgUnit responsible, JobOrderStatus status, Integer minQuality, double amount) {
    JobOrder order = new JobOrder();
    order.setId(ORDER_ID);
    order.setType(JobOrderType.MATERIAL);
    order.setResponsibleOrgUnit(responsible);
    order.setStatus(status);
    JobOrderMaterial jm = new JobOrderMaterial();
    jm.setMaterial(material);
    jm.setMinQuality(minQuality);
    jm.setAmount(amount);
    order.setMaterials(new HashSet<>(Set.of(jm)));
    return order;
  }

  private JobOrderItemMaterial itemMaterial(
      Material mat, QualityRequirement quality, double requiredQuantity) {
    JobOrderItemMaterial im = new JobOrderItemMaterial();
    im.setMaterial(mat);
    im.setQualityRequirement(quality);
    im.setRequiredQuantity(requiredQuantity);
    return im;
  }

  private MaterialClaim claim(
      JobOrder order, QualityRequirement quality, OrgUnit claimingOrgUnit, double amount) {
    MaterialClaim claim = new MaterialClaim();
    claim.setJobOrder(order);
    claim.setMaterial(material);
    claim.setQualityRequirement(quality);
    claim.setClaimingOrgUnit(claimingOrgUnit);
    claim.setAmount(amount);
    return claim;
  }
}
