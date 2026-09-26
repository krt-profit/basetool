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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
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
import de.greluc.krt.profit.basetool.backend.support.JobOrderAuditLabel;
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
 * Manages material claims ("Eintragungen"): profit squadrons signing up for partial quantities of a
 * material bucket on a public Spezialkommando job order.
 *
 * <p>A claim is keyed on the bucket {@code (jobOrder, material, qualityRequirement)} and the
 * claiming squadron. Claims exist only on SK orders, never exceed the bucket's required amount in
 * sum (guarded by a pessimistic order lock, REQ-ORDERS-024, ADR-0092), and are deleted through the
 * repository without bumping the order's {@code @Version}.
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
   * Proxied self-reference so each {@link #upsertClaim} attempt runs {@link
   * #upsertClaimWithinTransaction} in its own {@code REQUIRES_NEW} transaction.
   */
  private final ObjectProvider<MaterialClaimService> self;

  /**
   * Total attempts {@link #upsertClaim} makes against a concurrent same-bucket writer before the
   * conflict surfaces as a 409.
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
   * Variant of {@link #getClaimBuckets(UUID)} for a caller already holding the managed {@link
   * JobOrder}.
   *
   * @param order the managed order whose buckets + claims to project.
   * @return the per-bucket claim view, never {@code null}.
   */
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
   * Batch variant of {@link #getClaimBucketsForOrder(JobOrder)} that loads the claims of all given
   * orders in one query (REQ-DATA-003). Pass only SK-responsible orders.
   *
   * @param orders the orders whose claim views to project; an empty collection yields an empty map.
   * @return order id → its per-bucket claim view, with empty claim lists where none exist.
   */
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
    Map<UUID, List<ClaimBucketDto>> result = new HashMap<>();
    for (JobOrder order : orders) {
      result.put(
          order.getId(),
          getClaimBucketsForOrder(order, claimsByOrder.getOrDefault(order.getId(), List.of())));
    }
    return result;
  }

  /**
   * Creates or updates a squadron's claim on a bucket, keyed on {@code (bucket, squadron)}.
   *
   * <p>The order must be a non-terminal SK order, the caller must act for the claiming squadron,
   * the bucket must exist, the total must not exceed the required amount, and a new claim's
   * squadron must be profit-eligible. Non-transactional orchestrator: each attempt runs in its own
   * transaction and is retried up to {@link #MAX_UPSERT_ATTEMPTS} times on a concurrent-write
   * conflict (REQ-ORDERS-024, ADR-0092).
   *
   * @param jobOrderId the order.
   * @param dto the claim payload.
   * @return the persisted claim.
   * @throws NotFoundException when the order or material is unknown.
   * @throws BadRequestException when the order is not an open SK order, the bucket does not exist,
   *     the amount would overclaim, or a new claim names a non-profit-eligible squadron.
   * @throws AccessDeniedException when the caller may not act for the claiming squadron.
   * @throws ObjectOptimisticLockingFailureException if every attempt loses the race (a 409).
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ClaimDto upsertClaim(@NotNull UUID jobOrderId, @NotNull CreateClaimDto dto) {
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
   * Performs one claim-upsert attempt in its own {@code REQUIRES_NEW} transaction, locking the
   * order before summing its claims. Called only through {@link #upsertClaim}.
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
  @NotNull
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
        Entities.require(
            materialClaimRepository.findById(claimId), () -> "MaterialClaim not found: " + claimId);
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
   * Withdraws every claim on an order when it is reassigned from an SK back to a squadron. Runs in
   * the caller's transaction and never touches the order's {@code @Version}.
   *
   * @param order the managed order whose claims are being withdrawn.
   * @return the number of claims withdrawn (0 when the order had none).
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
   * Computes the required amount per material bucket: per {@code (material, quality)} for an {@code
   * ITEM} order, and per material with {@code GOOD} / {@code NONE} derived from {@code minQuality}
   * for a {@code MATERIAL} order.
   *
   * @param order the order.
   * @return required amount keyed by bucket, insertion-ordered.
   */
  @NotNull
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
   * Enforces the claim permission matrix: an admin or a logistician (incl. lead) of the responsible
   * SK may manage any claim on the order; others only claims for a squadron they may edit.
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
   * Resolves the claiming org unit for a new claim, requiring a profit-eligible squadron.
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

  /**
   * Composes the audit subject label for the claim's parent order — {@code #<displayId>
   * '<handle>'}.
   *
   * @param order the parent order
   * @return the {@code #<displayId> '<handle>'} label
   */
  private static String orderLabel(@NotNull JobOrder order) {
    return JobOrderAuditLabel.of(order.getDisplayId());
  }
}
