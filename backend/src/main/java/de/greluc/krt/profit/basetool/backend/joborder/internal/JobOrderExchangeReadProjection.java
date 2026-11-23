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

import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimBucketSource;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.ItemLine;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.ItemProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.MaterialLine;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.MaterialProgress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.Progress;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead.Versions;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects the member-independent exchange read of job orders from their current rows
 * (REQ-OXCH-003): the header, the versions, the assignees and active support by id, and the
 * progress of each kind as the web shows it.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class JobOrderExchangeReadProjection implements JobOrderExchangeReads {

  private final JobOrderRepository orders;
  private final JobOrderParticipationRepository participations;
  private final JobOrderResponsibilityGuard responsibility;
  private final ClaimBucketSource claims;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyLedger ledger;

  @Override
  public @NotNull @Unmodifiable Map<UUID, JobOrderExchangeRead> project(
      @NotNull Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    List<JobOrder> found = orders.findAllById(Set.copyOf(orderIds));
    Set<UUID> valid = responsibility.validOrderIds(found);
    Map<UUID, List<UUID>> support = activeSupport(found);
    Map<UUID, List<ClaimBucketDto>> buckets =
        claims.getClaimBucketsForOrders(ofKind(found, JobOrderType.MATERIAL));
    Map<UUID, JobOrderMoneyTarget> moneyTargets =
        targets
            .findAllById(ofKind(found, JobOrderType.AUEC).stream().map(JobOrder::getId).toList())
            .stream()
            .collect(Collectors.toMap(JobOrderMoneyTarget::getOrderId, Function.identity()));
    return found.stream()
        .collect(
            Collectors.toUnmodifiableMap(
                JobOrder::getId,
                order ->
                    read(
                        order,
                        !valid.contains(order.getId()),
                        support.getOrDefault(order.getId(), List.of()),
                        buckets.getOrDefault(order.getId(), List.of()),
                        moneyTargets.get(order.getId()))));
  }

  private JobOrderExchangeRead read(
      JobOrder order,
      boolean responsibilityInvalid,
      List<UUID> supportingUnitIds,
      List<ClaimBucketDto> buckets,
      @Nullable JobOrderMoneyTarget moneyTarget) {
    boolean auec = order.getType() == JobOrderType.AUEC;
    Progress progress =
        switch (order.getType()) {
          case MATERIAL -> materialProgress(order, buckets);
          case ITEM -> itemProgress(order);
          case AUEC ->
              ledger
                  .read(order.getId())
                  .progress(Objects.requireNonNull(moneyTarget).getAmount(), order.getStatus());
        };
    return JobOrderExchangeRead.builder()
        .id(order.getId())
        .number(Objects.requireNonNull(order.getDisplayId()))
        .kind(order.getType())
        .status(order.getStatus())
        .priority(order.getPriority())
        .createdAt(Objects.requireNonNull(order.getCreatedAt()))
        .requestingUnitId(order.getRequestingOrgUnit().getId())
        .responsibleUnitId(order.getResponsibleOrgUnit().getId())
        .supportingUnitIds(supportingUnitIds)
        .comment(order.getComment())
        .contact(auec ? null : order.getHandle())
        .responsibilityInvalid(responsibilityInvalid)
        .versions(
            new Versions(
                order.getLifecycleVersion(),
                auec ? Objects.requireNonNull(moneyTarget).getVersion() : null,
                order.getCommentVersion()))
        .assigneeIds(
            order.getAssignees().stream().map(assignee -> assignee.getUser().getId()).toList())
        .progress(progress)
        .build();
  }

  private Map<UUID, List<UUID>> activeSupport(List<JobOrder> found) {
    return participations
        .findByActiveTrueAndJobOrderIdIn(found.stream().map(JobOrder::getId).toList())
        .stream()
        .collect(
            Collectors.groupingBy(
                participation -> participation.getJobOrder().getId(),
                Collectors.mapping(
                    participation -> participation.getClaimingOrgUnit().getId(),
                    Collectors.toList())));
  }

  private static List<JobOrder> ofKind(List<JobOrder> found, JobOrderType kind) {
    return found.stream().filter(order -> order.getType() == kind).toList();
  }

  private static MaterialProgress materialProgress(JobOrder order, List<ClaimBucketDto> buckets) {
    Map<Bucket, Double> open =
        buckets.stream()
            .collect(
                Collectors.toMap(
                    bucket -> new Bucket(bucket.material().id(), bucket.qualityTier().id()),
                    ClaimBucketDto::openRemaining));
    Map<Bucket, MaterialLine> lines = new LinkedHashMap<>();
    for (JobOrderMaterial material : order.getMaterials()) {
      Bucket bucket = new Bucket(material.getMaterial().getId(), material.getQualityTier().getId());
      lines.merge(
          bucket,
          new MaterialLine(
              bucket.materialId(),
              bucket.qualityTierId(),
              material.getAmount(),
              material.getCreditedAmount(),
              material.remainingAmount(),
              Objects.requireNonNull(open.get(bucket))),
          JobOrderExchangeReadProjection::combine);
    }
    return new MaterialProgress(List.copyOf(lines.values()));
  }

  private static MaterialLine combine(MaterialLine first, MaterialLine second) {
    return new MaterialLine(
        first.materialId(),
        first.qualityTierId(),
        ScuAmounts.roundToScuScale(first.requested() + second.requested()),
        ScuAmounts.roundToScuScale(first.handedOver() + second.handedOver()),
        ScuAmounts.roundToScuScale(first.remaining() + second.remaining()),
        first.open());
  }

  private static ItemProgress itemProgress(JobOrder order) {
    return new ItemProgress(
        order.getItems().stream().map(JobOrderExchangeReadProjection::itemLine).toList());
  }

  private static ItemLine itemLine(JobOrderItem item) {
    int ordered = item.getAmount();
    int delivered = item.getDeliveredAmount();
    return new ItemLine(
        item.getId(),
        item.getGameItem().getId(),
        ordered,
        item.getManufacturedAmount(),
        delivered,
        Math.max(0, ordered - delivered));
  }

  /**
   * One material and quality tier of an order.
   *
   * @param materialId the material
   * @param qualityTierId the quality tier
   */
  private record Bucket(UUID materialId, UUID qualityTierId) {}
}
