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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.PurposeBoundRequests;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyNeeds;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The header of a trade deal (REQ-PUR-007 … REQ-PUR-011, REQ-PUR-021, REQ-PUR-022): create, edit
 * and cancel a draft, read and list deals, and the rules every submit re-checks. Tranches,
 * deliveries and the settlement sit in their own services and take the deal's row lock, so none of
 * them conflicts with another (REQ-PUR-026). Audit details carry ids, enums and amounts only
 * (REQ-PUR-025).
 */
@Service
@RequiredArgsConstructor
public class PurchasingDealService {

  private static final UUID NO_ACCOUNT = new UUID(0L, 0L);
  private static final Set<DealStatus> COMMITTING =
      Set.of(DealStatus.SUBMITTED, DealStatus.FUNDED, DealStatus.IN_DELIVERY);

  private final PurchasingDealRepository dealRepository;
  private final PurchasingDealTrancheRepository trancheRepository;
  private final PurchasingDealDeliveryRepository deliveryRepository;
  private final PurchasingPartnerRepository partnerRepository;
  private final PurchasingSettingsService settingsService;
  private final PurchasingAccessPolicy accessPolicy;
  private final PurchasingDealAssembler assembler;
  private final SupplyNeeds supplyNeeds;
  private final UserRepository userRepository;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final QualityTierRepository qualityTierRepository;
  private final MissionRepository missionRepository;
  private final PurposeBoundRequests purposeBoundRequests;
  private final AuthHelperService authHelperService;
  private final AuditRecorder auditRecorder;
  private final MeterRegistry meterRegistry;

  /**
   * Pages over the deals the caller may read.
   *
   * @param status only this status, or {@code null}
   * @param needId only the deals of this need, or {@code null}
   * @param mine only the caller's own deals
   * @param overdue only the deals whose settlement is overdue
   * @param pageable page, size and sort
   * @return one page of deals
   */
  @NotNull
  @Transactional(readOnly = true)
  public Page<DealDto> list(
      @Nullable DealStatus status,
      @Nullable UUID needId,
      boolean mine,
      boolean overdue,
      @NotNull Pageable pageable) {
    settingsService.requireEnabled();
    Specification<PurchasingDeal> spec = visibleToCaller();
    if (status != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
    }
    if (needId != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("needId"), needId));
    }
    if (mine) {
      UUID me = authHelperService.currentUserId().orElse(NO_ACCOUNT);
      spec = spec.and((root, q, cb) -> cb.equal(root.get("delegateUserId"), me));
    }
    if (overdue) {
      Instant now = Instant.now();
      spec =
          spec.and((root, q, cb) ->
              cb.and(
                  root.get("status").in(DealStatus.DELIVERED, DealStatus.FAILED),
                  cb.lessThan(root.<Instant>get("settlementDueAt"), now)));
    }
    Page<PurchasingDeal> page = dealRepository.findAll(spec, pageable);
    List<DealDto> dtos = assembler.toDtos(page.getContent());
    return new PageImpl<>(dtos, pageable, page.getTotalElements());
  }

  /**
   * Reads one deal with its tranches, deliveries and settlement.
   *
   * @param id the deal
   * @return the deal as the caller sees it
   * @throws AccessDeniedException when the caller may not read the deal
   */
  @NotNull
  @Transactional(readOnly = true)
  public DealDto get(@NotNull UUID id) {
    settingsService.requireEnabled();
    return assembler.toDto(requireReadable(id));
  }

  /**
   * Creates a draft deal for one need (REQ-PUR-007).
   *
   * @param request the deal
   * @return the created draft
   * @throws AccessDeniedException when the caller is no active delegate
   */
  @NotNull
  @Transactional
  public DealDto create(@NotNull CreateDealRequest request) {
    settingsService.requireEnabled();
    UUID me = requireDelegate();
    NeedRef need = requireNeed(request.needId());
    final PurchasingPartner partner = requirePartner(request.partnerId());
    PurchasingDeal deal = new PurchasingDeal();
    deal.setNeedId(need.id());
    deal.setRaisingOrgUnitId(need.orgUnitId());
    deal.setDelegateUserId(me);
    deal.setStatus(DealStatus.DRAFT);
    applyHeader(
        deal,
        need,
        partner,
        request.amount(),
        request.unitPrice(),
        request.paymentModel(),
        request.riskClass(),
        request.escortWish(),
        request.missionId());
    PurchasingDeal saved = dealRepository.saveAndFlush(deal);
    auditRecorder.record(
        AuditEventType.DEAL_CREATED,
        saved.getId(),
        label(saved),
        null,
        details(saved).with("riskClass", saved.getRiskClass().name()));
    count("created");
    return assembler.toDto(saved);
  }

  /**
   * Edits a draft deal; the need stays (REQ-PUR-007).
   *
   * @param id the deal
   * @param request the new values with the echoed version
   * @return the updated draft
   * @throws PurchasingProblemException with {@code PURCHASING_DEAL_STATE} unless the deal is a
   *     draft
   */
  @NotNull
  @Transactional
  public DealDto update(@NotNull UUID id, @NotNull UpdateDealRequest request) {
    settingsService.requireEnabled();
    requireDelegate();
    PurchasingDeal deal = lock(id);
    requireOwnDeal(deal);
    OptimisticLock.checkRequired(deal.getVersion(), request.version(), PurchasingDeal.class, id);
    requireStatus(deal, DealStatus.DRAFT);
    NeedRef need = requireNeed(deal.getNeedId());
    final PurchasingPartner partner = requirePartner(request.partnerId());
    applyHeader(
        deal,
        need,
        partner,
        request.amount(),
        request.unitPrice(),
        request.paymentModel(),
        request.riskClass(),
        request.escortWish(),
        request.missionId());
    PurchasingDeal saved = dealRepository.saveAndFlush(deal);
    auditRecorder.record(
        AuditEventType.DEAL_UPDATED,
        saved.getId(),
        label(saved),
        null,
        details(saved).with("riskClass", saved.getRiskClass().name()));
    count("updated");
    return assembler.toDto(saved);
  }

  /**
   * Cancels a deal before any money was confirmed; the open tranche requests are cancelled in the
   * bank in the same transaction (REQ-PUR-022).
   *
   * @param id the deal
   * @param version the echoed version of the deal header
   * @return the cancelled deal
   * @throws PurchasingProblemException with {@code PURCHASING_DEAL_STATE} once money is confirmed
   */
  @NotNull
  @Transactional
  public DealDto cancel(@NotNull UUID id, @NotNull Long version) {
    settingsService.requireEnabled();
    PurchasingDeal deal = lock(id);
    if (!accessPolicy.canCancelDeal(deal)) {
      throw new AccessDeniedException("The caller may not cancel this deal");
    }
    OptimisticLock.checkRequired(deal.getVersion(), version, PurchasingDeal.class, id);
    if (deal.getStatus() != DealStatus.DRAFT && deal.getStatus() != DealStatus.SUBMITTED) {
      throw dealState("Only a deal without confirmed money can be cancelled");
    }
    cancelWithinTransaction(deal, "MANUAL");
    return assembler.toDto(deal);
  }

  /**
   * Cancels a locked deal that holds no confirmed money and cancels its open tranche requests in
   * the bank; the deal ends {@code CANCELLED}.
   *
   * @param deal the locked deal, a draft or submitted
   * @param reasonCode {@code MANUAL} or {@code NEED_WITHDRAWN}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void cancelWithinTransaction(@NotNull PurchasingDeal deal, @NotNull String reasonCode) {
    for (PurchasingDealTranche tranche :
        trancheRepository.findByDealIdOrderByCreatedAtAsc(deal.getId())) {
      if (tranche.getState() == TrancheState.REQUESTED && tranche.getBankRequestId() != null) {
        purposeBoundRequests.cancel(tranche.getBankRequestId(), BankPurposeKind.PURCHASING_DEAL);
      }
    }
    deal.setStatus(DealStatus.CANCELLED);
    dealRepository.saveAndFlush(deal);
    auditRecorder.record(
        AuditEventType.DEAL_CANCELLED,
        deal.getId(),
        label(deal),
        null,
        details(deal).with("reason", reasonCode));
    count("cancelled");
  }

  /**
   * Re-checks the rules a deal must hold whenever money is requested for it: the need is open and
   * purchasable, the counterparty is external and not blocked, the amount fits the need's open
   * remainder, the price stays under the ceiling and the class conditions hold (REQ-PUR-005,
   * REQ-PUR-008 … REQ-PUR-011).
   *
   * @param deal the deal about to be funded
   * @return the need the deal serves
   */
  @NotNull
  NeedRef requireSubmittable(@NotNull PurchasingDeal deal) {
    NeedRef need = requireNeed(deal.getNeedId());
    PurchasingPartner partner = requirePartner(deal.getPartnerId());
    validate(
        deal,
        need,
        partner,
        deal.getAmount(),
        deal.getUnitPrice(),
        deal.getTotal(),
        deal.getRiskClass());
    return need;
  }

  /**
   * Loads a deal under its row lock.
   *
   * @param id the deal
   * @return the locked deal
   */
  @NotNull
  PurchasingDeal lock(@NotNull UUID id) {
    return Entities.require(dealRepository.findByIdForUpdate(id), "Deal not found");
  }

  /**
   * Refuses the call unless the caller is the deal's delegate.
   *
   * @param deal the deal
   */
  void requireOwnDeal(@NotNull PurchasingDeal deal) {
    if (!accessPolicy.canTradeDeal(deal)) {
      throw new AccessDeniedException("The caller is not the delegate of this deal");
    }
  }

  /**
   * Refuses the call unless the deal is in the given state.
   *
   * @param deal the deal
   * @param expected the required state
   */
  void requireStatus(@NotNull PurchasingDeal deal, @NotNull DealStatus expected) {
    if (deal.getStatus() != expected) {
      throw dealState("The deal is " + deal.getStatus() + ", not " + expected);
    }
  }

  /**
   * Builds the {@code PURCHASING_DEAL_STATE} problem.
   *
   * @param detail what the state forbids
   * @return the exception to throw
   */
  @NotNull
  static PurchasingProblemException dealState(@NotNull String detail) {
    return new PurchasingProblemException(PurchasingProblemCode.PURCHASING_DEAL_STATE, detail);
  }

  /**
   * Builds the audit details every deal event starts with: ids, enums and amounts only.
   *
   * @param deal the deal
   * @return the details
   */
  @NotNull
  static AuditDetails details(@NotNull PurchasingDeal deal) {
    return AuditDetails.of("need", deal.getNeedId())
        .with("partner", deal.getPartnerId())
        .with("amount", deal.getAmount())
        .with("total", deal.getTotal())
        .with("status", deal.getStatus().name());
  }

  /**
   * Names a deal for the audit trail.
   *
   * @param deal the deal
   * @return the label shown in the audit viewer
   */
  @NotNull
  static String label(@NotNull PurchasingDeal deal) {
    return "Deal " + deal.getDealNo();
  }

  private void applyHeader(
      @NotNull PurchasingDeal deal,
      @NotNull NeedRef need,
      @NotNull PurchasingPartner partner,
      double rawAmount,
      long unitPrice,
      @NotNull PaymentModel paymentModel,
      @Nullable RiskClassCode requestedClass,
      boolean escortWish,
      @Nullable UUID missionId) {
    double amount = ScuAmounts.roundToScuScale(rawAmount);
    if (amount <= 0.0) {
      throw new BadRequestException("The amount must be positive");
    }
    if ((need.quantityPiece() || need.gameItemId() != null) && amount % 1 != 0) {
      throw new BadRequestException("This catalogue entry is counted in whole units");
    }
    if (missionId != null && !missionRepository.existsById(missionId)) {
      throw new BadRequestException("The Einsatz does not exist");
    }
    long total = Math.round(amount * unitPrice);
    RiskClassCode computed = classFor(total);
    RiskClassCode finalClass = computed;
    if (requestedClass != null) {
      if (requestedClass.compareTo(computed) < 0) {
        throw new BadRequestException("The risk class may be raised, never lowered");
      }
      finalClass = requestedClass;
    }
    validate(deal, need, partner, amount, unitPrice, total, finalClass);
    deal.setPartnerId(partner.getId());
    deal.setMaterial(null);
    deal.setGameItem(null);
    deal.setQualityTier(null);
    deal.setAmount(amount);
    deal.setUnitPrice(unitPrice);
    deal.setTotal(total);
    deal.setPaymentModel(paymentModel);
    deal.setRiskClass(finalClass);
    deal.setEscortWish(escortWish);
    deal.setMissionId(missionId);
    supplyNeedCatalogue(deal, need);
  }

  private void supplyNeedCatalogue(@NotNull PurchasingDeal deal, @NotNull NeedRef need) {
    deal.setMaterial(
        need.materialId() == null ? null : materialRepository.getReferenceById(need.materialId()));
    deal.setGameItem(
        need.gameItemId() == null ? null : gameItemRepository.getReferenceById(need.gameItemId()));
    deal.setQualityTier(
        need.qualityTierId() == null
            ? null
            : qualityTierRepository.getReferenceById(need.qualityTierId()));
  }

  private void validate(
      @NotNull PurchasingDeal deal,
      @NotNull NeedRef need,
      @NotNull PurchasingPartner partner,
      double amount,
      long unitPrice,
      long total,
      @NotNull RiskClassCode riskClass) {
    if (need.status() != NeedStatus.OPEN) {
      throw problem(PurchasingProblemCode.PURCHASING_NEED_NOT_OPEN, "The need is not open");
    }
    if (!need.purchasable()) {
      throw problem(
          PurchasingProblemCode.PURCHASING_NEED_NOT_PURCHASABLE,
          "The raising unit does not allow buying for this need");
    }
    if (need.maxUnitPrice() != null && unitPrice > need.maxUnitPrice()) {
      throw problem(
          PurchasingProblemCode.PURCHASING_PRICE_ABOVE_MAXIMUM,
          "The unit price is above the need's maximum");
    }
    if (need.qualityTierId() != null
        && !settingsService.current().getAllowedQualityTierIds().contains(need.qualityTierId())) {
      throw problem(
          PurchasingProblemCode.PURCHASING_TIER_NOT_ALLOWED,
          "Purchasing may not buy this quality tier");
    }
    if (partner.getStatus() == PartnerStatus.BLOCKED) {
      throw problem(PurchasingProblemCode.PURCHASING_PARTNER_BLOCKED, "The partner is blocked");
    }
    requireExternal(partner);
    PurchasingRiskClass rules = classRow(riskClass);
    if (total > rules.getMaxTotal()) {
      throw problem(
          PurchasingProblemCode.PURCHASING_TOTAL_ABOVE_CLASS_MAXIMUM,
          "The total is above the maximum of the risk class");
    }
    if (rules.isRequiresKnownPartner() && dealRepository.countCleanSettled(partner.getId()) == 0) {
      throw problem(
          PurchasingProblemCode.PURCHASING_PARTNER_NOT_KNOWN,
          "The risk class requires a partner with a settled deal");
    }
    double available = need.openAmount() - committedElsewhere(need.id(), deal.getId());
    if (amount - available > 5e-4) {
      throw problem(
          PurchasingProblemCode.PURCHASING_AMOUNT_EXCEEDS_NEED,
          "The amount exceeds what the need still asks for");
    }
  }

  /**
   * Refuses a deal whose counterparty has turned out to be a member (REQ-PUR-005).
   *
   * @param deal the deal
   */
  void requireExternalCounterparty(@NotNull PurchasingDeal deal) {
    requireExternal(requirePartner(deal.getPartnerId()));
  }

  private void requireExternal(@NotNull PurchasingPartner partner) {
    if (partner.getKind() == PartnerKind.INDIVIDUAL
        && !partner.isErased()
        && userRepository.existsOtherAccountWithName(
            partner.getName().toLowerCase(Locale.ROOT), NO_ACCOUNT)) {
      throw problem(
          PurchasingProblemCode.PURCHASING_COUNTERPARTY_IS_MEMBER, "The counterparty is a member");
    }
  }

  private double committedElsewhere(@NotNull UUID needId, @Nullable UUID ownDealId) {
    List<PurchasingDeal> others =
        dealRepository.findByNeedIdAndStatusIn(needId, COMMITTING).stream()
            .filter(other -> !other.getId().equals(ownDealId))
            .toList();
    if (others.isEmpty()) {
      return 0.0;
    }
    Set<UUID> ids = new HashSet<>();
    others.forEach(other -> ids.add(other.getId()));
    Map<UUID, Double> accepted = new HashMap<>();
    for (PurchasingDealDelivery delivery : deliveryRepository.findByDealIdIn(ids)) {
      if (delivery.getAcceptedAmount() != null) {
        accepted.merge(delivery.getDealId(), delivery.getAcceptedAmount(), Double::sum);
      }
    }
    double committed = 0.0;
    for (PurchasingDeal other : others) {
      committed += Math.max(0.0, other.getAmount() - accepted.getOrDefault(other.getId(), 0.0));
    }
    return committed;
  }

  private @NotNull RiskClassCode classFor(long total) {
    for (PurchasingRiskClass riskClass : settingsService.classesInOrder()) {
      if (total <= riskClass.getMaxTotal()) {
        return riskClass.getCode();
      }
    }
    throw problem(
        PurchasingProblemCode.PURCHASING_TOTAL_ABOVE_CLASS_MAXIMUM,
        "The total is above the maximum of every risk class");
  }

  private @NotNull PurchasingRiskClass classRow(@NotNull RiskClassCode code) {
    return settingsService.classesInOrder().stream()
        .filter(row -> row.getCode() == code)
        .findFirst()
        .orElseThrow(() -> new BadRequestException("Unknown risk class"));
  }

  private @NotNull UUID requireDelegate() {
    UUID me =
        authHelperService
            .currentUserId()
            .orElseThrow(() -> new AccessDeniedException("Not signed in"));
    if (!accessPolicy.canCreateDeals()) {
      throw new AccessDeniedException("Only an active trade delegate may trade");
    }
    return me;
  }

  private @NotNull NeedRef requireNeed(@NotNull UUID needId) {
    return supplyNeeds.find(needId).orElseThrow(() -> new BadRequestException("Need not found"));
  }

  private @NotNull PurchasingPartner requirePartner(@NotNull UUID partnerId) {
    return Entities.require(partnerRepository.findById(partnerId), "Partner not found");
  }

  private @NotNull PurchasingDeal requireReadable(@NotNull UUID id) {
    PurchasingDeal deal = Entities.require(dealRepository.findById(id), "Deal not found");
    if (!accessPolicy.canReadDeal(deal)) {
      throw new AccessDeniedException("The caller may not read this deal");
    }
    return deal;
  }

  /**
   * Builds the specification of the deals the caller may read.
   *
   * @return the visibility filter
   */
  @NotNull
  Specification<PurchasingDeal> visibleToCaller() {
    if (accessPolicy.isGoverningLeadership() || accessPolicy.isBankStaff()) {
      return (root, q, cb) -> cb.conjunction();
    }
    UUID me = authHelperService.currentUserId().orElse(NO_ACCOUNT);
    Set<UUID> units = accessPolicy.managedUnitIds();
    return (root, q, cb) -> {
      List<Predicate> any = new ArrayList<>();
      any.add(cb.equal(root.get("delegateUserId"), me));
      if (!units.isEmpty()) {
        any.add(root.get("raisingOrgUnitId").in(units));
      }
      return cb.or(any.toArray(Predicate[]::new));
    };
  }

  private static @NotNull PurchasingProblemException problem(
      @NotNull PurchasingProblemCode code, @NotNull String detail) {
    return new PurchasingProblemException(code, detail);
  }

  private void count(@NotNull String event) {
    meterRegistry.counter(MetricNames.PURCHASING_DEALS, MetricNames.TAG_EVENT, event).increment();
  }
}
