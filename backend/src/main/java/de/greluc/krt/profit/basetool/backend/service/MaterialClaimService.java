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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateClaimDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages material claims ("Eintragungen") — the way profit squadrons sign up for partial
 * quantities of a material bucket on a public Spezialkommando job order (Job-Order rework #340,
 * Phase 4 / #344).
 *
 * <p>A claim is keyed on the aggregated bucket {@code (jobOrder, material, qualityRequirement)} so
 * the same flow serves both order kinds (a {@code MATERIAL} order buckets {@link JobOrderMaterial}
 * by {@code minQuality}, an {@code ITEM} order sums {@link JobOrderItemMaterial} per quality). The
 * service enforces every invariant the data layer cannot: claims live only on SK orders, the sum
 * across squadrons never exceeds the bucket's required amount (no overclaim), and a squadron holds
 * at most one claim per bucket (a repeat post updates rather than duplicates). The no-overclaim
 * invariant is a cross-row aggregate, so it is made concurrency-safe with a pessimistic lock on the
 * order (the claims' aggregate root) taken before the already-claimed sum is read — see {@link
 * #upsertClaim} (REQ-ORDERS-024, ADR-0092).
 *
 * <p>Claims are an independent aggregate — there is no mapped collection on {@link JobOrder}, so
 * the reconciliation hooks ({@link #withdrawAllForOrderWithinTransaction} on SK→Squadron
 * de-escalation, {@link #withdrawOrphanedClaimsWithinTransaction} on a bucket removal) delete rows
 * through the repository without ever bumping the parent order's {@code @Version}. This sidesteps
 * the optimistic-locking traps documented in CLAUDE.md: a withdrawal runs inside the order's edit
 * transaction but touches only {@link MaterialClaim} rows, which the order's managed graph never
 * holds.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MaterialClaimService {

  private final MaterialClaimRepository materialClaimRepository;
  private final JobOrderRepository jobOrderRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final UserRepository userRepository;
  private final AuthHelperService authHelperService;
  private final OwnerScopeService ownerScopeService;
  private final AuditService auditService;
  private final MaterialMapper materialMapper;
  private final SquadronMapper squadronMapper;

  /**
   * Self-reference used to invoke {@link #upsertClaimWithinTransaction} through the Spring proxy so
   * each retry of {@link #upsertClaim} opens its OWN {@code REQUIRES_NEW} transaction. A direct
   * {@code this} call would be self-invocation, skip the proxy and run every attempt in one
   * transaction — fatal here, because a losing writer's {@link DataIntegrityViolationException} /
   * {@link ObjectOptimisticLockingFailureException} poisons the whole transaction (Postgres marks
   * it aborted), so no same-transaction retry could ever commit. An {@link ObjectProvider} defers
   * the lookup, avoiding an eager self-injection cycle at construction. Mirrors {@code
   * OperationPayoutService.setPayoutStatus} (#1111).
   */
  private final ObjectProvider<MaterialClaimService> self;

  /**
   * Total attempts (one initial + retries) {@link #upsertClaim} makes against a concurrent
   * same-bucket writer before giving up and surfacing the conflict as a 409. Five gives ample
   * head-room for the realistic case — a handful of a squadron's logisticians lodging the <em>first
   * </em> claim on one bucket within the same instant: the round-one INSERT losers retry, find the
   * winner's committed row and UPDATE it in place (last-writer-wins), so a genuine conflict only
   * ever survives a pathological, never-winning race.
   */
  private static final int MAX_UPSERT_ATTEMPTS = 5;

  /**
   * Identity of one aggregated material bucket — a material at a single quality level. Shared key
   * type for the required-amount aggregation and the per-bucket claim grouping.
   *
   * @param materialId the material.
   * @param quality the quality bucket.
   */
  private record Bucket(UUID materialId, QualityRequirement quality) {}

  /**
   * Returns the full claim view of an order: one {@link ClaimBucketDto} per required material
   * bucket, carrying the required amount, the collectively-claimed amount, the open remainder and
   * the individual per-squadron claims. Visibility is gated at the controller via {@code
   * canSeeJobOrder}; a non-SK order simply has no buckets eligible for claiming, but the read still
   * returns its required buckets with empty claim lists for a uniform UI shell.
   *
   * @param jobOrderId the order to inspect.
   * @return the per-bucket claim view, never {@code null}.
   * @throws NotFoundException when the order does not exist.
   */
  public List<ClaimBucketDto> getClaimBuckets(@NotNull UUID jobOrderId) {
    return getClaimBucketsForOrder(loadOrder(jobOrderId));
  }

  /**
   * Order-taking variant of {@link #getClaimBuckets(UUID)} for callers that already hold a managed
   * {@link JobOrder} (e.g. {@code JobOrderService.mapToDtoWithStock} enriching the detail DTO with
   * claims, Phase 5 / #345) — avoids the redundant {@code findById} reload.
   *
   * @param order the managed order whose buckets + claims to project.
   * @return the per-bucket claim view, never {@code null}.
   */
  public List<ClaimBucketDto> getClaimBucketsForOrder(@NotNull JobOrder order) {
    return getClaimBucketsForOrder(
        order, materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(order.getId()));
  }

  /**
   * Batched-list variant of {@link #getClaimBucketsForOrder(JobOrder)} that takes the order's
   * claims pre-loaded by the caller (newest-first), so the paged job-order list can fetch every SK
   * order's claims in one {@code findByJobOrderIdInOrderByCreatedAtDesc} query and avoid the
   * per-order claim query (REQ-DATA-003). The {@code claims} must be exactly this order's claims;
   * the bucketing, required-amount and open-remaining computation are identical to the single-order
   * path.
   *
   * @param order the managed order whose buckets to project.
   * @param orderClaims this order's claims, newest-first; may be empty.
   * @return the per-bucket claim view, never {@code null}.
   */
  public List<ClaimBucketDto> getClaimBucketsForOrder(
      @NotNull JobOrder order, @NotNull List<MaterialClaim> orderClaims) {
    Map<Bucket, Double> required = requiredByBucket(order);
    Map<UUID, Material> materials = materialsByBucket(order);

    Map<Bucket, List<MaterialClaim>> claimsByBucket = new LinkedHashMap<>();
    for (MaterialClaim claim : orderClaims) {
      claimsByBucket
          .computeIfAbsent(
              new Bucket(claim.getMaterial().getId(), claim.getQualityRequirement()),
              k -> new ArrayList<>())
          .add(claim);
    }

    List<ClaimBucketDto> buckets = new ArrayList<>();
    for (Map.Entry<Bucket, Double> entry : required.entrySet()) {
      Bucket bucket = entry.getKey();
      double requiredAmount = round3(entry.getValue());
      List<MaterialClaim> claims = claimsByBucket.getOrDefault(bucket, List.of());
      double claimedAmount = round3(claims.stream().mapToDouble(MaterialClaim::getAmount).sum());
      double openRemaining = round3(Math.max(0.0, requiredAmount - claimedAmount));
      buckets.add(
          new ClaimBucketDto(
              materialMapper.toDto(materials.get(bucket.materialId())),
              bucket.quality(),
              requiredAmount,
              claimedAmount,
              openRemaining,
              claims.stream().map(this::toClaimDto).toList()));
    }
    return buckets;
  }

  /**
   * Batch variant of {@link #getClaimBucketsForOrder(JobOrder)} for the paged job-order list: loads
   * the claims of all given orders in a single query and returns the per-order bucket view keyed by
   * order id, so the list path issues one claim query instead of one per SK order (REQ-DATA-003).
   * Pass only the orders that can carry claims (SK-responsible); other orders need no entry (their
   * claim view is empty by construction).
   *
   * @param orders the orders whose claim views to project; an empty collection yields an empty map.
   * @return order id → its per-bucket claim view; orders with no claims still get their required
   *     buckets with empty claim lists.
   */
  public Map<UUID, List<ClaimBucketDto>> getClaimBucketsForOrders(
      @NotNull Collection<JobOrder> orders) {
    if (orders.isEmpty()) {
      return Map.of();
    }
    List<UUID> orderIds = orders.stream().map(JobOrder::getId).toList();
    Map<UUID, List<MaterialClaim>> claimsByOrder =
        materialClaimRepository.findByJobOrderIdInOrderByCreatedAtDesc(orderIds).stream()
            .collect(Collectors.groupingBy(claim -> claim.getJobOrder().getId()));
    Map<UUID, List<ClaimBucketDto>> result = new HashMap<>();
    for (JobOrder order : orders) {
      result.put(
          order.getId(),
          getClaimBucketsForOrder(order, claimsByOrder.getOrDefault(order.getId(), List.of())));
    }
    return result;
  }

  /**
   * Creates or updates a squadron's claim on a bucket (upsert keyed on {@code (bucket, squadron)}).
   * Enforces every invariant: the order must be a non-terminal SK order, the caller must be allowed
   * to act for the claiming squadron, the bucket must exist on the order, the new amount must not
   * push the bucket's total claims past its required amount, and — on a new claim — the claiming
   * squadron must itself be profit-eligible (only Profit-side squadrons take part in the order
   * workflow).
   *
   * <p><b>No cross-squadron overclaim under concurrency.</b> The no-overclaim guard sums the
   * bucket's claims across <em>all</em> squadrons — a cross-row aggregate the unique index cannot
   * protect. Two DIFFERENT squadrons racing their first claim on one bucket would each read a zero
   * already-claimed sum under {@code READ COMMITTED} (the other's uncommitted INSERT is invisible)
   * and both commit past the required amount, and because {@code uq_material_claim_bucket_org_unit}
   * keys per claiming squadron the two rows never collide. {@link #upsertClaimWithinTransaction}
   * therefore takes a {@code PESSIMISTIC_WRITE} row lock on the order — the claims' aggregate root,
   * via {@code JobOrderRepository.lockForClaimUpsert} — before reading that sum: claimants of one
   * order serialise, so the loser reads the winner's committed claim and its guard rejects the
   * overclaim (REQ-ORDERS-024, ADR-0092).
   *
   * <p><b>Last-writer-wins under concurrency, for real.</b> The {@code material_claim} row carries
   * a JPA {@code @Version} (via {@code AbstractEntity}) <em>and</em> a unique index {@code
   * uq_material_claim_bucket_org_unit} on {@code (job_order_id, material_id, quality_requirement,
   * claiming_org_unit_id)} (V131), so two logisticians of the same squadron lodging the
   * <em>first</em> claim on one bucket at once do NOT both succeed: one loses the INSERT (unique
   * constraint → {@link DataIntegrityViolationException}) or, on a repeat edit, the UPDATE
   * ({@code @Version} → {@link ObjectOptimisticLockingFailureException}) race and its transaction
   * is poisoned. This method is therefore a <b>non-transactional orchestrator</b> ({@code
   * NOT_SUPPORTED}) that runs each attempt in its own {@code REQUIRES_NEW} transaction (via {@link
   * #self}) and retries up to {@link #MAX_UPSERT_ATTEMPTS} times on either exception: by the retry
   * the winner has committed the row, so the loser reloads it and UPDATEs in place. Unlike a
   * boolean toggle the {@code @Version} is kept and echoed to the client through {@link ClaimDto} —
   * a genuine concurrent same-squadron edit that outlasts the retry bound still surfaces a truthful
   * 409, never a 500 (found in the optimistic-lock audit for #1186, pre-existing on the claim
   * sign-up path). Now that the pessimistic order lock above serialises an order's claim writers,
   * this retry is a defense-in-depth backstop rather than the primary guard — it still covers a
   * same-row race the order lock does not serialise, e.g. an upsert whose row is deleted by a
   * concurrent {@link #withdrawClaim} between the find-or-create and the save.
   *
   * @param jobOrderId the order.
   * @param dto the claim payload.
   * @return the persisted claim.
   * @throws NotFoundException when the order or material is unknown.
   * @throws BadRequestException when the order is not an open SK order, the bucket does not exist,
   *     the amount would overclaim, or a new claim names a non-profit-eligible squadron.
   * @throws AccessDeniedException when the caller may not act for the claiming squadron.
   * @throws ObjectOptimisticLockingFailureException only if every attempt loses the race — a
   *     persistent hot-bucket contention that still maps to a 409, never a 500.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ClaimDto upsertClaim(@NotNull UUID jobOrderId, @NotNull CreateClaimDto dto) {
    // Retry the upsert across FRESH transactions. Each attempt is REQUIRES_NEW (see self): a losing
    // first-claim writer's unique-constraint / @Version violation poisons its own transaction, so
    // the
    // only correct retry is a brand-new one. All but the final attempt swallow the race and loop;
    // the
    // final attempt lets a persistent race propagate so it maps to a truthful 409, not a pretend
    // success.
    for (int attempt = 1; attempt < MAX_UPSERT_ATTEMPTS; attempt++) {
      try {
        return self.getObject().upsertClaimWithinTransaction(jobOrderId, dto);
      } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException race) {
        log.debug(
            "Concurrent material-claim upsert race (attempt {}/{}) for order {} material {} quality"
                + " {} claimingOrgUnit {} — retrying",
            attempt,
            MAX_UPSERT_ATTEMPTS,
            jobOrderId,
            dto.materialId(),
            dto.qualityRequirement(),
            dto.claimingOrgUnitId());
      }
    }
    return self.getObject().upsertClaimWithinTransaction(jobOrderId, dto);
  }

  /**
   * Performs one attempt of the claim upsert inside its own {@code REQUIRES_NEW} transaction — the
   * validate + find-or-create + save + audit body that {@link #upsertClaim} retries. Kept separate
   * (and invoked through {@link #self}) precisely so a unique-constraint / {@code @Version}
   * violation rolls back only this attempt's transaction and the orchestrator can retry in a clean
   * one. Must never be called directly by application code — go through {@link #upsertClaim}.
   *
   * @param jobOrderId the order.
   * @param dto the claim payload.
   * @return the persisted claim.
   * @throws NotFoundException when the order or material is unknown.
   * @throws BadRequestException when the order is not an open SK order, the bucket does not exist,
   *     the amount would overclaim, or a new claim names a non-profit-eligible squadron.
   * @throws AccessDeniedException when the caller may not act for the claiming squadron.
   * @throws DataIntegrityViolationException when a concurrent writer already inserted the row.
   * @throws ObjectOptimisticLockingFailureException when a concurrent writer already updated the
   *     row.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ClaimDto upsertClaimWithinTransaction(
      @NotNull UUID jobOrderId, @NotNull CreateClaimDto dto) {
    JobOrder order = loadOrder(jobOrderId);
    assertClaimable(order);
    assertCanManage(order, dto.claimingOrgUnitId());

    Bucket bucket = new Bucket(dto.materialId(), dto.qualityRequirement());
    Map<Bucket, Double> required = requiredByBucket(order);
    Double requiredAmount = required.get(bucket);
    if (requiredAmount == null) {
      throw new BadRequestException(
          "No such material bucket on this order: material="
              + dto.materialId()
              + " quality="
              + dto.qualityRequirement());
    }

    // Serialise every claim upsert on this order on its aggregate-root (job_order) row BEFORE
    // summing the bucket's existing claims (REQ-ORDERS-024, ADR-0092). Two DIFFERENT squadrons
    // racing their first claim on the same bucket would otherwise each read a zero already-claimed
    // sum under READ COMMITTED (the other's uncommitted INSERT is invisible), both pass the guard
    // below and both commit — and because uq_material_claim_bucket_org_unit keys per claiming
    // squadron it never collides across squadrons, so the REQUIRES_NEW retry cannot catch that
    // cross-squadron overclaim. The row lock releases at this attempt's commit/rollback.
    jobOrderRepository.lockForClaimUpsert(jobOrderId);

    double amount = dto.amount();
    double claimedByOthers =
        materialClaimRepository
            .findByJobOrderIdAndMaterialIdAndQualityRequirement(
                jobOrderId, dto.materialId(), dto.qualityRequirement())
            .stream()
            .filter(c -> !c.getClaimingOrgUnit().getId().equals(dto.claimingOrgUnitId()))
            .mapToDouble(MaterialClaim::getAmount)
            .sum();
    if (round3(claimedByOthers + amount) > round3(requiredAmount)) {
      throw new BadRequestException(
          "Overclaim: requested "
              + amount
              + " plus already-claimed "
              + round3(claimedByOthers)
              + " exceeds the required "
              + round3(requiredAmount)
              + " for this bucket.");
    }

    MaterialClaim claim =
        materialClaimRepository
            .findByJobOrderIdAndMaterialIdAndQualityRequirementAndClaimingOrgUnitId(
                jobOrderId, dto.materialId(), dto.qualityRequirement(), dto.claimingOrgUnitId())
            .orElseGet(
                () -> {
                  MaterialClaim fresh = new MaterialClaim();
                  fresh.setJobOrder(order);
                  fresh.setMaterial(resolveMaterial(order, dto.materialId()));
                  fresh.setQualityRequirement(dto.qualityRequirement());
                  fresh.setClaimingOrgUnit(resolveClaimingOrgUnit(dto.claimingOrgUnitId()));
                  return fresh;
                });
    claim.setAmount(amount);
    authHelperService
        .currentUserId()
        .flatMap(userRepository::findById)
        .ifPresent(claim::setClaimedByUser);

    boolean isNew = claim.getId() == null;
    MaterialClaim saved = materialClaimRepository.save(claim);
    // Audit (Phase 7, #347): identifiers + amount only — never names/emails. The request-scoped MDC
    // (correlationId / userId / orgUnitId) is attached by CorrelationIdFilter.
    log.info(
        "Material claim upserted: order={} material={} quality={} claimingOrgUnit={} amount={}",
        order.getId(),
        dto.materialId(),
        dto.qualityRequirement(),
        dto.claimingOrgUnitId(),
        amount);
    auditService.record(
        AuditEventType.JOB_ORDER_CLAIM_UPSERTED,
        order.getId(),
        orderLabel(order),
        null,
        AuditDetails.of("claim", saved.getId())
            .with("material", dto.materialId())
            .with("quality", dto.qualityRequirement())
            .with("claimingOrgUnit", dto.claimingOrgUnitId())
            .with("amount", amount)
            .with("mode", isNew ? "created" : "updated"));
    return toClaimDto(saved);
  }

  /**
   * Withdraws a single claim. Same gates as {@link #upsertClaim}: the order must be a non-terminal
   * SK order and the caller must be allowed to act for the claim's squadron.
   *
   * @param jobOrderId the order the claim belongs to.
   * @param claimId the claim to withdraw.
   * @throws NotFoundException when the order or claim is unknown, or the claim is on another order.
   * @throws BadRequestException when the order is terminal or no longer an SK order.
   * @throws AccessDeniedException when the caller may not act for the claim's squadron.
   */
  @Transactional
  public void withdrawClaim(@NotNull UUID jobOrderId, @NotNull UUID claimId) {
    JobOrder order = loadOrder(jobOrderId);
    assertClaimable(order);
    MaterialClaim claim =
        materialClaimRepository
            .findById(claimId)
            .orElseThrow(() -> new NotFoundException("MaterialClaim not found: " + claimId));
    if (!claim.getJobOrder().getId().equals(jobOrderId)) {
      throw new NotFoundException(
          "MaterialClaim " + claimId + " does not belong to order " + jobOrderId);
    }
    assertCanManage(order, claim.getClaimingOrgUnit().getId());
    final UUID claimMaterialId = claim.getMaterial().getId();
    final QualityRequirement claimQuality = claim.getQualityRequirement();
    final UUID claimingOrgUnitId = claim.getClaimingOrgUnit().getId();
    materialClaimRepository.delete(claim);
    auditService.record(
        AuditEventType.JOB_ORDER_CLAIM_WITHDRAWN,
        order.getId(),
        orderLabel(order),
        null,
        AuditDetails.of("claim", claimId)
            .with("material", claimMaterialId)
            .with("quality", claimQuality)
            .with("claimingOrgUnit", claimingOrgUnitId));
    log.info(
        "Material claim withdrawn: order={} claim={} material={} quality={} claimingOrgUnit={}",
        jobOrderId,
        claimId,
        claim.getMaterial().getId(),
        claim.getQualityRequirement(),
        claim.getClaimingOrgUnit().getId());
  }

  /**
   * Reconciliation hook (decision #10): withdraws every claim on an order, used when a Phase-2
   * reassignment de-escalates the order from an SK back to a squadron — the order becomes private,
   * so its public claims are dropped. Runs inside the reassignment transaction on the
   * already-managed order; deletes through the repository so the order's {@code @Version} is never
   * touched.
   *
   * @param order the managed order whose claims are being withdrawn.
   * @return the number of claims withdrawn (0 when the order had none); folded into the parent
   *     reassignment's audit event by the caller.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int withdrawAllForOrderWithinTransaction(@NotNull JobOrder order) {
    List<MaterialClaim> claims =
        materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(order.getId());
    if (!claims.isEmpty()) {
      materialClaimRepository.deleteAll(claims);
      log.info(
          "Withdrew all {} material claim(s) on order {} (SK→squadron de-escalation)",
          claims.size(),
          order.getId());
    }
    return claims.size();
  }

  /**
   * Reconciliation hook (decision #6): withdraws claims whose bucket no longer exists on the order,
   * used after an order edit removes a bucket. The bucket computation ({@link #requiredByBucket})
   * is kind-agnostic, so this withdraws orphans for both a {@code MATERIAL} order whose material
   * line was removed (the live path, wired into {@code JobOrderService.updateJobOrder}) and an
   * {@code ITEM} order whose derived buckets changed. ITEM orders are immutable after creation
   * today (no edit endpoint — the detail UI gates editing to {@code type != 'ITEM'}), so the ITEM
   * branch is dormant but ready for a future item-edit path; it is covered by unit tests against an
   * ITEM-typed order so it cannot rot.
   *
   * <p>Runs inside the edit transaction and deletes through the repository so the order's
   * {@code @Version} is never touched — claims are an independent aggregate, which is what keeps
   * the bulk withdrawal free of the optimistic-locking traps in CLAUDE.md.
   *
   * @param order the managed order whose buckets define which claims survive.
   * @return the number of orphaned claims withdrawn (0 when none); folded into the parent edit's
   *     audit event by the caller.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int withdrawOrphanedClaimsWithinTransaction(@NotNull JobOrder order) {
    Map<Bucket, Double> required = requiredByBucket(order);
    List<MaterialClaim> orphaned =
        materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(order.getId()).stream()
            .filter(
                c ->
                    !required.containsKey(
                        new Bucket(c.getMaterial().getId(), c.getQualityRequirement())))
            .toList();
    if (!orphaned.isEmpty()) {
      materialClaimRepository.deleteAll(orphaned);
      log.info(
          "Withdrew {} orphaned material claim(s) on order {} after a bucket was removed",
          orphaned.size(),
          order.getId());
    }
    return orphaned.size();
  }

  /**
   * Computes the required amount per material bucket for either order kind: an {@code ITEM} order
   * sums each {@link JobOrderItemMaterial#getRequiredQuantity()} per {@code (material, quality)}; a
   * {@code MATERIAL} order sums each {@link JobOrderMaterial#getAmount()} with the bucket derived
   * from {@code minQuality} ({@code GOOD} when a 650-floor is set, {@code NONE} otherwise).
   *
   * @param order the order.
   * @return required amount keyed by bucket, insertion-ordered for stable rendering.
   */
  private Map<Bucket, Double> requiredByBucket(JobOrder order) {
    Map<Bucket, Double> required = new LinkedHashMap<>();
    if (order.getType() == JobOrderType.ITEM) {
      for (JobOrderItem item : order.getItems()) {
        for (JobOrderItemMaterial req : item.getMaterials()) {
          required.merge(
              new Bucket(req.getMaterial().getId(), req.getQualityRequirement()),
              req.getRequiredQuantity() == null ? 0.0 : req.getRequiredQuantity(),
              Double::sum);
        }
      }
    } else {
      for (JobOrderMaterial mat : order.getMaterials()) {
        QualityRequirement quality =
            mat.getMinQuality() != null ? QualityRequirement.GOOD : QualityRequirement.NONE;
        required.merge(
            new Bucket(mat.getMaterial().getId(), quality),
            mat.getAmount() == null ? 0.0 : mat.getAmount(),
            Double::sum);
      }
    }
    return required;
  }

  /**
   * Builds a material lookup for the buckets of an order so the bucket DTO can carry the full
   * {@link de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto} without a second query per
   * row.
   *
   * @param order the order.
   * @return material id → material, for every material referenced by a bucket.
   */
  private Map<UUID, Material> materialsByBucket(JobOrder order) {
    Map<UUID, Material> materials = new LinkedHashMap<>();
    if (order.getType() == JobOrderType.ITEM) {
      for (JobOrderItem item : order.getItems()) {
        for (JobOrderItemMaterial req : item.getMaterials()) {
          materials.putIfAbsent(req.getMaterial().getId(), req.getMaterial());
        }
      }
    } else {
      for (JobOrderMaterial mat : order.getMaterials()) {
        materials.putIfAbsent(mat.getMaterial().getId(), mat.getMaterial());
      }
    }
    return materials;
  }

  /**
   * Asserts the order accepts claim mutations: it must be responsible to a Spezialkommando (claims
   * are public-SK-only) and not in a terminal status (terminal freezes claims read-only for
   * history, decision #6).
   *
   * @param order the order.
   * @throws BadRequestException when the order is not a claimable SK order.
   */
  private void assertClaimable(JobOrder order) {
    OrgUnit responsible = order.getResponsibleOrgUnit();
    if (responsible == null || responsible.getKind() != OrgUnitKind.SPECIAL_COMMAND) {
      throw new BadRequestException(
          "Material claims are only allowed on Spezialkommando orders; order "
              + order.getId()
              + " is not responsible to an SK.");
    }
    if (order.getStatus() == JobOrderStatus.COMPLETED
        || order.getStatus() == JobOrderStatus.REJECTED) {
      throw new BadRequestException(
          "Order " + order.getId() + " is in a terminal status; its claims are frozen.");
    }
  }

  /**
   * Enforces the claim permission matrix (decision #8): an admin, or a logistician of the
   * <em>responsible</em> SK, may manage <b>any</b> claim on that order; a squadron's
   * logistician/officer may manage only claims for their <b>own</b> squadron ({@link
   * AuthHelperService#canEditOrgUnit}). The SK-logistician check is the single contextual {@code
   * LOGISTICIAN@skId} authority — and because an SK <b>lead</b> ({@code is_lead}) is automatically
   * a logistician of its SK (granted that contextual authority by {@code
   * CustomJwtGrantedAuthoritiesConverter}, mirroring how admin/officer outrank the role below
   * them), this one check covers both the SK's logisticians and its leads/officers. The bare {@code
   * hasRole('LOGISTICIAN')} controller gate has already filtered out anyone below logistician.
   *
   * @param order the order whose responsible SK defines the elevated authority.
   * @param claimingOrgUnitId the squadron the claim is for.
   * @throws AccessDeniedException when the caller is neither a logistician/lead of the responsible
   *     SK nor allowed to act for this squadron.
   */
  private void assertCanManage(JobOrder order, UUID claimingOrgUnitId) {
    if (authHelperService.isAdmin()) {
      return;
    }
    UUID responsibleSkId = order.getResponsibleOrgUnit().getId();
    boolean managesResponsibleSk =
        ownerScopeService.hasRoleInOrgUnit(responsibleSkId, "LOGISTICIAN");
    boolean managesOwnSquadron = authHelperService.canEditOrgUnit(claimingOrgUnitId);
    if (!managesResponsibleSk && !managesOwnSquadron) {
      throw new AccessDeniedException(
          "You may only manage claims for your own squadron, or any claim as a logistician or lead"
              + " of the responsible Spezialkommando.");
    }
  }

  /**
   * Loads an order with its materials/items eager-fetched (via the repository's detail graph) so
   * the bucket aggregation does not lazy-load row by row.
   *
   * @param jobOrderId the order id.
   * @return the managed order.
   * @throws NotFoundException when the order does not exist.
   */
  private JobOrder loadOrder(UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .orElseThrow(() -> new NotFoundException("JobOrder not found: " + jobOrderId));
  }

  /**
   * Resolves a material referenced by a create payload, requiring it to actually be a bucket on the
   * order (the bucket existence is re-checked by the caller via {@code requiredByBucket}; this only
   * loads the managed entity).
   *
   * @param order the order the claim is on.
   * @param materialId the material id.
   * @return the managed material instance from the order's buckets.
   * @throws BadRequestException when the material is not part of the order.
   */
  private Material resolveMaterial(JobOrder order, UUID materialId) {
    Material material = materialsByBucket(order).get(materialId);
    if (material == null) {
      throw new BadRequestException(
          "Material " + materialId + " is not part of order " + order.getId());
    }
    return material;
  }

  /**
   * Resolves the claiming org unit and validates it may sign up for material: it must be a squadron
   * (Spezialkommandos place orders, they never claim against them) <em>and</em> that squadron must
   * be profit-eligible. A claim models a profit squadron volunteering to deliver part of an SK
   * order, so a squadron an admin has not marked {@code isProfitEligible} is outside the order
   * workflow — the claim modal's squadron picker is already filtered to the profit-eligible subset,
   * and this check is the authoritative server-side guard behind that filter (it also blocks a
   * hand-crafted request, and an admin or responsible-SK lead from claiming on behalf of a
   * non-profit squadron). Reached only on the insert branch of {@link #upsertClaim}; an existing
   * claim's squadron is not re-resolved, so a squadron that loses eligibility may still adjust or
   * withdraw a claim it lodged while eligible.
   *
   * @param claimingOrgUnitId the org unit id from the payload.
   * @return the managed, profit-eligible squadron-kind org unit.
   * @throws BadRequestException when the id is unknown, is not a squadron, or is a squadron that is
   *     not profit-eligible.
   */
  private OrgUnit resolveClaimingOrgUnit(UUID claimingOrgUnitId) {
    OrgUnit orgUnit =
        orgUnitRepository
            .findById(claimingOrgUnitId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "claimingOrgUnitId does not resolve to a known org unit: "
                            + claimingOrgUnitId));
    if (orgUnit.getKind() != OrgUnitKind.SQUADRON) {
      throw new BadRequestException(
          "Only squadrons may claim material; " + claimingOrgUnitId + " is not a squadron.");
    }
    if (!orgUnit.isProfitEligible()) {
      throw new BadRequestException(
          "Only profit-eligible squadrons may claim material; "
              + claimingOrgUnitId
              + " is not profit-eligible.");
    }
    return orgUnit;
  }

  /**
   * Projects a persisted claim to its API DTO. Exposes the claiming squadron as a slim reference
   * and the audit user as a bare id (never a name, so no PII crosses the boundary).
   *
   * @param claim the persisted claim.
   * @return the DTO.
   */
  private ClaimDto toClaimDto(MaterialClaim claim) {
    return new ClaimDto(
        claim.getId(),
        squadronMapper.orgUnitToReferenceDto(claim.getClaimingOrgUnit()),
        claim.getAmount(),
        claim.getClaimedByUser() != null ? claim.getClaimedByUser().getId() : null,
        claim.getCreatedAt(),
        claim.getVersion());
  }

  /**
   * Rounds a quantity to the 0.001 precision the UI uses, killing the floating-point noise that
   * accumulates in summed SCU quantities (same rationale as the order-derivation rounding).
   *
   * @param value the raw quantity.
   * @return the value rounded to three decimals.
   */
  private static double round3(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }

  /**
   * Composes the audit subject label for the claim's parent order — {@code #<displayId>
   * '<handle>'}.
   *
   * @param order the parent order
   * @return the {@code #<displayId> '<handle>'} label
   */
  private static String orderLabel(JobOrder order) {
    return "#" + order.getDisplayId() + " '" + order.getHandle() + "'";
  }
}
