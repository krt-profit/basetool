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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DeliveryStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.LossReason;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What a deal shows and what the caller may do next, built for a whole page at once (REQ-PUR-007,
 * REQ-PUR-012, REQ-PUR-015, REQ-PUR-016, REQ-PUR-021).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingDealAssemblerTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID PARTNER = UUID.randomUUID();
  private static final UUID NEED = UUID.randomUUID();
  private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

  @Mock private PurchasingDealTrancheRepository trancheRepository;
  @Mock private PurchasingDealDeliveryRepository deliveryRepository;
  @Mock private PurchasingDealSettlementRepository settlementRepository;
  @Mock private PurchasingPartnerRepository partnerRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private UserRepository userRepository;
  @Mock private PurchasingAccessPolicy accessPolicy;
  @Mock private AuthHelperService authHelperService;

  private PurchasingDealAssembler assembler;

  @BeforeEach
  void setUp() {
    assembler =
        new PurchasingDealAssembler(
            trancheRepository,
            deliveryRepository,
            settlementRepository,
            partnerRepository,
            orgUnitRepository,
            locationRepository,
            userRepository,
            accessPolicy,
            authHelperService);
  }

  private PurchasingDeal deal(DealStatus status) {
    PurchasingDeal deal = new PurchasingDeal();
    deal.setId(UUID.randomUUID());
    deal.setDealNo(7L);
    deal.setNeedId(NEED);
    deal.setRaisingOrgUnitId(UNIT);
    deal.setPartnerId(PARTNER);
    deal.setDelegateUserId(CALLER);
    deal.setAmount(10.0);
    deal.setUnitPrice(1000L);
    deal.setTotal(10000L);
    deal.setPaymentModel(PaymentModel.PARTIAL);
    deal.setRiskClass(RiskClassCode.A);
    deal.setStatus(status);
    deal.setVersion(4L);
    deal.setCreatedAt(T0);
    return deal;
  }

  private PurchasingDealTranche tranche(
      PurchasingDeal deal, TrancheState state, long amount, int order) {
    PurchasingDealTranche tranche = new PurchasingDealTranche();
    tranche.setId(UUID.randomUUID());
    tranche.setDealId(deal.getId());
    tranche.setKind(TrancheKind.ADVANCE);
    tranche.setAmount(amount);
    tranche.setState(state);
    tranche.setCreatedAt(T0.plusSeconds(order));
    return tranche;
  }

  private PurchasingDealDelivery delivery(
      PurchasingDeal deal, DeliveryStatus status, double amount, Double accepted, int order) {
    PurchasingDealDelivery delivery = new PurchasingDealDelivery();
    delivery.setId(UUID.randomUUID());
    delivery.setDealId(deal.getId());
    delivery.setAmount(amount);
    delivery.setStatus(status);
    delivery.setAcceptedAmount(accepted);
    delivery.setCreatedAt(T0.plusSeconds(order));
    return delivery;
  }

  private PurchasingPartner partner() {
    PurchasingPartner partner = new PurchasingPartner();
    partner.setId(PARTNER);
    partner.setKind(PartnerKind.ORG);
    partner.setName("Nine Tails Traders");
    partner.setOrgTag("NTT");
    partner.setChannel(PartnerChannel.DISCORD);
    partner.setContactVia(PartnerContact.HB);
    partner.setStatus(PartnerStatus.ACTIVE);
    partner.setConditionsNote("pays late");
    partner.setReputationNote("reliable");
    return partner;
  }

  private void callerIsDelegate(boolean active) {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(accessPolicy.isActiveDelegate()).thenReturn(active);
  }

  @Test
  void noDealsYieldNoDtosAndNoLookups() {
    assertThat(assembler.toDtos(List.of())).isEmpty();

    verifyNoInteractions(
        trancheRepository,
        deliveryRepository,
        settlementRepository,
        partnerRepository,
        orgUnitRepository,
        locationRepository,
        userRepository,
        accessPolicy,
        authHelperService);
  }

  @Test
  void requestedAndFundedSumsAndAcceptedAndPendingAmountsAreComputedFromTheChildren() {
    PurchasingDeal deal = deal(DealStatus.IN_DELIVERY);
    when(trancheRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(
            List.of(
                tranche(deal, TrancheState.CONFIRMED, 200L, 2),
                tranche(deal, TrancheState.REQUESTED, 100L, 1),
                tranche(deal, TrancheState.REFUSED, 50L, 3),
                tranche(deal, TrancheState.CANCELLED, 70L, 4)));
    when(deliveryRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(
            List.of(
                delivery(deal, DeliveryStatus.ACCEPTED, 10.0, 10.0, 1),
                delivery(deal, DeliveryStatus.PARTIALLY_ACCEPTED, 8.0, 5.0, 2),
                delivery(deal, DeliveryStatus.RECORDED, 4.0, null, 3),
                delivery(deal, DeliveryStatus.REJECTED, 3.0, null, 4)));

    DealDto dto = assembler.toDto(deal);

    assertThat(dto.requestedAmount()).isEqualTo(300L);
    assertThat(dto.fundedAmount()).isEqualTo(200L);
    assertThat(dto.acceptedAmount()).isEqualTo(15.0);
    assertThat(dto.pendingAmount()).isEqualTo(4.0);
    assertThat(dto.tranches()).extracting(TrancheDto::amount).containsExactly(100L, 200L, 50L, 70L);
    assertThat(dto.deliveries()).hasSize(4);
    assertThat(dto.version()).isEqualTo(4L);
    assertThat(dto.createdAt()).isEqualTo(T0);
  }

  @Test
  void aSettlementIsOverdueOnlyOnADeliveredOrFailedDealPastItsDueDate() {
    Instant past = Instant.now().minus(2, ChronoUnit.DAYS);
    Instant future = Instant.now().plus(2, ChronoUnit.DAYS);
    PurchasingDeal deliveredPast = deal(DealStatus.DELIVERED);
    deliveredPast.setSettlementDueAt(past);
    PurchasingDeal failedPast = deal(DealStatus.FAILED);
    failedPast.setSettlementDueAt(past);
    PurchasingDeal settledPast = deal(DealStatus.SETTLED);
    settledPast.setSettlementDueAt(past);
    PurchasingDeal deliveredFuture = deal(DealStatus.DELIVERED);
    deliveredFuture.setSettlementDueAt(future);
    PurchasingDeal deliveredUndated = deal(DealStatus.DELIVERED);

    List<DealDto> dtos =
        assembler.toDtos(
            List.of(deliveredPast, failedPast, settledPast, deliveredFuture, deliveredUndated));

    assertThat(dtos).extracting(DealDto::overdue).containsExactly(true, true, false, false, false);
    assertThat(dtos)
        .extracting(DealDto::id)
        .containsExactly(
            deliveredPast.getId(),
            failedPast.getId(),
            settledPast.getId(),
            deliveredFuture.getId(),
            deliveredUndated.getId());
  }

  @Test
  void partnerNotesAreHiddenFromCallersWhoMayNotReadThem() {
    PurchasingDeal deal = deal(DealStatus.DRAFT);
    when(partnerRepository.findAllById(Set.of(PARTNER))).thenReturn(List.of(partner()));

    DealPartnerDto hidden = assembler.toDto(deal).partner();

    assertThat(hidden.name()).isEqualTo("Nine Tails Traders");
    assertThat(hidden.orgTag()).isEqualTo("NTT");
    assertThat(hidden.status()).isEqualTo(PartnerStatus.ACTIVE);
    assertThat(hidden.conditionsNote()).isNull();
    assertThat(hidden.reputationNote()).isNull();
  }

  @Test
  void partnerNotesAreShownToDelegatesAndTheGoverningLeadership() {
    PurchasingDeal deal = deal(DealStatus.DRAFT);
    when(partnerRepository.findAllById(Set.of(PARTNER))).thenReturn(List.of(partner()));
    when(accessPolicy.canReadPartnerNotes()).thenReturn(true);

    DealPartnerDto shown = assembler.toDto(deal).partner();

    assertThat(shown.conditionsNote()).isEqualTo("pays late");
    assertThat(shown.reputationNote()).isEqualTo("reliable");
  }

  @Test
  void aMissingPartnerRowShowsThePlaceholder() {
    PurchasingDeal deal = deal(DealStatus.DRAFT);
    when(accessPolicy.canReadPartnerNotes()).thenReturn(true);

    DealPartnerDto placeholder = assembler.toDto(deal).partner();

    assertThat(placeholder.id()).isEqualTo(new UUID(0, 0));
    assertThat(placeholder.name()).isEqualTo(PurchasingPartner.ERASED_NAME);
    assertThat(placeholder.kind()).isNull();
    assertThat(placeholder.status()).isNull();
    assertThat(placeholder.conditionsNote()).isNull();
    assertThat(placeholder.reputationNote()).isNull();
  }

  @Test
  void anErasedPartnerKeepsItsStoredPlaceholderAndShowsNoNotes() {
    PurchasingDeal deal = deal(DealStatus.DELIVERED);
    PurchasingPartner erased = partner();
    erased.setErased(true);
    erased.setName(PurchasingPartner.ERASED_NAME);
    erased.setOrgTag(null);
    erased.setConditionsNote(null);
    erased.setReputationNote(null);
    when(partnerRepository.findAllById(Set.of(PARTNER))).thenReturn(List.of(erased));
    when(accessPolicy.canReadPartnerNotes()).thenReturn(true);

    DealPartnerDto dto = assembler.toDto(deal).partner();

    assertThat(dto.id()).isEqualTo(PARTNER);
    assertThat(dto.name()).isEqualTo(PurchasingPartner.ERASED_NAME);
    assertThat(dto.orgTag()).isNull();
    assertThat(dto.conditionsNote()).isNull();
    assertThat(dto.reputationNote()).isNull();
  }

  @Test
  void theActiveDelegateEditsAndCancelsADraftButDoesNotRecordDeliveries() {
    callerIsDelegate(true);
    DealDto dto = assembler.toDto(deal(DealStatus.DRAFT));

    assertThat(dto.isMine()).isTrue();
    assertThat(dto.canEdit()).isTrue();
    assertThat(dto.canRequestTranche()).isTrue();
    assertThat(dto.canCancel()).isTrue();
    assertThat(dto.canRecordDelivery()).isFalse();
    assertThat(dto.canCloseDelivery()).isFalse();
    assertThat(dto.canSettle()).isFalse();
  }

  @Test
  void aSubmittedDealCanBeCancelledAndFundedFurtherButNotEdited() {
    callerIsDelegate(true);
    DealDto dto = assembler.toDto(deal(DealStatus.SUBMITTED));

    assertThat(dto.canEdit()).isFalse();
    assertThat(dto.canRequestTranche()).isTrue();
    assertThat(dto.canCancel()).isTrue();
    assertThat(dto.canRecordDelivery()).isFalse();
  }

  @Test
  void noFurtherTrancheCanBeRequestedOnceTheLiveTranchesCoverTheTotal() {
    callerIsDelegate(true);
    PurchasingDeal deal = deal(DealStatus.SUBMITTED);
    when(trancheRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(
            List.of(
                tranche(deal, TrancheState.CONFIRMED, 6000L, 1),
                tranche(deal, TrancheState.REQUESTED, 4000L, 2),
                tranche(deal, TrancheState.REFUSED, 9000L, 3)));

    assertThat(assembler.toDto(deal).canRequestTranche()).isFalse();
  }

  @Test
  void aFundedDealRecordsAndClosesDeliveriesAndCannotBeCancelled() {
    callerIsDelegate(true);
    DealDto dto = assembler.toDto(deal(DealStatus.FUNDED));

    assertThat(dto.canRecordDelivery()).isTrue();
    assertThat(dto.canCloseDelivery()).isTrue();
    assertThat(dto.canCancel()).isFalse();
    assertThat(dto.canEdit()).isFalse();
    assertThat(dto.canSettle()).isFalse();
  }

  @Test
  void aPendingDeliveryBlocksClosingTheDeliveryPhase() {
    callerIsDelegate(true);
    PurchasingDeal deal = deal(DealStatus.IN_DELIVERY);
    when(deliveryRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(List.of(delivery(deal, DeliveryStatus.RECORDED, 4.0, null, 1)));

    DealDto dto = assembler.toDto(deal);

    assertThat(dto.canCloseDelivery()).isFalse();
    assertThat(dto.canRecordDelivery()).isTrue();
  }

  @Test
  void nothingIsLeftToRecordOnceTheWholeAmountIsAccepted() {
    callerIsDelegate(true);
    PurchasingDeal deal = deal(DealStatus.IN_DELIVERY);
    when(deliveryRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(List.of(delivery(deal, DeliveryStatus.ACCEPTED, 10.0, 10.0, 1)));

    DealDto dto = assembler.toDto(deal);

    assertThat(dto.canRecordDelivery()).isFalse();
    assertThat(dto.canCloseDelivery()).isTrue();
  }

  @Test
  void aRejectedDeliveryFreesItsAmountForANewOne() {
    callerIsDelegate(true);
    PurchasingDeal deal = deal(DealStatus.IN_DELIVERY);
    when(deliveryRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(List.of(delivery(deal, DeliveryStatus.REJECTED, 10.0, null, 1)));

    assertThat(assembler.toDto(deal).canRecordDelivery()).isTrue();
  }

  @Test
  void aClosedDeliveryPhaseOffersNeitherRecordingNorClosing() {
    callerIsDelegate(true);
    PurchasingDeal deal = deal(DealStatus.IN_DELIVERY);
    deal.setDeliveryClosed(true);

    DealDto dto = assembler.toDto(deal);

    assertThat(dto.deliveryClosed()).isTrue();
    assertThat(dto.canRecordDelivery()).isFalse();
    assertThat(dto.canCloseDelivery()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = DealStatus.class,
      names = {"DELIVERED", "FAILED"})
  void theDelegateSettlesADeliveredOrFailedDealUntilItIsSettled(DealStatus status) {
    callerIsDelegate(true);
    PurchasingDeal open = deal(status);
    PurchasingDeal done = deal(status);
    PurchasingDealSettlement settled = new PurchasingDealSettlement();
    settled.setId(UUID.randomUUID());
    settled.setDealId(done.getId());
    settled.setSettledAt(T0);
    PurchasingDealSettlement recorded = new PurchasingDealSettlement();
    recorded.setId(UUID.randomUUID());
    recorded.setDealId(open.getId());
    when(settlementRepository.findByDealIdIn(Set.of(open.getId(), done.getId())))
        .thenReturn(List.of(settled, recorded));

    List<DealDto> dtos = assembler.toDtos(List.of(open, done));

    assertThat(dtos).extracting(DealDto::canSettle).containsExactly(true, false);
    assertThat(dtos.getFirst().canCancel()).isFalse();
  }

  @Test
  void aSettlementIsMappedWithItsMoneyAndLoss() {
    callerIsDelegate(true);
    PurchasingDeal deal = deal(DealStatus.SETTLED);
    PurchasingDealSettlement settlement = new PurchasingDealSettlement();
    settlement.setId(UUID.randomUUID());
    settlement.setDealId(deal.getId());
    settlement.setMoneyReceived(10000L);
    settlement.setMoneyPaid(9000L);
    settlement.setRemainder(500L);
    settlement.setLossAmount(500L);
    settlement.setLossReason(LossReason.NON_DELIVERY);
    settlement.setSettledAt(T0);
    settlement.setVersion(2L);
    when(settlementRepository.findByDealIdIn(Set.of(deal.getId()))).thenReturn(List.of(settlement));

    DealDto dto = assembler.toDto(deal);

    assertThat(dto.settlement()).isNotNull();
    assertThat(dto.settlement().moneyReceived()).isEqualTo(10000L);
    assertThat(dto.settlement().moneyPaid()).isEqualTo(9000L);
    assertThat(dto.settlement().remainder()).isEqualTo(500L);
    assertThat(dto.settlement().lossAmount()).isEqualTo(500L);
    assertThat(dto.settlement().lossReason()).isEqualTo(LossReason.NON_DELIVERY);
    assertThat(dto.settlement().settledAt()).isEqualTo(T0);
    assertThat(dto.settlement().version()).isEqualTo(2L);
    assertThat(dto.canSettle()).isFalse();
  }

  @Test
  void aCallerWhoIsNotTheDelegateMayDoNothingEvenAsAnActiveDelegate() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));
    PurchasingDeal draft = deal(DealStatus.DRAFT);
    PurchasingDeal inDelivery = deal(DealStatus.IN_DELIVERY);
    PurchasingDeal delivered = deal(DealStatus.DELIVERED);

    List<DealDto> dtos = assembler.toDtos(List.of(draft, inDelivery, delivered));

    assertThat(dtos).extracting(DealDto::isMine).containsOnly(false);
    assertThat(dtos).extracting(DealDto::canEdit).containsOnly(false);
    assertThat(dtos).extracting(DealDto::canRequestTranche).containsOnly(false);
    assertThat(dtos).extracting(DealDto::canRecordDelivery).containsOnly(false);
    assertThat(dtos).extracting(DealDto::canCloseDelivery).containsOnly(false);
    assertThat(dtos).extracting(DealDto::canSettle).containsOnly(false);
    assertThat(dtos).extracting(DealDto::canCancel).containsOnly(false);
  }

  @Test
  void aFormerDelegateStillCancelsAndSettlesHerDealsButTradesNoFurther() {
    callerIsDelegate(false);
    PurchasingDeal draft = deal(DealStatus.DRAFT);
    PurchasingDeal delivered = deal(DealStatus.DELIVERED);
    PurchasingDeal inDelivery = deal(DealStatus.IN_DELIVERY);

    List<DealDto> dtos = assembler.toDtos(List.of(draft, delivered, inDelivery));

    assertThat(dtos.get(0).isMine()).isTrue();
    assertThat(dtos.get(0).canEdit()).isFalse();
    assertThat(dtos.get(0).canRequestTranche()).isFalse();
    assertThat(dtos.get(0).canCancel()).isTrue();
    assertThat(dtos.get(1).canSettle()).isTrue();
    assertThat(dtos.get(2).canRecordDelivery()).isFalse();
    assertThat(dtos.get(2).canCloseDelivery()).isFalse();
  }

  @Test
  void aDeliveryCanBeAcceptedByTheUnitManagerWhoIsNotTheDelegate() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));
    PurchasingDeal deal = deal(DealStatus.IN_DELIVERY);
    when(accessPolicy.canAcceptDeliveries(deal)).thenReturn(true);
    when(deliveryRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(
            List.of(
                delivery(deal, DeliveryStatus.RECORDED, 4.0, null, 1),
                delivery(deal, DeliveryStatus.ACCEPTED, 3.0, 3.0, 2)));

    DealDto dto = assembler.toDto(deal);

    assertThat(dto.deliveries()).extracting(DeliveryDto::canAccept).containsExactly(true, false);
  }

  @Test
  void theDelegateNeverAcceptsHerOwnDeliveries() {
    callerIsDelegate(true);
    PurchasingDeal deal = deal(DealStatus.IN_DELIVERY);
    when(accessPolicy.canAcceptDeliveries(deal)).thenReturn(true);
    when(deliveryRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(List.of(delivery(deal, DeliveryStatus.RECORDED, 4.0, null, 1)));

    assertThat(assembler.toDto(deal).deliveries())
        .extracting(DeliveryDto::canAccept)
        .containsExactly(false);
  }

  @Test
  void aCallerWithoutTheUnitManagerRoleAcceptsNoDelivery() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    PurchasingDeal deal = deal(DealStatus.IN_DELIVERY);
    when(accessPolicy.canAcceptDeliveries(deal)).thenReturn(false);
    when(deliveryRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(List.of(delivery(deal, DeliveryStatus.RECORDED, 4.0, null, 1)));

    assertThat(assembler.toDto(deal).deliveries())
        .extracting(DeliveryDto::canAccept)
        .containsExactly(false);
  }

  @Test
  void theDelegateNameComesFromTheUserRepositoryInOneBatch() {
    User pilot = new User();
    pilot.setId(CALLER);
    pilot.setUsername("pilot");
    pilot.setDisplayName("Pilot One");
    PurchasingDeal first = deal(DealStatus.DRAFT);
    PurchasingDeal second = deal(DealStatus.DRAFT);
    when(userRepository.findAllById(Set.of(CALLER))).thenReturn(List.of(pilot));

    List<DealDto> dtos = assembler.toDtos(List.of(first, second));

    assertThat(dtos).extracting(DealDto::delegateName).containsExactly("Pilot One", "Pilot One");
  }

  @Test
  void aDealWithoutADelegateOrWithAnUnknownOneHasNoDelegateName() {
    PurchasingDeal orphan = deal(DealStatus.CANCELLED);
    orphan.setDelegateUserId(null);
    PurchasingDeal unknown = deal(DealStatus.CANCELLED);
    unknown.setDelegateUserId(UUID.randomUUID());
    when(userRepository.findAllById(Set.of(unknown.getDelegateUserId()))).thenReturn(List.of());

    List<DealDto> dtos = assembler.toDtos(List.of(orphan, unknown));

    assertThat(dtos).extracting(DealDto::delegateName).containsExactly(null, null);
  }

  @Test
  void noUserLookupHappensWhenNoDealHasADelegate() {
    PurchasingDeal orphan = deal(DealStatus.CANCELLED);
    orphan.setDelegateUserId(null);

    assembler.toDto(orphan);

    verifyNoInteractions(userRepository);
  }

  @Test
  void unitAndLocationNamesAreResolvedAndFallBackToADash() {
    PurchasingDeal deal = deal(DealStatus.IN_DELIVERY);
    Squadron unit = new Squadron();
    unit.setId(UNIT);
    unit.setName("Staffel Alpha");
    UUID knownLocation = UUID.randomUUID();
    UUID goneLocation = UUID.randomUUID();
    Location location = new Location();
    location.setId(knownLocation);
    location.setName("Port Olisar");
    PurchasingDealDelivery atKnown = delivery(deal, DeliveryStatus.RECORDED, 1.0, null, 1);
    atKnown.setLocationId(knownLocation);
    PurchasingDealDelivery atGone = delivery(deal, DeliveryStatus.RECORDED, 1.0, null, 2);
    atGone.setLocationId(goneLocation);
    PurchasingDealDelivery nowhere = delivery(deal, DeliveryStatus.RECORDED, 1.0, null, 3);
    when(orgUnitRepository.findAllById(Set.of(UNIT))).thenReturn(List.of(unit));
    when(deliveryRepository.findByDealIdIn(Set.of(deal.getId())))
        .thenReturn(List.of(atKnown, atGone, nowhere));
    when(locationRepository.findAllById(Set.of(knownLocation, goneLocation)))
        .thenReturn(List.of(location));

    DealDto dto = assembler.toDto(deal);

    assertThat(dto.raisingOrgUnit().id()).isEqualTo(UNIT);
    assertThat(dto.raisingOrgUnit().name()).isEqualTo("Staffel Alpha");
    assertThat(dto.deliveries())
        .extracting(d -> d.location() == null ? null : d.location().name())
        .containsExactly("Port Olisar", "—", null);
  }

  @Test
  void aMissingRaisingUnitShowsADash() {
    DealDto dto = assembler.toDto(deal(DealStatus.DRAFT));

    assertThat(dto.raisingOrgUnit().name()).isEqualTo("—");
  }

  @Test
  void aMaterialDealShowsTheUexReferencePriceAndTheQuantityType() {
    Material scu = new Material();
    scu.setId(UUID.randomUUID());
    scu.setName("Quantainium");
    scu.setQuantityType(QuantityType.SCU);
    scu.setPriceBuy(12.5);
    Material piece = new Material();
    piece.setId(UUID.randomUUID());
    piece.setName("Medpen");
    piece.setQuantityType(QuantityType.PIECE);
    PurchasingDeal bulk = deal(DealStatus.DRAFT);
    bulk.setMaterial(scu);
    PurchasingDeal counted = deal(DealStatus.DRAFT);
    counted.setMaterial(piece);

    List<DealDto> dtos = assembler.toDtos(List.of(bulk, counted));

    assertThat(dtos.get(0).quantityType()).isEqualTo("SCU");
    assertThat(dtos.get(0).uexReferencePrice()).isEqualTo(12.5);
    assertThat(dtos.get(0).material().name()).isEqualTo("Quantainium");
    assertThat(dtos.get(1).quantityType()).isEqualTo("PIECE");
    assertThat(dtos.get(1).uexReferencePrice()).isNull();
  }

  @Test
  void aGameItemDealIsCountedInItemsAndCarriesTheQualityTier() {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setName("Behring P4-AR");
    QualityTier tier = new QualityTier();
    tier.setId(UUID.randomUUID());
    tier.setCode("HIGH");
    tier.setMinQuality(700);
    tier.setLabelDe("Hoch");
    tier.setLabelEn("High");
    PurchasingDeal deal = deal(DealStatus.DRAFT);
    deal.setGameItem(item);
    deal.setQualityTier(tier);

    DealDto dto = assembler.toDto(deal);

    assertThat(dto.quantityType()).isEqualTo("ITEM");
    assertThat(dto.gameItem().name()).isEqualTo("Behring P4-AR");
    assertThat(dto.material()).isNull();
    assertThat(dto.qualityTier().code()).isEqualTo("HIGH");
    assertThat(dto.qualityTier().minQuality()).isEqualTo(700);
    assertThat(dto.qualityTier().labelDe()).isEqualTo("Hoch");
    assertThat(dto.qualityTier().labelEn()).isEqualTo("High");
  }

  @Test
  void aDealWithoutANumberYetShowsZeroAndHeaderValuesAreCopied() {
    PurchasingDeal deal = deal(DealStatus.DRAFT);
    deal.setDealNo(null);
    deal.setEscortWish(true);
    UUID mission = UUID.randomUUID();
    deal.setMissionId(mission);

    DealDto dto = assembler.toDto(deal);

    assertThat(dto.dealNo()).isZero();
    assertThat(dto.needId()).isEqualTo(NEED);
    assertThat(dto.amount()).isEqualTo(10.0);
    assertThat(dto.unitPrice()).isEqualTo(1000L);
    assertThat(dto.total()).isEqualTo(10000L);
    assertThat(dto.paymentModel()).isEqualTo(PaymentModel.PARTIAL);
    assertThat(dto.riskClass()).isEqualTo(RiskClassCode.A);
    assertThat(dto.escortWish()).isTrue();
    assertThat(dto.missionId()).isEqualTo(mission);
    assertThat(dto.status()).isEqualTo(DealStatus.DRAFT);
  }

  @Test
  void allChildrenOfAPageAreLoadedInOneStatementPerTable() {
    PurchasingDeal first = deal(DealStatus.DRAFT);
    PurchasingDeal second = deal(DealStatus.SUBMITTED);
    when(trancheRepository.findByDealIdIn(any()))
        .thenReturn(List.of(tranche(second, TrancheState.REQUESTED, 100L, 1)));

    List<DealDto> dtos = assembler.toDtos(List.of(first, second));

    assertThat(dtos.get(0).tranches()).isEmpty();
    assertThat(dtos.get(1).tranches()).hasSize(1);
    assertThat(dtos.get(1).requestedAmount()).isEqualTo(100L);
    verify(trancheRepository, times(1)).findByDealIdIn(any());
    verify(deliveryRepository, times(1)).findByDealIdIn(any());
    verify(settlementRepository, times(1)).findByDealIdIn(any());
    verify(partnerRepository, times(1)).findAllById(any());
  }
}
