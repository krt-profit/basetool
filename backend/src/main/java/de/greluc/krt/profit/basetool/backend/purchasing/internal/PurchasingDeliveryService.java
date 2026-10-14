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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DeliveryStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.events.PurchasingNotices;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyNeeds;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The goods of a deal (REQ-PUR-015, REQ-PUR-016): the delegate records a handover, the raising
 * unit's lead or a Logistician decides it and books the accepted part into the unit's pool, and the
 * delegate closes the delivery. The delegate never writes stock. Every step takes the deal's row
 * lock, so a delivery never conflicts with a tranche confirmation (REQ-PUR-026).
 */
@Service
@RequiredArgsConstructor
public class PurchasingDeliveryService {

  private static final double TOLERANCE = 5e-4;
  private static final Set<DealStatus> DELIVERING =
      Set.of(DealStatus.FUNDED, DealStatus.IN_DELIVERY);

  private final PurchasingDealRepository dealRepository;
  private final PurchasingDealDeliveryRepository deliveryRepository;
  private final PurchasingSettingsService settingsService;
  private final PurchasingAccessPolicy accessPolicy;
  private final PurchasingDealService dealService;
  private final PurchasingDealAssembler assembler;
  private final StockCommands stockCommands;
  private final SupplyNeeds supplyNeeds;
  private final LocationRepository locationRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final AuthHelperService authHelperService;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final MeterRegistry meterRegistry;

  /**
   * Records a handover the delegate made to the raising unit (REQ-PUR-015).
   *
   * @param dealId the deal
   * @param request the delivered amount and the handover location
   * @return the deal with the new delivery
   * @throws PurchasingProblemException with {@code PURCHASING_DELIVERY_EXCEEDS_UNDELIVERED} when
   *     the amount is more than the deal still lacks
   */
  @NotNull
  @Transactional
  public DealDto record(@NotNull UUID dealId, @NotNull RecordDeliveryRequest request) {
    settingsService.requireEnabled();
    PurchasingDeal deal = dealService.lock(dealId);
    dealService.requireOwnDeal(deal);
    requireDelivering(deal);
    double amount = validAmount(deal, request.amount());
    double delivered =
        deliveryRepository.findByDealIdOrderByCreatedAtAsc(dealId).stream()
            .mapToDouble(PurchasingDealDelivery::effectiveAmount)
            .sum();
    if (amount - (deal.getAmount() - delivered) > TOLERANCE) {
      throw new PurchasingProblemException(
          PurchasingProblemCode.PURCHASING_DELIVERY_EXCEEDS_UNDELIVERED,
          "The delivery is more than the deal still lacks");
    }
    if (request.locationId() != null && !locationRepository.existsById(request.locationId())) {
      throw new BadRequestException("Location not found");
    }
    PurchasingDealDelivery delivery = new PurchasingDealDelivery();
    delivery.setDealId(dealId);
    delivery.setAmount(amount);
    delivery.setLocationId(request.locationId());
    delivery.setRecordedBy(authHelperService.currentUserId().orElse(null));
    delivery.setStatus(DeliveryStatus.RECORDED);
    PurchasingDealDelivery saved = deliveryRepository.saveAndFlush(delivery);
    if (deal.getStatus() == DealStatus.FUNDED) {
      deal.setStatus(DealStatus.IN_DELIVERY);
      dealRepository.saveAndFlush(deal);
    }
    auditRecorder.record(
        AuditEventType.DEAL_DELIVERY_RECORDED,
        dealId,
        PurchasingDealService.label(deal),
        null,
        PurchasingDealService.details(deal)
            .with("delivery", saved.getId())
            .with("deliveredAmount", amount));
    OrgUnit unit = unitOf(deal);
    eventPublisher.publishEvent(
        PurchasingNotices.deliveryRecorded(
            dealId,
            deal.getDealNo(),
            unit.getId(),
            unit.getKind(),
            unit.getName(),
            entryName(deal),
            PurchasingTrancheService.amountText(amount),
            deal.getDelegateUserId()));
    count("recorded");
    return assembler.toDto(deal);
  }

  /**
   * Decides a delivery: the accepted part is booked into the raising unit's pool and credited to
   * the need, the rest is rejected (REQ-PUR-016).
   *
   * @param dealId the deal
   * @param deliveryId the delivery
   * @param request the accepted and rejected amounts, the measured quality and the stock location
   * @return the deal after the decision
   * @throws PurchasingProblemException with {@code PURCHASING_SELF_ACCEPTANCE} when the caller is
   *     the delegate, or {@code PURCHASING_QUALITY_BELOW_TIER} when the measured quality misses the
   *     need's tier
   */
  @NotNull
  @Transactional
  public DealDto accept(
      @NotNull UUID dealId, @NotNull UUID deliveryId, @NotNull AcceptDeliveryRequest request) {
    settingsService.requireEnabled();
    PurchasingDeal deal = dealService.lock(dealId);
    if (!accessPolicy.canAcceptDeliveries(deal)) {
      throw new AccessDeniedException("The caller may not decide the deliveries of this deal");
    }
    if (accessPolicy.isDealDelegate(deal)) {
      throw new PurchasingProblemException(
          PurchasingProblemCode.PURCHASING_SELF_ACCEPTANCE,
          "The delegate may not accept their own delivery");
    }
    PurchasingDealDelivery delivery =
        Entities.require(deliveryRepository.findById(deliveryId), "Delivery not found");
    if (!delivery.getDealId().equals(dealId)) {
      throw new BadRequestException("The delivery belongs to another deal");
    }
    OptimisticLock.checkRequired(
        delivery.getVersion(), request.version(), PurchasingDealDelivery.class, deliveryId);
    if (delivery.getStatus() != DeliveryStatus.RECORDED) {
      throw PurchasingDealService.dealState("The delivery is already decided");
    }
    double accepted = validAmount(deal, request.acceptedAmount(), true);
    double rejected = validAmount(deal, request.rejectedAmount(), true);
    if (Math.abs(accepted + rejected - delivery.getAmount()) > TOLERANCE) {
      throw new BadRequestException("Accepted plus rejected must equal the delivery");
    }
    if (rejected > 0.0 && request.rejectReason() == null) {
      throw new BadRequestException("A rejected part needs a reason");
    }
    Integer quality = null;
    if (accepted > 0.0) {
      quality = requireQuality(deal, request.measuredQuality());
    }
    UUID me =
        authHelperService
            .currentUserId()
            .orElseThrow(() -> new AccessDeniedException("Not signed in"));
    if (accepted > 0.0) {
      book(deal, delivery, request, accepted, quality, me);
    }
    delivery.setAcceptedAmount(accepted);
    delivery.setRejectedAmount(rejected);
    delivery.setRejectReason(rejected > 0.0 ? request.rejectReason() : null);
    delivery.setMeasuredQuality(quality);
    delivery.setAcceptedBy(me);
    delivery.setAcceptedAt(Instant.now());
    DeliveryStatus outcome =
        accepted <= 0.0
            ? DeliveryStatus.REJECTED
            : rejected <= 0.0 ? DeliveryStatus.ACCEPTED : DeliveryStatus.PARTIALLY_ACCEPTED;
    delivery.setStatus(outcome);
    deliveryRepository.saveAndFlush(delivery);
    auditRecorder.record(
        outcome == DeliveryStatus.REJECTED
            ? AuditEventType.DEAL_DELIVERY_REJECTED
            : AuditEventType.DEAL_DELIVERY_ACCEPTED,
        dealId,
        PurchasingDealService.label(deal),
        null,
        PurchasingDealService.details(deal)
            .with("delivery", deliveryId)
            .with("accepted", accepted)
            .with("rejected", rejected)
            .with("quality", quality)
            .with("rejectReason", delivery.getRejectReason()));
    eventPublisher.publishEvent(
        PurchasingNotices.deliveryDecided(
            dealId, deal.getDealNo(), deal.getDelegateUserId(), outcome.name(), me));
    count(outcome == DeliveryStatus.REJECTED ? "rejected" : "accepted");
    closeWhenComplete(deal);
    return assembler.toDto(deal);
  }

  /**
   * Closes the delivery of a deal: it becomes {@code DELIVERED} when something was accepted and
   * {@code FAILED} otherwise, and the settlement falls due (REQ-PUR-017).
   *
   * @param dealId the deal
   * @return the closed deal
   */
  @NotNull
  @Transactional
  public DealDto close(@NotNull UUID dealId) {
    settingsService.requireEnabled();
    PurchasingDeal deal = dealService.lock(dealId);
    dealService.requireOwnDeal(deal);
    requireDelivering(deal);
    List<PurchasingDealDelivery> deliveries =
        deliveryRepository.findByDealIdOrderByCreatedAtAsc(dealId);
    if (deliveries.stream().anyMatch(d -> d.getStatus() == DeliveryStatus.RECORDED)) {
      throw PurchasingDealService.dealState("A recorded delivery is still undecided");
    }
    closeDelivery(deal, deliveries);
    return assembler.toDto(deal);
  }

  private void closeWhenComplete(@NotNull PurchasingDeal deal) {
    List<PurchasingDealDelivery> deliveries =
        deliveryRepository.findByDealIdOrderByCreatedAtAsc(deal.getId());
    double accepted =
        deliveries.stream()
            .filter(d -> d.getAcceptedAmount() != null)
            .mapToDouble(PurchasingDealDelivery::effectiveAmount)
            .sum();
    boolean pending = deliveries.stream().anyMatch(d -> d.getStatus() == DeliveryStatus.RECORDED);
    if (!pending && accepted >= deal.getAmount() - TOLERANCE) {
      closeDelivery(deal, deliveries);
    }
  }

  private void closeDelivery(
      @NotNull PurchasingDeal deal, @NotNull List<PurchasingDealDelivery> deliveries) {
    double accepted =
        deliveries.stream()
            .filter(d -> d.getAcceptedAmount() != null)
            .mapToDouble(PurchasingDealDelivery::effectiveAmount)
            .sum();
    boolean failed = accepted <= 0.0;
    deal.setDeliveryClosed(true);
    deal.setStatus(failed ? DealStatus.FAILED : DealStatus.DELIVERED);
    deal.setSettlementDueAt(
        Instant.now().plus(Duration.ofDays(settingsService.current().getSettlementDueDays())));
    deal.setOverdueNotifiedAt(null);
    dealRepository.saveAndFlush(deal);
    auditRecorder.record(
        failed ? AuditEventType.DEAL_FAILED : AuditEventType.DEAL_DELIVERY_CLOSED,
        deal.getId(),
        PurchasingDealService.label(deal),
        null,
        PurchasingDealService.details(deal).with("accepted", accepted));
    count(failed ? "failed" : "closed");
  }

  private void book(
      @NotNull PurchasingDeal deal,
      @NotNull PurchasingDealDelivery delivery,
      @NotNull AcceptDeliveryRequest request,
      double accepted,
      Integer quality,
      @NotNull UUID me) {
    if (request.locationId() == null) {
      throw new BadRequestException("The accepted goods need a stock location");
    }
    Location location =
        Entities.require(locationRepository.findById(request.locationId()), "Location not found");
    StockLot lot = new StockLot(deal.getMaterial(), deal.getGameItem(), location, quality, false);
    stockCommands.lockLots(me, List.of(lot.key()));
    delivery.setBookedRowId(
        stockCommands.bookIntoPool(me, unitOf(deal), lot, accepted, StockBookingReason.PURCHASE));
    supplyNeeds.creditPurchase(deal.getNeedId(), accepted);
  }

  private Integer requireQuality(@NotNull PurchasingDeal deal, Integer measured) {
    if (deal.getMaterial() == null) {
      return null;
    }
    int quality = measured == null ? 0 : measured;
    if (deal.getQualityTier() != null && quality < deal.getQualityTier().getMinQuality()) {
      throw new PurchasingProblemException(
          PurchasingProblemCode.PURCHASING_QUALITY_BELOW_TIER,
          "The measured quality is below the tier of the need");
    }
    return quality;
  }

  private void requireDelivering(@NotNull PurchasingDeal deal) {
    if (!DELIVERING.contains(deal.getStatus()) || deal.isDeliveryClosed()) {
      throw PurchasingDealService.dealState("The deal takes no delivery in its state");
    }
  }

  private double validAmount(@NotNull PurchasingDeal deal, double raw) {
    return validAmount(deal, raw, false);
  }

  private double validAmount(@NotNull PurchasingDeal deal, double raw, boolean allowZero) {
    double rounded = ScuAmounts.roundToScuScale(raw);
    if (rounded < 0.0 || (rounded == 0.0 && !allowZero)) {
      throw new BadRequestException("The amount must be positive");
    }
    boolean whole =
        deal.getGameItem() != null
            || (deal.getMaterial() != null
                && deal.getMaterial().getQuantityType() == QuantityType.PIECE);
    if (whole && rounded % 1 != 0) {
      throw new BadRequestException("This catalogue entry is counted in whole units");
    }
    return rounded;
  }

  private @NotNull OrgUnit unitOf(@NotNull PurchasingDeal deal) {
    return Entities.require(
        orgUnitRepository.findById(deal.getRaisingOrgUnitId()), "Org unit not found");
  }

  private static @NotNull String entryName(@NotNull PurchasingDeal deal) {
    return deal.getMaterial() != null ? deal.getMaterial().getName() : deal.getGameItem().getName();
  }

  private void count(@NotNull String event) {
    meterRegistry
        .counter(MetricNames.PURCHASING_DEALS, MetricNames.TAG_EVENT, "delivery_" + event)
        .increment();
  }
}
