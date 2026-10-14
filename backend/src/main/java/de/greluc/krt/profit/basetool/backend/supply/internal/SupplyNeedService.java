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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedLifecycleObserver;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriority;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.PurchaseReason;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemCode;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises, edits and closes the needs of the raising units (REQ-SUP-003 … REQ-SUP-006, REQ-SUP-019).
 * Every mutation checks the feature switch, the unit's role and the unit's setting, records its
 * audit event in the same transaction and counts it. What donations and deliveries cover is added
 * by single statements that leave the need's version alone, so a concurrent edit never conflicts.
 */
@Service
@RequiredArgsConstructor
public class SupplyNeedService {

  /** The rounding tolerance of the "covered" comparison. */
  private static final double COVERED_TOLERANCE = 5e-4;

  private final SupplyNeedRepository needRepository;
  private final SupplyUnitSettingRepository unitSettingRepository;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final QualityTierRepository qualityTierRepository;
  private final JobOrderRepository jobOrderRepository;
  private final SupplySettingsService settingsService;
  private final SupplyAccessPolicy accessPolicy;
  private final SupplyMapper mapper;
  private final AuditRecorder auditRecorder;
  private final AuthHelperService authHelperService;
  private final MeterRegistry meterRegistry;
  private final ObjectProvider<NeedLifecycleObserver> lifecycleObservers;

  /**
   * Pages over the needs the caller may see: everyone sees the open ones, the managers of a unit
   * and the governing leadership see every state of theirs (REQ-SUP-006).
   *
   * @param orgUnitId only this raising unit, or {@code null}
   * @param statuses only these states, or empty for the visible ones
   * @param purchasable only purchasable ({@code true}) or non-purchasable needs, or {@code null}
   * @param materialId only this material, or {@code null}
   * @param pageable page, size and sort
   * @return one page of needs
   */
  @Transactional(readOnly = true)
  public @NotNull Page<NeedDto> list(
      @Nullable UUID orgUnitId,
      @NotNull Set<NeedStatus> statuses,
      @Nullable Boolean purchasable,
      @Nullable UUID materialId,
      @NotNull Pageable pageable) {
    settingsService.requireEnabled();
    final boolean seesAll = accessPolicy.isAdmin() || accessPolicy.isGoverningLeadership();
    final Set<UUID> managed = accessPolicy.managedUnitIds();
    Specification<SupplyNeed> spec = fetchCatalogue();
    if (orgUnitId != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("orgUnitId"), orgUnitId));
    }
    if (!statuses.isEmpty()) {
      spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
    }
    if (purchasable != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("purchasable"), purchasable));
    }
    if (materialId != null) {
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.equal(root.join("material", JoinType.LEFT).get("id"), materialId));
    }
    if (!seesAll) {
      spec =
          spec.and(
              (root, query, cb) -> {
                var open = cb.equal(root.get("status"), NeedStatus.OPEN);
                if (managed.isEmpty()) {
                  return open;
                }
                return cb.or(open, root.get("orgUnitId").in(managed));
              });
    }
    Page<SupplyNeed> page = needRepository.findAll(spec, pageable);
    SupplyMapper.NeedContext context = mapper.needContext(page.getContent());
    return page.map(need -> mapper.toDto(need, context));
  }

  /**
   * Reads one need the caller may see.
   *
   * @param id the need
   * @return the need
   */
  @Transactional(readOnly = true)
  public @NotNull NeedDto get(@NotNull UUID id) {
    settingsService.requireEnabled();
    SupplyNeed need = loadVisible(id);
    return mapper.toDto(need, mapper.needContext(List.of(need)));
  }

  /**
   * Raises a need (REQ-SUP-003, REQ-SUP-004).
   *
   * @param request the need
   * @return the raised need
   * @throws SupplyProblemException when the unit may not raise needs
   * @throws AccessDeniedException when the caller does not manage the unit
   * @throws BadRequestException when the request is inconsistent
   */
  @Transactional
  public @NotNull NeedDto create(@NotNull CreateNeedRequest request) {
    settingsService.requireEnabled();
    requireManager(request.orgUnitId());
    SupplyUnitSetting setting =
        unitSettingRepository
            .findById(request.orgUnitId())
            .filter(SupplyUnitSetting::isMayRaiseNeeds)
            .orElseThrow(
                () ->
                    new SupplyProblemException(
                        SupplyProblemCode.SUPPLY_UNIT_CANNOT_RAISE,
                        "The unit may not raise needs"));
    SupplyNeed need = new SupplyNeed();
    need.setOrgUnitId(setting.getId());
    applyCatalogue(need, request.materialId(), request.gameItemId(), request.qualityTierId());
    need.setAmount(validAmount(need, request.amount()));
    need.setPriority(request.priority() == null ? NeedPriority.NORMAL : request.priority());
    applyPurchase(need, request.purchasable(), request.purchaseReason(), request.maxUnitPrice());
    applyJobOrder(need, request.jobOrderId());
    need.setStatus(NeedStatus.OPEN);
    need.setCreatedBy(authHelperService.currentUserId().orElse(null));
    SupplyNeed saved = needRepository.save(need);
    auditRecorder.record(
        AuditEventType.NEED_RAISED,
        saved.getId(),
        label(saved),
        null,
        AuditDetails.of("unit", saved.getOrgUnitId())
            .with("amount", saved.getAmount())
            .with("tier", saved.getQualityTier() == null ? null : saved.getQualityTier().getCode())
            .with("purchasable", saved.isPurchasable()));
    count("raised");
    if (saved.isPurchasable()) {
      releasedForPurchase(saved);
    }
    return mapper.toDto(saved, mapper.needContext(List.of(saved)));
  }

  /**
   * Edits an open or paused need (REQ-SUP-003).
   *
   * @param id the need
   * @param request the new values and the echoed version
   * @return the edited need
   */
  @Transactional
  public @NotNull NeedDto update(@NotNull UUID id, @NotNull UpdateNeedRequest request) {
    settingsService.requireEnabled();
    SupplyNeed need = lockManaged(id, request.version());
    final boolean wasPurchasable = need.isPurchasable();
    requireOpenOrPaused(need);
    double amount = validAmount(need, request.amount());
    if (amount + COVERED_TOLERANCE < need.getFulfilledAmount()) {
      throw new BadRequestException("The amount cannot fall below what is already covered");
    }
    need.setAmount(amount);
    need.setPriority(request.priority() == null ? NeedPriority.NORMAL : request.priority());
    applyPurchase(need, request.purchasable(), request.purchaseReason(), request.maxUnitPrice());
    applyJobOrder(need, request.jobOrderId());
    if (need.getFulfilledAmount() >= amount - COVERED_TOLERANCE) {
      need.setStatus(NeedStatus.FULFILLED);
    }
    SupplyNeed saved = needRepository.saveAndFlush(need);
    auditRecorder.record(
        AuditEventType.NEED_UPDATED,
        saved.getId(),
        label(saved),
        null,
        AuditDetails.of("amount", saved.getAmount()).with("purchasable", saved.isPurchasable()));
    if (saved.getStatus() == NeedStatus.FULFILLED) {
      recordFulfilled(saved);
    }
    if (!wasPurchasable && saved.isPurchasable() && saved.getStatus() == NeedStatus.OPEN) {
      releasedForPurchase(saved);
    }
    count("updated");
    return mapper.toDto(saved, mapper.needContext(List.of(saved)));
  }

  /**
   * Withdraws an open or paused need; it is terminal (REQ-SUP-005).
   *
   * @param id the need
   * @param version the echoed version
   * @return the withdrawn need
   */
  @Transactional
  public @NotNull NeedDto withdraw(@NotNull UUID id, long version) {
    settingsService.requireEnabled();
    SupplyNeed need = lockManaged(id, version);
    requireOpenOrPaused(need);
    need.setStatus(NeedStatus.WITHDRAWN);
    SupplyNeed saved = needRepository.saveAndFlush(need);
    auditRecorder.record(AuditEventType.NEED_WITHDRAWN, saved.getId(), label(saved), null, null);
    lifecycleObservers.orderedStream().forEach(observer -> observer.onWithdrawn(saved.getId()));
    count("withdrawn");
    return mapper.toDto(saved, mapper.needContext(List.of(saved)));
  }

  /**
   * Pauses an open need (REQ-SUP-005).
   *
   * @param id the need
   * @param version the echoed version
   * @return the paused need
   */
  @Transactional
  public @NotNull NeedDto pause(@NotNull UUID id, long version) {
    settingsService.requireEnabled();
    SupplyNeed need = lockManaged(id, version);
    if (need.getStatus() != NeedStatus.OPEN) {
      throw notOpen();
    }
    need.setStatus(NeedStatus.PAUSED);
    SupplyNeed saved = needRepository.saveAndFlush(need);
    auditRecorder.record(AuditEventType.NEED_PAUSED, saved.getId(), label(saved), null, null);
    count("paused");
    return mapper.toDto(saved, mapper.needContext(List.of(saved)));
  }

  /**
   * Opens a paused need again (REQ-SUP-005).
   *
   * @param id the need
   * @param version the echoed version
   * @return the reopened need
   */
  @Transactional
  public @NotNull NeedDto resume(@NotNull UUID id, long version) {
    settingsService.requireEnabled();
    SupplyNeed need = lockManaged(id, version);
    if (need.getStatus() != NeedStatus.PAUSED) {
      throw notOpen();
    }
    need.setStatus(NeedStatus.OPEN);
    SupplyNeed saved = needRepository.saveAndFlush(need);
    auditRecorder.record(AuditEventType.NEED_RESUMED, saved.getId(), label(saved), null, null);
    count("resumed");
    return mapper.toDto(saved, mapper.needContext(List.of(saved)));
  }

  /**
   * Adds what a received donation covers to its need and fulfils the need once it is covered
   * (REQ-SUP-005). Joins the donation's transaction.
   *
   * @param needId the need
   * @param amount the covered amount
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void credit(@NotNull UUID needId, double amount) {
    if (needRepository.addFulfilled(needId, amount, NeedStatus.OPEN, NeedStatus.PAUSED) == 0) {
      return;
    }
    if (needRepository.markFulfilledIfCovered(
            needId, COVERED_TOLERANCE, NeedStatus.FULFILLED, NeedStatus.OPEN, NeedStatus.PAUSED)
        > 0) {
      needRepository.findById(needId).ifPresent(this::recordFulfilled);
      count("fulfilled");
    }
  }

  /**
   * Adds an accepted purchase delivery to a need's covered and purchased amounts and fulfils the
   * need once it is covered (REQ-SUP-005). Joins the delivery's transaction.
   *
   * @param needId the need
   * @param amount the accepted amount
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void creditPurchase(@NotNull UUID needId, double amount) {
    if (needRepository.addPurchased(needId, amount, NeedStatus.OPEN, NeedStatus.PAUSED) == 0) {
      return;
    }
    if (needRepository.markFulfilledIfCovered(
            needId, COVERED_TOLERANCE, NeedStatus.FULFILLED, NeedStatus.OPEN, NeedStatus.PAUSED)
        > 0) {
      needRepository.findById(needId).ifPresent(this::recordFulfilled);
      count("fulfilled");
    }
  }

  private void recordFulfilled(@NotNull SupplyNeed need) {
    auditRecorder.record(
        AuditEventType.NEED_FULFILLED,
        need.getId(),
        label(need),
        null,
        AuditDetails.of("amount", need.getAmount()));
    lifecycleObservers.orderedStream().forEach(observer -> observer.onFulfilled(need.getId()));
  }

  private void releasedForPurchase(@NotNull SupplyNeed need) {
    NeedRef ref = SupplyMapper.toRef(need);
    UUID actor = authHelperService.currentUserId().orElse(null);
    lifecycleObservers
        .orderedStream()
        .forEach(observer -> observer.onReleasedForPurchase(ref, actor));
  }

  private @NotNull SupplyNeed loadVisible(@NotNull UUID id) {
    SupplyNeed need = Entities.require(needRepository.findById(id), "Need not found");
    boolean visible =
        need.getStatus() == NeedStatus.OPEN
            || accessPolicy.isAdmin()
            || accessPolicy.isGoverningLeadership()
            || accessPolicy.managedUnitIds().contains(need.getOrgUnitId());
    if (!visible) {
      throw new AccessDeniedException("Need not visible");
    }
    return need;
  }

  private @NotNull SupplyNeed lockManaged(@NotNull UUID id, long version) {
    SupplyNeed need = Entities.require(needRepository.findByIdForUpdate(id), "Need not found");
    requireManager(need.getOrgUnitId());
    OptimisticLock.checkRequired(need.getVersion(), version, SupplyNeed.class, id);
    return need;
  }

  private void requireManager(@NotNull UUID orgUnitId) {
    if (!accessPolicy.canManageUnit(orgUnitId)) {
      throw new AccessDeniedException("The caller does not manage this unit's needs");
    }
  }

  private static void requireOpenOrPaused(@NotNull SupplyNeed need) {
    if (need.getStatus() != NeedStatus.OPEN && need.getStatus() != NeedStatus.PAUSED) {
      throw notOpen();
    }
  }

  private static @NotNull SupplyProblemException notOpen() {
    return new SupplyProblemException(
        SupplyProblemCode.SUPPLY_NEED_NOT_OPEN, "The need is not in a state that allows this");
  }

  private void applyCatalogue(
      @NotNull SupplyNeed need,
      @Nullable UUID materialId,
      @Nullable UUID gameItemId,
      @Nullable UUID qualityTierId) {
    if ((materialId == null) == (gameItemId == null)) {
      throw new BadRequestException("Name exactly one of material and game item");
    }
    if (materialId != null) {
      Material material =
          Entities.require(materialRepository.findById(materialId), "Material not found");
      need.setMaterial(material);
      QualityTier tier =
          qualityTierId == null
              ? Entities.require(qualityTierRepository.findByMinQuality(0), "Base tier not found")
              : Entities.require(qualityTierRepository.findById(qualityTierId), "Tier not found");
      need.setQualityTier(tier);
    } else {
      if (qualityTierId != null) {
        throw new BadRequestException("A game item has no quality tier");
      }
      GameItem item =
          Entities.require(gameItemRepository.findById(gameItemId), "Game item not found");
      need.setGameItem(item);
    }
  }

  private static double validAmount(@NotNull SupplyNeed need, double amount) {
    double rounded = ScuAmounts.roundToScuScale(amount);
    if (rounded <= 0.0) {
      throw new BadRequestException("The amount must be positive");
    }
    boolean whole =
        need.getGameItem() != null
            || (need.getMaterial() != null
                && need.getMaterial().getQuantityType() == QuantityType.PIECE);
    if (whole && rounded % 1 != 0) {
      throw new BadRequestException("This catalogue entry is counted in whole units");
    }
    return rounded;
  }

  private static void applyPurchase(
      @NotNull SupplyNeed need,
      boolean purchasable,
      @Nullable PurchaseReason reason,
      @Nullable Long maxUnitPrice) {
    if (purchasable) {
      if (reason == null || maxUnitPrice == null || maxUnitPrice <= 0) {
        throw new BadRequestException("A purchasable need needs a reason and a maximum unit price");
      }
      need.setPurchasable(true);
      need.setPurchaseReason(reason);
      need.setMaxUnitPrice(maxUnitPrice);
    } else {
      need.setPurchasable(false);
      need.setPurchaseReason(null);
      need.setMaxUnitPrice(null);
    }
  }

  private void applyJobOrder(@NotNull SupplyNeed need, @Nullable UUID jobOrderId) {
    if (jobOrderId == null) {
      need.setJobOrderId(null);
      return;
    }
    JobOrder order =
        Entities.require(jobOrderRepository.findById(jobOrderId), "Job order not found");
    if (order.getResponsibleOrgUnit() == null
        || !need.getOrgUnitId().equals(order.getResponsibleOrgUnit().getId())) {
      throw new BadRequestException("The job order belongs to another unit");
    }
    need.setJobOrderId(jobOrderId);
  }

  private static @NotNull String label(@NotNull SupplyNeed need) {
    if (need.getMaterial() != null) {
      return need.getMaterial().getName();
    }
    return need.getGameItem() == null ? "—" : need.getGameItem().getName();
  }

  private void count(@NotNull String event) {
    meterRegistry.counter(MetricNames.SUPPLY_NEEDS, MetricNames.TAG_EVENT, event).increment();
  }

  private static @NotNull Specification<SupplyNeed> fetchCatalogue() {
    return (root, query, cb) -> {
      if (query != null
          && query.getResultType() != Long.class
          && query.getResultType() != long.class) {
        root.fetch("material", JoinType.LEFT);
        root.fetch("gameItem", JoinType.LEFT);
        root.fetch("qualityTier", JoinType.LEFT);
      }
      return cb.conjunction();
    };
  }

  /**
   * Lists the needs of a unit that are open, for pickers that offer a need to credit.
   *
   * @param orgUnitId the raising unit
   * @return the unit's open needs
   */
  @Transactional(readOnly = true)
  public @NotNull List<NeedDto> openNeedsOf(@NotNull UUID orgUnitId) {
    settingsService.requireEnabled();
    List<SupplyNeed> needs =
        needRepository.findAll(
            fetchCatalogue()
                .and(
                    (root, query, cb) ->
                        cb.and(
                            cb.equal(root.get("orgUnitId"), orgUnitId),
                            cb.equal(root.get("status"), NeedStatus.OPEN))));
    SupplyMapper.NeedContext context = mapper.needContext(needs);
    List<NeedDto> dtos = new ArrayList<>();
    for (SupplyNeed need : needs) {
      dtos.add(mapper.toDto(need, context));
    }
    return dtos;
  }
}
