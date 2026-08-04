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

import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandOrderShareDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandOverviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandRowDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService.OrderLinkedStockIndex;
import de.greluc.krt.profit.basetool.backend.support.QuantityTypeRounding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the cross-order material-demand overview (REQ-ORDERS-034): the material still to be
 * gathered across <em>every</em> non-terminal job order the caller may see, folded into one row per
 * {@code (responsible org unit, material, quality)} bucket.
 *
 * <p>This is the aggregate sibling of the per-order material view. The order detail answers "what
 * does this order need"; this service answers "what does my unit still have to gather in total", a
 * question that previously required opening every order and adding up by hand. Both read the same
 * underlying figures — the same outstanding requirements and the same order-linked stock sums — so
 * a bucket here always reconciles with the orders behind it.
 *
 * <p>Read-only and side-effect free: it maps, sums and sorts, never mutating an entity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOrderMaterialDemandService {

  /**
   * The statuses the overview aggregates: an order still being worked. Terminal orders ({@code
   * COMPLETED}, {@code REJECTED}) carry no outstanding demand and are excluded at the query, not
   * filtered out afterwards, so they never reach the sums.
   */
  private static final List<JobOrderStatus> NON_TERMINAL_STATUSES =
      List.of(JobOrderStatus.OPEN, JobOrderStatus.IN_PROGRESS);

  /** Loads the scoped, non-terminal orders together with both kinds' requirement branches. */
  private final JobOrderRepository jobOrderRepository;

  /** Supplies the caller's visibility scope and the viewer-side profit gate. */
  private final OwnerScopeService ownerScopeService;

  /** Supplies the batched order-linked stock index and the bucket quality floors. */
  private final JobOrderStockProjectionService jobOrderStockProjectionService;

  /** Normalises an item order's blueprint-derived requirements into material buckets. */
  private final JobOrderItemService jobOrderItemService;

  /** Supplies the batched per-order claim view for public Spezialkommando orders. */
  private final MaterialClaimService materialClaimService;

  /** Maps a {@code MATERIAL} line's material entity to its DTO. */
  private final MaterialMapper materialMapper;

  /** Maps the responsible OrgUnit to the badge reference the grouping is rendered with. */
  private final SquadronMapper squadronMapper;

  /**
   * Aggregates the caller's visible, non-terminal job orders into the per-org-unit material demand.
   *
   * <p>The caller's visibility scope is pushed into SQL (including the SK-public escape), and the
   * viewer-side profit gate is applied first, so a caller outside the order workflow gets an empty
   * overview rather than a partial one. Every multi-order lookup is batched once for the whole
   * result — the linked stock through {@link
   * JobOrderStockProjectionService#loadOrderLinkedStockIndex(java.util.Collection)} and the claims
   * through {@link MaterialClaimService#getClaimBucketsForOrders(List)} — so the aggregation adds
   * no N+1 over orders, materials or inventory (REQ-DATA-003).
   *
   * @return the overview, its groups ordered by org-unit shorthand and each group's rows SCU-first
   *     then by material name; empty (but never {@code null}) when the caller may see no
   *     non-terminal order.
   */
  @NotNull
  @Transactional(readOnly = true)
  public MaterialDemandOverviewDto getMaterialDemandOverview() {
    // Viewer-side profit gate, mirroring the order queue: a caller who belongs to no
    // profit-eligible
    // org unit is not part of the order workflow and must not see its aggregated demand either.
    if (!ownerScopeService.canViewJobOrders()) {
      return new MaterialDemandOverviewDto(List.of(), 0);
    }
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    List<JobOrder> orders =
        jobOrderRepository.findScopedOrdersWithMaterialRequirements(
            NON_TERMINAL_STATUSES,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds());
    if (orders.isEmpty()) {
      return new MaterialDemandOverviewDto(List.of(), 0);
    }

    OrderLinkedStockIndex stockIndex =
        jobOrderStockProjectionService.loadOrderLinkedStockIndex(
            orders.stream().map(JobOrder::getId).toList());
    Map<UUID, List<ClaimBucketDto>> claimsByOrder =
        materialClaimService.getClaimBucketsForOrders(
            orders.stream()
                .filter(JobOrderMaterialDemandService::isSpecialCommandResponsible)
                .toList());

    // LinkedHashMap throughout: the orders arrive displayId-ordered, so a bucket's
    // contributing-order
    // list is built in that order and stays stable across requests without a second sort.
    Map<UUID, GroupAccumulator> groups = new LinkedHashMap<>();
    for (JobOrder order : orders) {
      Map<BucketKey, ClaimBucketDto> claimByBucket =
          indexClaims(claimsByOrder.getOrDefault(order.getId(), List.of()));
      GroupAccumulator group =
          groups.computeIfAbsent(
              groupKey(order),
              key -> new GroupAccumulator(referenceOf(order.getResponsibleOrgUnit())));
      for (MaterialRequirement requirement : requirementsOf(order)) {
        accumulate(group, order, requirement, stockIndex, claimByBucket);
      }
    }

    List<MaterialDemandGroupDto> groupDtos =
        groups.values().stream()
            .map(GroupAccumulator::toDto)
            .filter(dto -> !dto.materials().isEmpty())
            .sorted(GROUP_ORDER)
            .toList();
    log.debug(
        "Aggregated material demand across {} order(s) into {} org-unit group(s)",
        orders.size(),
        groupDtos.size());
    return new MaterialDemandOverviewDto(groupDtos, orders.size());
  }

  /**
   * Normalises one order into its material buckets, hiding the two kinds' different shapes from the
   * aggregation loop. A {@code MATERIAL} order contributes its material lines directly (their
   * {@code amount} is already the outstanding requirement — handovers decrement it in place); an
   * {@code ITEM} order contributes the blueprint-derived aggregation, which already scales each
   * line by its not-yet-manufactured share. Neither is adjusted again here, so no reduction is
   * applied twice.
   *
   * @param order the order to normalise.
   * @return its buckets; empty for an order with no requirements.
   */
  @NotNull
  private List<MaterialRequirement> requirementsOf(@NotNull JobOrder order) {
    if (order.getType() == JobOrderType.ITEM) {
      List<MaterialRequirement> requirements = new ArrayList<>();
      for (AggregatedMaterialDto aggregated : jobOrderItemService.aggregateMaterials(order)) {
        if (aggregated.material() == null) {
          continue;
        }
        requirements.add(
            new MaterialRequirement(
                aggregated.material(),
                aggregated.qualityRequirement(),
                aggregated.totalQuantity() == null ? 0.0 : aggregated.totalQuantity()));
      }
      return requirements;
    }
    List<MaterialRequirement> requirements = new ArrayList<>();
    for (JobOrderMaterial line : order.getMaterials()) {
      if (line.getMaterial() == null) {
        continue;
      }
      // A MATERIAL line's bucket quality mirrors aggregateMaterials(): a stored 650-floor is GOOD,
      // "Keine" (null minQuality) is NONE — so both kinds land in the same bucket for one material.
      QualityRequirement quality =
          line.getMinQuality() != null ? QualityRequirement.GOOD : QualityRequirement.NONE;
      requirements.add(
          new MaterialRequirement(
              materialMapper.toDto(line.getMaterial()),
              quality,
              line.getAmount() == null ? 0.0 : line.getAmount()));
    }
    return requirements;
  }

  /**
   * Folds one order's bucket into its org unit's running totals and records the order's share for
   * the drill-down.
   *
   * @param group the accumulator of the order's responsible org unit.
   * @param order the contributing order.
   * @param requirement the order's bucket (material, quality and outstanding required amount).
   * @param stockIndex the batched order-linked stock lookup.
   * @param claimByBucket the order's claim view, keyed by bucket; empty for a non-SK order.
   */
  private void accumulate(
      @NotNull GroupAccumulator group,
      @NotNull JobOrder order,
      @NotNull MaterialRequirement requirement,
      @NotNull OrderLinkedStockIndex stockIndex,
      @NotNull Map<BucketKey, ClaimBucketDto> claimByBucket) {
    BucketKey key = new BucketKey(requirement.material().id(), requirement.quality());
    double booked =
        stockIndex.stockFor(
            order.getId(),
            requirement.material().id(),
            JobOrderStockProjectionService.qualityFloorFor(requirement.quality()));
    ClaimBucketDto claimBucket = claimByBucket.get(key);
    double claimed =
        claimBucket == null || claimBucket.claimedAmount() == null
            ? 0.0
            : claimBucket.claimedAmount();

    BucketAccumulator bucket =
        group.buckets.computeIfAbsent(
            key, unused -> new BucketAccumulator(requirement.material(), requirement.quality()));
    bucket.requiredAmount += requirement.requiredAmount();
    bucket.bookedAmount += booked;
    bucket.claimedAmount += claimed;
    bucket.shares.add(
        new MaterialDemandOrderShareDto(
            order.getId(),
            order.getDisplayId(),
            order.getStatus(),
            order.getType(),
            round(requirement.requiredAmount(), requirement.material()),
            round(booked, requirement.material()),
            round(claimed, requirement.material())));
  }

  /**
   * Indexes an order's claim buckets by {@code (material, quality)} so a requirement row can pick
   * up its claimed amount without scanning the list per bucket.
   *
   * @param claimBuckets the order's claim view; may be empty.
   * @return the buckets keyed by material + quality, never {@code null}.
   */
  @NotNull
  private static Map<BucketKey, ClaimBucketDto> indexClaims(
      @NotNull List<ClaimBucketDto> claimBuckets) {
    Map<BucketKey, ClaimBucketDto> index = new LinkedHashMap<>();
    for (ClaimBucketDto bucket : claimBuckets) {
      if (bucket.material() != null) {
        index.put(new BucketKey(bucket.material().id(), bucket.qualityRequirement()), bucket);
      }
    }
    return index;
  }

  /**
   * Rounds an aggregated amount to the precision its material's unit can express, so a summed PIECE
   * material never surfaces a fractional count and an SCU sum never shows floating-point noise
   * (REQ-ORDERS-001/002). Rounding happens on the <em>sum</em>, not per contribution, so the group
   * total and its drill-down shares stay consistent to the displayed precision.
   *
   * @param value the raw summed amount.
   * @param material the material whose quantity type selects the granularity.
   * @return the rounded amount.
   */
  private static double round(double value, @Nullable MaterialDto material) {
    return QuantityTypeRounding.roundForQuantityType(value, quantityTypeOf(material));
  }

  /**
   * Resolves a material DTO's textual quantity type back to the enum {@link QuantityTypeRounding}
   * keys on, treating an unknown or absent value as SCU (the {@code Material} default) rather than
   * failing the whole overview for one malformed catalog row.
   *
   * @param material the material, possibly {@code null}.
   * @return the quantity type, or {@code null} to mean "the SCU default".
   */
  @Nullable
  private static QuantityType quantityTypeOf(@Nullable MaterialDto material) {
    if (material == null || material.quantityType() == null) {
      return null;
    }
    try {
      return QuantityType.valueOf(material.quantityType());
    } catch (IllegalArgumentException unknownType) {
      log.debug("Unknown quantity type '{}' - falling back to SCU", material.quantityType());
      return null;
    }
  }

  /**
   * The grouping key of an order: its responsible org unit's id, or a nil UUID for the fallback
   * group that collects orders whose responsible unit is absent. Using a sentinel rather than a
   * {@code null} key keeps such demand visible instead of dropping it.
   *
   * @param order the order to key.
   * @return the group key.
   */
  @NotNull
  private static UUID groupKey(@NotNull JobOrder order) {
    OrgUnit responsible = order.getResponsibleOrgUnit();
    return responsible == null || responsible.getId() == null
        ? new UUID(0L, 0L)
        : responsible.getId();
  }

  /**
   * Maps the responsible OrgUnit to its badge reference, tolerating the absent unit the fallback
   * group exists for. Invoked once per group (from {@code computeIfAbsent}), not once per
   * contributing order.
   *
   * @param orgUnit the responsible org unit, possibly {@code null}.
   * @return the reference DTO, or {@code null} for the fallback group.
   */
  @Nullable
  private SquadronReferenceDto referenceOf(@Nullable OrgUnit orgUnit) {
    return orgUnit == null ? null : squadronMapper.orgUnitToReferenceDto(orgUnit);
  }

  /**
   * {@code true} iff the order is responsible to a Spezialkommando — the only orders that carry
   * material claims, so only those are handed to the batched claim lookup.
   *
   * @param jobOrder the order.
   * @return whether the order is a public SK order.
   */
  private static boolean isSpecialCommandResponsible(@NotNull JobOrder jobOrder) {
    return jobOrder.getResponsibleOrgUnit() != null
        && jobOrder.getResponsibleOrgUnit().getKind() == OrgUnitKind.SPECIAL_COMMAND;
  }

  /**
   * Group ordering: named units by shorthand (case-insensitive, falling back to the long name),
   * with the unnamed fallback group last so it never displaces a real unit from the top of the
   * page.
   */
  private static final Comparator<MaterialDemandGroupDto> GROUP_ORDER =
      Comparator.<MaterialDemandGroupDto, Integer>comparing(g -> g.orgUnit() == null ? 1 : 0)
          .thenComparing(
              g -> {
                if (g.orgUnit() == null) {
                  return "";
                }
                if (g.orgUnit().shorthand() != null) {
                  return g.orgUnit().shorthand();
                }
                return g.orgUnit().name() == null ? "" : g.orgUnit().name();
              },
              String.CASE_INSENSITIVE_ORDER);

  /**
   * Row ordering within a group: SCU materials first, then by material name (case-insensitive),
   * then {@code GOOD} before {@code NONE} — the same ordering the order detail's material tables
   * use, so a user finds a material in the same place on both surfaces.
   */
  private static final Comparator<MaterialDemandRowDto> ROW_ORDER =
      Comparator.<MaterialDemandRowDto, Integer>comparing(
              r ->
                  r.material() != null && "SCU".equalsIgnoreCase(r.material().quantityType())
                      ? 0
                      : 1)
          .thenComparing(
              r -> r.material() != null && r.material().name() != null ? r.material().name() : "",
              String.CASE_INSENSITIVE_ORDER)
          .thenComparing(r -> r.qualityRequirement().name());

  /**
   * One order's normalised material bucket, the shape both order kinds are reduced to before they
   * are aggregated.
   *
   * @param material the bucket's material
   * @param quality the bucket's quality requirement
   * @param requiredAmount the order's outstanding requirement for the bucket
   */
  private record MaterialRequirement(
      MaterialDto material, QualityRequirement quality, double requiredAmount) {}

  /**
   * The aggregation key inside one org-unit group: a material at one quality level.
   *
   * @param materialId the material's id
   * @param quality the quality requirement
   */
  private record BucketKey(UUID materialId, QualityRequirement quality) {}

  /** Running totals of one responsible org unit while the orders are folded in. */
  private static final class GroupAccumulator {

    /** The group's org unit, or {@code null} for the unresolved-unit fallback group. */
    private final SquadronReferenceDto orgUnit;

    /** The group's buckets, insertion-ordered by first appearance. */
    private final Map<BucketKey, BucketAccumulator> buckets = new LinkedHashMap<>();

    /**
     * Starts a group for one responsible org unit.
     *
     * @param orgUnit the group's org unit, or {@code null} for the fallback group.
     */
    private GroupAccumulator(@Nullable SquadronReferenceDto orgUnit) {
      this.orgUnit = orgUnit;
    }

    /**
     * Emits the finished group with its rows sorted for display.
     *
     * @return the group DTO.
     */
    private MaterialDemandGroupDto toDto() {
      List<MaterialDemandRowDto> rows =
          buckets.values().stream().map(BucketAccumulator::toDto).sorted(ROW_ORDER).toList();
      return new MaterialDemandGroupDto(orgUnit, rows);
    }
  }

  /** Running totals of one {@code (material, quality)} bucket within a group. */
  private static final class BucketAccumulator {

    /** The bucket's material. */
    private final MaterialDto material;

    /** The bucket's quality requirement. */
    private final QualityRequirement quality;

    /** The contributing orders' shares, in {@code displayId} order. */
    private final List<MaterialDemandOrderShareDto> shares = new ArrayList<>();

    /** Summed outstanding requirement across the group's orders. */
    private double requiredAmount;

    /** Summed order-linked stock across the group's orders. */
    private double bookedAmount;

    /** Summed claimed amount across the group's orders. */
    private double claimedAmount;

    /**
     * Starts a bucket for one material at one quality level.
     *
     * @param material the bucket's material.
     * @param quality the bucket's quality requirement.
     */
    private BucketAccumulator(MaterialDto material, QualityRequirement quality) {
      this.material = material;
      this.quality = quality;
    }

    /**
     * Emits the finished row, rounding every total to the material's own precision and deriving the
     * gathering gap from the rounded figures so the displayed columns actually subtract.
     *
     * @return the row DTO.
     */
    private MaterialDemandRowDto toDto() {
      double required = round(requiredAmount, material);
      double booked = round(bookedAmount, material);
      return new MaterialDemandRowDto(
          material,
          quality,
          required,
          booked,
          round(claimedAmount, material),
          Math.max(0.0, round(required - booked, material)),
          List.copyOf(shares));
    }
  }
}
