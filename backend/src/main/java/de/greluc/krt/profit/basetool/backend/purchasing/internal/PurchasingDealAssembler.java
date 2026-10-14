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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DeliveryStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Turns deals into {@link DealDto}s: loads the tranches, deliveries, settlements, partners and
 * names of a whole page in a handful of statements, and decides per caller what the deal shows and
 * what the caller may do next (REQ-PUR-021).
 */
@Component
@RequiredArgsConstructor
class PurchasingDealAssembler {

  private static final Set<DealStatus> TRANCHE_STATES =
      Set.of(
          DealStatus.DRAFT,
          DealStatus.SUBMITTED,
          DealStatus.FUNDED,
          DealStatus.IN_DELIVERY,
          DealStatus.DELIVERED);
  private static final Set<DealStatus> DELIVERY_STATES =
      Set.of(DealStatus.FUNDED, DealStatus.IN_DELIVERY);

  private final PurchasingDealTrancheRepository trancheRepository;
  private final PurchasingDealDeliveryRepository deliveryRepository;
  private final PurchasingDealSettlementRepository settlementRepository;
  private final PurchasingPartnerRepository partnerRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final LocationRepository locationRepository;
  private final UserRepository userRepository;
  private final PurchasingAccessPolicy accessPolicy;
  private final AuthHelperService authHelperService;

  /**
   * Builds the DTO of one deal.
   *
   * @param deal the deal
   * @return the deal as the caller sees it
   */
  @NotNull
  DealDto toDto(@NotNull PurchasingDeal deal) {
    return toDtos(List.of(deal)).getFirst();
  }

  /**
   * Builds the DTOs of several deals, in the order given.
   *
   * @param deals the deals
   * @return the deals as the caller sees them
   */
  @NotNull
  List<DealDto> toDtos(@NotNull Collection<PurchasingDeal> deals) {
    if (deals.isEmpty()) {
      return List.of();
    }
    Set<UUID> dealIds = deals.stream().map(PurchasingDeal::getId).collect(Collectors.toSet());
    final Map<UUID, List<PurchasingDealTranche>> tranches =
        group(
            trancheRepository.findByDealIdIn(dealIds),
            PurchasingDealTranche::getDealId,
            Comparator.comparing(PurchasingDealTranche::getCreatedAt));
    List<PurchasingDealDelivery> allDeliveries = deliveryRepository.findByDealIdIn(dealIds);
    final Map<UUID, List<PurchasingDealDelivery>> deliveries =
        group(
            allDeliveries,
            PurchasingDealDelivery::getDealId,
            Comparator.comparing(PurchasingDealDelivery::getCreatedAt));
    final Map<UUID, PurchasingDealSettlement> settlements =
        settlementRepository.findByDealIdIn(dealIds).stream()
            .collect(Collectors.toMap(PurchasingDealSettlement::getDealId, Function.identity()));
    final Map<UUID, PurchasingPartner> partners =
        partnerRepository
            .findAllById(
                deals.stream().map(PurchasingDeal::getPartnerId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(PurchasingPartner::getId, Function.identity()));
    Map<UUID, String> unitNames = new HashMap<>();
    for (OrgUnit unit :
        orgUnitRepository.findAllById(
            deals.stream().map(PurchasingDeal::getRaisingOrgUnitId).collect(Collectors.toSet()))) {
      unitNames.put(unit.getId(), unit.getName());
    }
    Map<UUID, String> userNames = new HashMap<>();
    Set<UUID> userIds = new HashSet<>();
    deals.forEach(deal -> addIfPresent(userIds, deal.getDelegateUserId()));
    if (!userIds.isEmpty()) {
      for (User user : userRepository.findAllById(userIds)) {
        userNames.put(user.getId(), user.getEffectiveName());
      }
    }
    Set<UUID> locationIds = new HashSet<>();
    allDeliveries.forEach(delivery -> addIfPresent(locationIds, delivery.getLocationId()));
    Map<UUID, String> locationNames = new HashMap<>();
    if (!locationIds.isEmpty()) {
      for (Location location : locationRepository.findAllById(locationIds)) {
        locationNames.put(location.getId(), location.getName());
      }
    }
    boolean readNotes = accessPolicy.canReadPartnerNotes();
    boolean governing = accessPolicy.isGoverningLeadership();
    UUID caller = authHelperService.currentUserId().orElse(null);
    Instant now = Instant.now();
    return deals.stream()
        .map(
            deal ->
                assemble(
                    deal,
                    tranches.getOrDefault(deal.getId(), List.of()),
                    deliveries.getOrDefault(deal.getId(), List.of()),
                    settlements.get(deal.getId()),
                    partners.get(deal.getPartnerId()),
                    unitNames,
                    userNames,
                    locationNames,
                    readNotes,
                    governing,
                    caller,
                    now))
        .toList();
  }

  private @NotNull DealDto assemble(
      @NotNull PurchasingDeal deal,
      @NotNull List<PurchasingDealTranche> tranches,
      @NotNull List<PurchasingDealDelivery> deliveries,
      @Nullable PurchasingDealSettlement settlement,
      @Nullable PurchasingPartner partner,
      @NotNull Map<UUID, String> unitNames,
      @NotNull Map<UUID, String> userNames,
      @NotNull Map<UUID, String> locationNames,
      boolean readNotes,
      boolean governing,
      @Nullable UUID caller,
      @NotNull Instant now) {
    boolean mine = caller != null && caller.equals(deal.getDelegateUserId());
    long requested = tranches.stream().filter(t -> t.isLive()).mapToLong(t -> t.getAmount()).sum();
    long funded =
        tranches.stream()
            .filter(t -> t.getState() == TrancheState.CONFIRMED)
            .mapToLong(t -> t.getAmount())
            .sum();
    double accepted =
        deliveries.stream()
            .filter(d -> d.getAcceptedAmount() != null)
            .mapToDouble(d -> d.getAcceptedAmount())
            .sum();
    double pending =
        deliveries.stream()
            .filter(d -> d.getStatus() == DeliveryStatus.RECORDED)
            .mapToDouble(d -> d.getAmount())
            .sum();
    double effective = deliveries.stream().mapToDouble(d -> d.effectiveAmount()).sum();
    boolean overdue =
        deal.getSettlementDueAt() != null
            && deal.getSettlementDueAt().isBefore(now)
            && (deal.getStatus() == DealStatus.DELIVERED || deal.getStatus() == DealStatus.FAILED);
    boolean settlementOpen = settlement == null || settlement.getSettledAt() == null;
    boolean canAccept = accessPolicy.canAcceptDeliveries(deal) && !mine;
    boolean hasPending = pending > 0.0;
    boolean mayTrade = mine && accessPolicy.isActiveDelegate();
    return new DealDto(
        deal.getId(),
        deal.getDealNo() == null ? 0L : deal.getDealNo(),
        deal.getNeedId(),
        new PurchasingRefDto(
            deal.getRaisingOrgUnitId(), unitNames.getOrDefault(deal.getRaisingOrgUnitId(), "—")),
        deal.getMaterial() == null
            ? null
            : new PurchasingRefDto(deal.getMaterial().getId(), deal.getMaterial().getName()),
        deal.getGameItem() == null
            ? null
            : new PurchasingRefDto(deal.getGameItem().getId(), deal.getGameItem().getName()),
        quantityType(deal),
        deal.getQualityTier() == null ? null : tierRef(deal.getQualityTier()),
        deal.getAmount(),
        accepted,
        pending,
        deal.getUnitPrice(),
        deal.getTotal(),
        deal.getMaterial() == null ? null : deal.getMaterial().getPriceBuy(),
        deal.getPaymentModel(),
        deal.getRiskClass(),
        deal.isEscortWish(),
        deal.getMissionId(),
        deal.getStatus(),
        deal.isDeliveryClosed(),
        deal.getSettlementDueAt(),
        overdue,
        requested,
        funded,
        partnerDto(partner, readNotes),
        deal.getDelegateUserId() == null ? null : userNames.get(deal.getDelegateUserId()),
        mine,
        tranches.stream().map(PurchasingDealAssembler::toTrancheDto).toList(),
        deliveries.stream()
            .map(delivery -> toDeliveryDto(delivery, locationNames, canAccept))
            .toList(),
        settlement == null ? null : toSettlementDto(settlement),
        mayTrade && deal.getStatus() == DealStatus.DRAFT,
        mayTrade && TRANCHE_STATES.contains(deal.getStatus()) && requested < deal.getTotal(),
        mayTrade
            && DELIVERY_STATES.contains(deal.getStatus())
            && !deal.isDeliveryClosed()
            && deal.getAmount() - effective > 5e-4,
        mayTrade
            && DELIVERY_STATES.contains(deal.getStatus())
            && !deal.isDeliveryClosed()
            && !hasPending,
        mine
            && (deal.getStatus() == DealStatus.DELIVERED || deal.getStatus() == DealStatus.FAILED)
            && settlementOpen,
        (mine || governing)
            && (deal.getStatus() == DealStatus.DRAFT || deal.getStatus() == DealStatus.SUBMITTED),
        deal.getVersion(),
        deal.getCreatedAt());
  }

  private static @NotNull String quantityType(@NotNull PurchasingDeal deal) {
    if (deal.getGameItem() != null) {
      return "ITEM";
    }
    return deal.getMaterial() != null && deal.getMaterial().getQuantityType() == QuantityType.PIECE
        ? "PIECE"
        : "SCU";
  }

  private static @NotNull PurchasingTierRefDto tierRef(@NotNull QualityTier tier) {
    return new PurchasingTierRefDto(
        tier.getId(), tier.getCode(), tier.getLabelDe(), tier.getLabelEn(), tier.getMinQuality());
  }

  private static @NotNull DealPartnerDto partnerDto(
      @Nullable PurchasingPartner partner, boolean readNotes) {
    if (partner == null) {
      return new DealPartnerDto(
          new UUID(0, 0), null, PurchasingPartner.ERASED_NAME, null, null, null, null);
    }
    return new DealPartnerDto(
        partner.getId(),
        partner.getKind(),
        partner.getName(),
        partner.getOrgTag(),
        partner.getStatus(),
        readNotes ? partner.getConditionsNote() : null,
        readNotes ? partner.getReputationNote() : null);
  }

  private static @NotNull TrancheDto toTrancheDto(@NotNull PurchasingDealTranche tranche) {
    return new TrancheDto(
        tranche.getId(),
        tranche.getKind(),
        tranche.getAmount(),
        tranche.getState(),
        tranche.getBankRequestId(),
        tranche.getDecidedAt(),
        tranche.getCreatedAt(),
        tranche.getVersion());
  }

  private static @NotNull DeliveryDto toDeliveryDto(
      @NotNull PurchasingDealDelivery delivery,
      @NotNull Map<UUID, String> locationNames,
      boolean mayAccept) {
    PurchasingRefDto location =
        delivery.getLocationId() == null
            ? null
            : new PurchasingRefDto(
                delivery.getLocationId(),
                locationNames.getOrDefault(delivery.getLocationId(), "—"));
    return new DeliveryDto(
        delivery.getId(),
        delivery.getAmount(),
        location,
        delivery.getStatus(),
        delivery.getAcceptedAmount(),
        delivery.getRejectedAmount(),
        delivery.getRejectReason(),
        delivery.getMeasuredQuality(),
        delivery.getAcceptedAt(),
        delivery.getCreatedAt(),
        mayAccept && delivery.getStatus() == DeliveryStatus.RECORDED,
        delivery.getVersion());
  }

  private static @NotNull SettlementDto toSettlementDto(
      @NotNull PurchasingDealSettlement settlement) {
    return new SettlementDto(
        settlement.getMoneyReceived(),
        settlement.getMoneyPaid(),
        settlement.getRemainder(),
        settlement.getLossAmount(),
        settlement.getLossReason(),
        settlement.getRemainderRequestId(),
        settlement.getSettledAt(),
        settlement.getVersion());
  }

  private static <T> @NotNull Map<UUID, List<T>> group(
      @NotNull List<T> rows, @NotNull Function<T, UUID> key, @NotNull Comparator<T> order) {
    return rows.stream().sorted(order).collect(Collectors.groupingBy(key));
  }

  private static void addIfPresent(@NotNull Set<UUID> target, @Nullable UUID id) {
    if (id != null) {
      target.add(id);
    }
  }
}
