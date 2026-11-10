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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierMapper;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimBucketSource;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderNotices;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages independently versioned material promises and their retained contribution history.
 *
 * <p>The current SK workflow guards each order and material across every quality bucket; ending one
 * promise preserves its participation and contribution facts (REQ-ORDERS-024).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MaterialClaimService implements ClaimBucketSource {

  private static final int CLAIM_BATCH_SIZE = 200;

  private final MaterialClaimRepository materialClaimRepository;
  private final MaterialClaimAuthority claimAuthority;
  private final JobOrderParticipationRepository participations;
  private final MaterialPromiseCoordinator promiseCoordinator;
  private final MaterialPromiseCapacity materialCapacity;
  private final de.greluc.krt.profit.basetool.backend.joborder.api
          .JobOrderMaterialRequirementResolver
      materialRequirements;
  private final JobOrderRepository jobOrderRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderResponsibilityGuard responsibilityGuard;
  private final JobOrderEligibilityNotices eligibilityNotices;
  private final UserRepository userRepository;
  private final AuthHelperService authHelperService;
  private final RequestScopeResolver requestScopeResolver;
  private final MaterialMapper materialMapper;
  private final SquadronMapper squadronMapper;
  private final QualityTierMapper qualityTierMapper;

  /**
   * Proxied self-reference so each {@link #upsertClaim} attempt runs {@link
   * #upsertClaimWithinTransaction} in its own {@code REQUIRES_NEW} transaction.
   */
  private final ObjectProvider<MaterialClaimService> self;

  /** Publishes the withdrawal notices (REQ-ORDERS-044). */
  private final ApplicationEventPublisher eventPublisher;

  private final UserService userService;

  /**
   * Identity of one aggregated material bucket — a material at a single quality tier. Shared key
   * type for the required-amount aggregation and the per-bucket claim grouping.
   *
   * @param materialId the material.
   * @param tierId the quality tier.
   */
  private record Bucket(UUID materialId, UUID tierId) {}

  /**
   * Returns the claim view of an order: one {@link ClaimBucketDto} per required bucket with
   * required, claimed and open amounts and the per-squadron claims. A non-SK order yields buckets
   * with empty claim lists.
   *
   * @param jobOrderId the order to inspect.
   * @return the per-bucket claim view, never {@code null}.
   * @throws NotFoundException when the order does not exist.
   */
  @NotNull
  public List<ClaimBucketDto> getClaimBuckets(@NotNull UUID jobOrderId) {
    return getClaimBucketsForOrder(loadOrder(jobOrderId));
  }

  /**
   * Returns eligible squadrons whose claims the current caller may write on this order.
   *
   * @param jobOrderId the readable order
   * @return current claim-writer options, empty when claim writes are unavailable
   */
  @NotNull
  public List<OrgUnitMembershipOptionDto> claimingOptions(@NotNull UUID jobOrderId) {
    JobOrder order = loadOrder(jobOrderId);
    if (!authHelperService.isLogisticianOrAbove()
        || !requestScopeResolver.canViewJobOrders()
        || !claimable(order)
        || !responsibilityGuard.isValid(order)) {
      return List.of();
    }
    return orgUnitRepository.findAllById(profitHierarchy.eligibleUnitIds()).stream()
        .filter(unit -> unit.getKind() == OrgUnitKind.SQUADRON)
        .filter(unit -> canManage(order, unit.getId()))
        .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
        .map(unit ->
            new OrgUnitMembershipOptionDto(
                unit.getId(), unit.getName(), unit.getShorthand(), unit.getKind()))
        .toList();
  }

  /**
   * Variant of {@link #getClaimBuckets(UUID)} for a caller already holding the managed {@link
   * JobOrder}.
   *
   * @param order the managed order whose buckets + claims to project.
   * @return the per-bucket claim view, never {@code null}.
   */
  @Override
  @NotNull
  public List<ClaimBucketDto> getClaimBucketsForOrder(@NotNull JobOrder order) {
    return getClaimBucketsForOrder(
        order, materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(order.getId()));
  }

  /**
   * Variant of {@link #getClaimBucketsForOrder(JobOrder)} over claims the caller pre-loaded
   * (REQ-DATA-003).
   *
   * @param order the managed order whose buckets to project.
   * @param orderClaims exactly this order's claims, newest-first; may be empty.
   * @return the per-bucket claim view, never {@code null}.
   */
  @NotNull
  public List<ClaimBucketDto> getClaimBucketsForOrder(
      JobOrder order, @NotNull List<MaterialClaim> orderClaims) {
    return getClaimBucketsForOrder(order, orderClaims, materialCapacity.ofOrder(order));
  }

  private List<ClaimBucketDto> getClaimBucketsForOrder(
      JobOrder order,
      List<MaterialClaim> orderClaims,
      Map<MaterialPromiseCapacity.Bucket, MaterialPromiseCapacity.Capacity> capacities) {
    Map<Bucket, Double> required = requiredByBucket(order);
    Map<UUID, Material> materials = materialsByBucket(order);
    Map<UUID, QualityTier> tiers = tiersOf(order);

    Map<Bucket, List<MaterialClaim>> claimsByBucket = new LinkedHashMap<>();
    for (MaterialClaim claim : orderClaims) {
      claimsByBucket
          .computeIfAbsent(
              new Bucket(claim.getMaterial().getId(), claim.getQualityTier().getId()),
              _ -> new ArrayList<>())
          .add(claim);
    }

    List<ClaimBucketDto> buckets = new ArrayList<>();
    for (Map.Entry<Bucket, Double> entry : required.entrySet()) {
      Bucket bucket = entry.getKey();
      double requiredAmount = round3(entry.getValue());
      List<MaterialClaim> claims = claimsByBucket.getOrDefault(bucket, List.of());
      double claimedAmount = round3(claims.stream().mapToDouble(MaterialClaim::getAmount).sum());
      var current =
          Objects.requireNonNull(
              capacities.get(
                  new MaterialPromiseCapacity.Bucket(bucket.materialId(), bucket.tierId())));
      double openRemaining =
          round3(Math.max(0.0, current.availableQuanta() / 1000.0 - claimedAmount));
      QualityTier tier = Objects.requireNonNull(tiers.get(bucket.tierId()));
      buckets.add(
          new ClaimBucketDto(
              materialMapper.toDto(Objects.requireNonNull(materials.get(bucket.materialId()))),
              tier.getCode(),
              qualityTierMapper.toDto(tier),
              requiredAmount,
              claimedAmount,
              openRemaining,
              claims.stream().map(this::toClaimDto).toList()));
    }
    return buckets;
  }

  /**
   * Batch variant of {@link #getClaimBucketsForOrder(JobOrder)} that loads the claims of all given
   * orders in one query (REQ-DATA-003). Pass only SK-responsible orders.
   *
   * @param orders the orders whose claim views to project; an empty collection yields an empty map.
   * @return order id → its per-bucket claim view, with empty claim lists where none exist.
   */
  @Override
  @NotNull
  public Map<UUID, List<ClaimBucketDto>> getClaimBucketsForOrders(
      @NotNull Collection<JobOrder> orders) {
    if (orders.isEmpty()) {
      return Map.of();
    }
    List<UUID> orderIds = orders.stream().map(JobOrder::getId).toList();
    Map<UUID, List<MaterialClaim>> claimsByOrder =
        materialClaimRepository.findByJobOrderIdInOrderByCreatedAtDesc(orderIds).stream()
            .collect(Collectors.groupingBy(claim -> claim.getJobOrder().getId()));
    var capacities = materialCapacity.ofOrders(orders);
    Map<UUID, List<ClaimBucketDto>> result = new HashMap<>();
    for (JobOrder order : orders) {
      result.put(
          order.getId(),
          getClaimBucketsForOrder(
              order,
              claimsByOrder.getOrDefault(order.getId(), List.of()),
              Objects.requireNonNull(capacities.get(order.getId()))));
    }
    return result;
  }

  /**
   * Creates or updates a squadron's claim on a bucket, keyed on {@code (bucket, squadron)}.
   *
   * <p>The order must be a non-terminal SK order, the caller must act for the claiming squadron,
   * the bucket must exist, the total must not exceed the required amount, and a new claim's
   * squadron must be active Profit-hierarchy. Non-transactional orchestrator: each attempt runs in
   * its own transaction; meaningful edits require the version the caller observed.
   *
   * @param jobOrderId the order.
   * @param dto the claim payload.
   * @return the persisted claim.
   * @throws NotFoundException when the order or material is unknown.
   * @throws BadRequestException when the order is not an open SK order, the bucket does not exist,
   *     the amount would overclaim, or a new claim names a outside-Profit squadron.
   * @throws AccessDeniedException when the caller may not act for the claiming squadron.
   * @throws ObjectOptimisticLockingFailureException when an existing promise version is missing or
   *     stale.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ClaimDto upsertClaim(@NotNull UUID jobOrderId, @NotNull CreateClaimDto dto) {
    return self.getObject().upsertClaimWithinTransaction(jobOrderId, dto);
  }

  /**
   * Performs one claim-upsert attempt in its own {@code REQUIRES_NEW} transaction, locking the
   * order before summing its claims. Called only through {@link #upsertClaim}.
   *
   * @param jobOrderId the order.
   * @param dto the claim payload.
   * @return the persisted claim.
   * @throws NotFoundException when the order or material is unknown.
   * @throws BadRequestException when the order is not an open SK order, the bucket does not exist,
   *     the amount would overclaim, or a new claim names a outside-Profit squadron.
   * @throws AccessDeniedException when the caller may not act for the claiming squadron.
   * @throws DataIntegrityViolationException when a concurrent writer already inserted the row.
   * @throws ObjectOptimisticLockingFailureException when a concurrent writer already updated the
   *     row.
   */
  @NotNull
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ClaimDto upsertClaimWithinTransaction(
      @NotNull UUID jobOrderId, @NotNull CreateClaimDto dto) {
    promiseCoordinator.lock(
        List.of(
            new de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope(
                jobOrderId, dto.materialId())));
    JobOrder order = loadOrder(jobOrderId);
    responsibilityGuard.assertWritable(order);
    assertClaimable(order);
    final OrgUnit claimingUnit = resolveClaimingOrgUnit(dto.claimingOrgUnitId());
    assertCanManage(order, dto.claimingOrgUnitId());

    QualityTier tier = resolveBucketTier(order, dto);
    Bucket bucket = new Bucket(dto.materialId(), tier.getId());
    Map<Bucket, Double> required = requiredByBucket(order);
    Double requiredAmount = required.get(bucket);
    if (requiredAmount == null) {
      throw new BadRequestException(
          "No such material bucket on this order: material="
              + dto.materialId()
              + " quality="
              + tier.getCode());
    }

    var existingClaim =
        materialClaimRepository.findByJobOrderIdAndMaterialIdAndQualityTierIdAndClaimingOrgUnitId(
            jobOrderId, dto.materialId(), tier.getId(), dto.claimingOrgUnitId());
    de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock.check(
        existingClaim.map(MaterialClaim::getVersion).orElse(dto.version()),
        existingClaim.isPresent() ? dto.version() : null,
        MaterialClaim.class,
        existingClaim.map(MaterialClaim::getId).orElse(null));
    var bucketCapacity =
        Objects.requireNonNull(
            materialCapacity
                .ofOrder(order)
                .get(new MaterialPromiseCapacity.Bucket(dto.materialId(), tier.getId())));
    requiredAmount = bucketCapacity.availableQuanta() / 1000.0;

    double amount = dto.amount();
    double claimedByOthers =
        materialClaimRepository
            .findByJobOrderIdAndMaterialIdAndQualityTierId(
                jobOrderId, dto.materialId(), tier.getId())
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
        existingClaim.orElseGet(() -> {
          MaterialClaim fresh = new MaterialClaim();
          fresh.setJobOrder(order);
          fresh.setMaterial(resolveMaterial(order, dto.materialId()));
          fresh.setQualityTier(tier);
          fresh.setClaimingOrgUnit(claimingUnit);
          return fresh;
        });
    de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock.check(
        claim.getId() == null ? dto.version() : claim.getVersion(),
        claim.getId() == null ? null : dto.version(),
        MaterialClaim.class,
        claim.getId());
    promiseCoordinator.lockParticipation(jobOrderId, claimingUnit.getId());
    var participation =
        participations
            .findByJobOrderIdAndClaimingOrgUnitId(jobOrderId, claimingUnit.getId())
            .orElseGet(() -> {
              var fresh =
                  new de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderParticipation();
              fresh.setJobOrder(order);
              fresh.setClaimingOrgUnit(claimingUnit);
              return fresh;
            });
    participation.setActive(true);
    claim.setParticipation(participations.save(participation));
    double previousAmount = claim.getAmount() == null ? 0.0 : claim.getAmount();
    claim.setAmount(amount);
    authHelperService
        .currentUserId()
        .flatMap(userRepository::findById)
        .ifPresent(claim::setClaimedByUser);

    MaterialClaim saved = materialClaimRepository.save(claim);
    promiseCoordinator.record(
        saved,
        de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange.Reason
            .PROMISE_CHANGED,
        previousAmount,
        0.0);
    materialClaimRepository.flush();
    log.info(
        "Material claim upserted: order={} material={} quality={} claimingOrgUnit={} amount={}",
        order.getId(),
        dto.materialId(),
        tier.getCode(),
        dto.claimingOrgUnitId(),
        amount);
    return toClaimDto(saved);
  }

  /**
   * Ends one material promise under its observed version and current SK or own-Staffel authority.
   *
   * @param jobOrderId the order whose contribution is being withdrawn
   * @param claimId the retained contribution identity
   * @param version the contribution version observed by the caller
   * @throws NotFoundException when the order or its contribution is unknown
   * @throws BadRequestException when the order is terminal or no longer an SK order
   * @throws AccessDeniedException when the caller cannot manage this Staffel's promise
   * @throws ObjectOptimisticLockingFailureException when the observed version is missing or stale
   */
  @Transactional
  public void withdrawClaim(
      @NotNull UUID jobOrderId,
      @NotNull UUID claimId,
      @org.jetbrains.annotations.Nullable Long version) {
    UUID materialId =
        Entities.require(
            materialClaimRepository.materialIdOf(jobOrderId, claimId),
            () -> "MaterialClaim not found: " + claimId);
    promiseCoordinator.lock(
        List.of(
            new de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope(
                jobOrderId, materialId)));
    JobOrder order = loadOrder(jobOrderId);
    responsibilityGuard.assertWritable(order);
    assertClaimable(order);
    MaterialClaim claim =
        Entities.require(
            materialClaimRepository.findHistoricalById(claimId),
            () -> "MaterialClaim not found: " + claimId);
    assertCanManage(order, claim.getClaimingOrgUnit().getId());
    de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock.check(
        claim.getVersion(), version, MaterialClaim.class, claimId);
    promiseCoordinator.end(
        claim,
        de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange.Reason
            .PROMISE_WITHDRAWN);
  }

  /**
   * Ends active promises whose claiming Staffel no longer belongs to active Profit.
   *
   * @param affectedUnitIds the changed organisational units
   * @return the number of promises ended with their contribution history preserved
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int endIneligibleClaimsWithinTransaction(@NotNull Set<UUID> affectedUnitIds) {
    Set<UUID> eligibleIds = profitHierarchy.eligibleUnitIds();
    Set<UUID> ineligibleIds =
        affectedUnitIds.stream()
            .filter(id -> !eligibleIds.contains(id))
            .collect(Collectors.toUnmodifiableSet());
    if (ineligibleIds.isEmpty()) {
      return 0;
    }
    return endIneligibleClaims(() ->
        materialClaimRepository.findByClaimingOrgUnitIdInOrderById(
            ineligibleIds, PageRequest.of(0, CLAIM_BATCH_SIZE)));
  }

  /**
   * Reconciles one existing order while grouping its ended promises across all claim pages.
   *
   * @param orderId the order inspected during initial reconciliation
   * @return the number of promises ended with their historical facts preserved
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int endIneligibleClaimsForOrderWithinTransaction(@NotNull UUID orderId) {
    Set<UUID> eligibleIds = profitHierarchy.eligibleUnitIds();
    return endIneligibleClaims(() ->
        materialClaimRepository.findIneligibleActivePromisesByOrder(
            orderId, eligibleIds, PageRequest.of(0, CLAIM_BATCH_SIZE)));
  }

  private int endIneligibleClaims(@NotNull Supplier<List<MaterialClaim>> nextBatch) {
    int endedCount = 0;
    Map<JobOrder, Map<UUID, Set<String>>> affected = new LinkedHashMap<>();
    while (true) {
      List<MaterialClaim> batch = nextBatch.get();
      if (batch.isEmpty()) {
        break;
      }
      for (MaterialClaim claim : batch) {
        promiseCoordinator.end(
            claim,
            de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange.Reason
                .UNIT_INELIGIBLE);
        claim.getParticipation().setActive(false);
        affected
            .computeIfAbsent(claim.getJobOrder(), _ -> new LinkedHashMap<>())
            .computeIfAbsent(
                claim.getClaimingOrgUnit().getId(), _ -> new java.util.LinkedHashSet<>())
            .add(claim.getMaterial().getName());
      }
      materialClaimRepository.flush();
      endedCount += batch.size();
    }
    ActorRef actor = userService.currentActor();
    affected.forEach((order, units) -> eligibilityNotices.promisesEnded(order, units, actor));
    return endedCount;
  }

  /**
   * Removes dependent promises and participations only for explicit deletion of the whole order.
   *
   * @param order the managed order whose responsibility has been validated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void deleteForOrderWithinTransaction(@NotNull JobOrder order) {
    responsibilityGuard.assertWritable(order);
    while (true) {
      List<MaterialClaim> batch =
          materialClaimRepository.findForOrderIncludingHistory(
              order.getId(), PageRequest.of(0, CLAIM_BATCH_SIZE));
      if (batch.isEmpty()) {
        break;
      }
      materialClaimRepository.deleteAll(batch);
      materialClaimRepository.flush();
    }
    while (true) {
      var batch =
          participations.findByJobOrderIdOrderById(
              order.getId(), PageRequest.of(0, CLAIM_BATCH_SIZE));
      if (batch.isEmpty()) {
        return;
      }
      participations.deleteAll(batch);
      participations.flush();
    }
  }

  /**
   * Withdraws claims whose bucket no longer exists on the order after an edit, for either order
   * kind. Runs in the caller's transaction and never touches the order's {@code @Version}.
   *
   * @param order the managed order whose buckets define which claims survive.
   * @return the number of orphaned claims withdrawn (0 when none).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int withdrawOrphanedClaimsWithinTransaction(JobOrder order) {
    Map<Bucket, Double> required = requiredByBucket(order);
    List<MaterialClaim> orphaned =
        materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(order.getId()).stream()
            .filter(c ->
                !required.containsKey(
                    new Bucket(c.getMaterial().getId(), c.getQualityTier().getId())))
            .toList();
    if (!orphaned.isEmpty()) {
      orphaned.forEach(claim ->
          promiseCoordinator.end(
              claim,
              de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionChange.Reason
                  .REQUIREMENT_ENDED));
      announceWithdrawn(order, orphaned, "ORDER_CHANGED");
      log.info(
          "Withdrew {} orphaned material claim(s) on order {} after a bucket was removed",
          orphaned.size(),
          order.getId());
    }
    return orphaned.size();
  }

  /**
   * Tells each member whose claim was withdrawn, once per claim (REQ-ORDERS-044).
   *
   * @param order the order the claims were on
   * @param claims the withdrawn claims
   * @param reasonCode {@code ORDER_CHANGED} or {@code DE_ESCALATED}
   */
  private void announceWithdrawn(
      @NotNull JobOrder order, @NotNull List<MaterialClaim> claims, @NotNull String reasonCode) {
    ActorRef actor = userService.currentActor();
    for (MaterialClaim claim : claims) {
      if (claim.getClaimedByUser() == null) {
        continue;
      }
      eventPublisher.publishEvent(
          JobOrderNotices.claimWithdrawn(
              order.getId(),
              order.getDisplayId(),
              order.getHandle(),
              claim.getClaimedByUser().getId(),
              claim.getMaterial().getName(),
              reasonCode,
              actor));
    }
  }

  /**
   * Computes the required amount per material bucket {@code (material, quality tier)} for either
   * order kind.
   *
   * @param order the order.
   * @return required amount keyed by bucket, insertion-ordered.
   */
  @NotNull
  private Map<Bucket, Double> requiredByBucket(JobOrder order) {
    Map<Bucket, Double> required = new LinkedHashMap<>();
    for (var requirement : materialRequirements.requirementsOf(order)) {
      required.merge(
          new Bucket(requirement.material().id(), requirement.tier().id()),
          requirement.requiredAmount(),
          Double::sum);
    }
    return required;
  }

  /**
   * Collects the quality tiers the order's buckets use.
   *
   * @param order the order.
   * @return tier id → tier, for every tier referenced by a bucket.
   */
  @NotNull
  private static Map<UUID, QualityTier> tiersOf(JobOrder order) {
    Map<UUID, QualityTier> tiers = new LinkedHashMap<>();
    if (order.getType() == JobOrderType.ITEM) {
      for (JobOrderItem item : order.getItems()) {
        for (JobOrderItemMaterial req : item.getMaterials()) {
          tiers.putIfAbsent(req.getQualityTier().getId(), req.getQualityTier());
        }
      }
    } else {
      for (JobOrderMaterial mat : order.getMaterials()) {
        tiers.putIfAbsent(mat.getQualityTier().getId(), mat.getQualityTier());
      }
    }
    return tiers;
  }

  /**
   * Resolves the bucket tier a claim payload names by its code, among the tiers the order uses.
   *
   * @param order the order.
   * @param dto the claim payload.
   * @return the tier.
   * @throws BadRequestException when the order uses no tier of that code.
   */
  @NotNull
  private static QualityTier resolveBucketTier(JobOrder order, CreateClaimDto dto) {
    String code = dto.qualityRequirement().trim().toUpperCase(Locale.ROOT);
    return tiersOf(order).values().stream()
        .filter(t -> t.getCode().equals(code))
        .findFirst()
        .orElseThrow(() ->
            new BadRequestException(
                "Quality tier " + code + " is not used by order " + order.getId()));
  }

  /**
   * Builds a material lookup for the buckets of an order.
   *
   * @param order the order.
   * @return material id → material, for every material referenced by a bucket.
   */
  @NotNull
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
   * Asserts the order accepts claim mutations: responsible to a Spezialkommando and not terminal.
   *
   * @param order the order.
   * @throws BadRequestException when the order is not a claimable SK order.
   */
  private void assertClaimable(@NotNull JobOrder order) {
    claimAuthority.assertClaimable(order);
  }

  /**
   * Enforces the claim permission matrix: an admin or a logistician (incl. lead) of the responsible
   * SK may manage any claim on the order; others only claims for a squadron they are Logistician
   * of.
   *
   * @param order the order whose responsible SK defines the elevated authority.
   * @param claimingOrgUnitId the squadron the claim is for.
   * @throws AccessDeniedException when the caller is neither a logistician/lead of the responsible
   *     SK nor Logistician of this squadron.
   */
  private void assertCanManage(JobOrder order, UUID claimingOrgUnitId) {
    claimAuthority.assertCanManage(order, claimingOrgUnitId);
  }

  private boolean canManage(@NotNull JobOrder order, @NotNull UUID claimingOrgUnitId) {
    return claimAuthority.canManage(order, claimingOrgUnitId);
  }

  private boolean claimable(@NotNull JobOrder order) {
    return claimAuthority.claimable(order);
  }

  /**
   * Loads an order with its materials/items eager-fetched for bucket aggregation.
   *
   * @param jobOrderId the order id.
   * @return the managed order.
   * @throws NotFoundException when the order does not exist.
   */
  private JobOrder loadOrder(UUID jobOrderId) {
    return Entities.require(
        jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);
  }

  /**
   * Resolves a payload's material from the order's buckets.
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
   * Resolves the claiming org unit for a new claim, requiring a active Profit-hierarchy squadron.
   *
   * @param claimingOrgUnitId the org unit id from the payload.
   * @return the managed, active Profit-hierarchy squadron-kind org unit.
   * @throws BadRequestException when the id is unknown, is not a squadron, or is a squadron that is
   *     not active Profit-hierarchy.
   */
  private OrgUnit resolveClaimingOrgUnit(UUID claimingOrgUnitId) {
    OrgUnit orgUnit =
        orgUnitRepository
            .findById(claimingOrgUnitId)
            .orElseThrow(() ->
                new BadRequestException(
                    "claimingOrgUnitId does not resolve to a known org unit: "
                        + claimingOrgUnitId));
    if (orgUnit.getKind() != OrgUnitKind.SQUADRON) {
      throw new BadRequestException(
          "Only squadrons may claim material; " + claimingOrgUnitId + " is not a squadron.");
    }
    if (!profitHierarchy.isEligible(orgUnit.getId())) {
      throw new BadRequestException("orders.claim.claimingUnit.ineligible");
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
  @NotNull
  private ClaimDto toClaimDto(@NotNull MaterialClaim claim) {
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
}
