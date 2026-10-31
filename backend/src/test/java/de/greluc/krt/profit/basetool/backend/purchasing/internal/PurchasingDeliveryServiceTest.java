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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DeliveryStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RejectReason;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyNeeds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Recording, deciding and closing the deliveries of a deal (REQ-PUR-015, REQ-PUR-016, REQ-PUR-017,
 * REQ-PUR-024).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingDeliveryServiceTest {

  private static final UUID DEAL = UUID.randomUUID();
  private static final UUID DELIVERY = UUID.randomUUID();
  private static final UUID NEED = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID DELEGATE = UUID.randomUUID();
  private static final UUID ACCEPTOR = UUID.randomUUID();
  private static final UUID LOCATION = UUID.randomUUID();
  private static final UUID BOOKED_ROW = UUID.randomUUID();

  @Mock private PurchasingDealRepository dealRepository;
  @Mock private PurchasingDealDeliveryRepository deliveryRepository;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingAccessPolicy accessPolicy;
  @Mock private PurchasingDealService dealService;
  @Mock private PurchasingDealAssembler assembler;
  @Mock private StockCommands stockCommands;
  @Mock private SupplyNeeds supplyNeeds;
  @Mock private LocationRepository locationRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private AuditRecorder auditRecorder;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private DealDto dealDto;

  private SimpleMeterRegistry meterRegistry;
  private PurchasingDeliveryService service;
  private PurchasingDeal deal;
  private Material material;
  private Location location;
  private Squadron unit;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service =
        new PurchasingDeliveryService(
            dealRepository,
            deliveryRepository,
            settingsService,
            accessPolicy,
            dealService,
            assembler,
            stockCommands,
            supplyNeeds,
            locationRepository,
            orgUnitRepository,
            authHelperService,
            auditRecorder,
            eventPublisher,
            meterRegistry);
    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Quantanium");
    location = new Location();
    location.setId(LOCATION);
    unit = new Squadron();
    unit.setId(UNIT);
    unit.setName("Alpha");
    deal = new PurchasingDeal();
    deal.setId(DEAL);
    deal.setDealNo(42L);
    deal.setNeedId(NEED);
    deal.setRaisingOrgUnitId(UNIT);
    deal.setPartnerId(UUID.randomUUID());
    deal.setDelegateUserId(DELEGATE);
    deal.setMaterial(material);
    deal.setAmount(10.0);
    deal.setUnitPrice(100L);
    deal.setTotal(1000L);
    deal.setStatus(DealStatus.FUNDED);
  }

  @Test
  void recordMovesAFundedDealIntoDeliveryAndTellsTheRaisingUnit() {
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL)).thenReturn(List.of());
    when(locationRepository.existsById(LOCATION)).thenReturn(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(DELEGATE));
    when(deliveryRepository.saveAndFlush(any(PurchasingDealDelivery.class)))
        .thenAnswer(invocation -> {
          PurchasingDealDelivery saved = invocation.getArgument(0);
          saved.setId(DELIVERY);
          return saved;
        });
    when(orgUnitRepository.findById(UNIT)).thenReturn(Optional.of(unit));
    when(assembler.toDto(deal)).thenReturn(dealDto);

    DealDto result = service.record(DEAL, new RecordDeliveryRequest(4.0, LOCATION));

    assertThat(result).isSameAs(dealDto);
    assertThat(deal.getStatus()).isEqualTo(DealStatus.IN_DELIVERY);
    ArgumentCaptor<PurchasingDealDelivery> saved =
        ArgumentCaptor.forClass(PurchasingDealDelivery.class);
    verify(deliveryRepository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getStatus()).isEqualTo(DeliveryStatus.RECORDED);
    assertThat(saved.getValue().getAmount()).isEqualTo(4.0);
    assertThat(saved.getValue().getRecordedBy()).isEqualTo(DELEGATE);
    assertThat(saved.getValue().getLocationId()).isEqualTo(LOCATION);
    verify(dealRepository).saveAndFlush(deal);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_DELIVERY_RECORDED),
            eq(DEAL),
            eq("Deal 42"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .contains("delivery=" + DELIVERY, "deliveredAmount=4.0")
        .doesNotContain("Alpha");
    NoticeEvent notice = publishedNotice();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_DELIVERY_RECORDED);
    assertThat(notice.entityId()).isEqualTo(DEAL);
    assertThat(notice.actorSub()).isEqualTo(DELEGATE);
    assertThat(notice.contextOrgUnits())
        .containsEntry(
            NotificationContextRole.RESPONSIBLE, new OrgUnitRef(UNIT, OrgUnitKind.SQUADRON));
    assertThat(notice.renderParams())
        .containsEntry("deal", "42")
        .containsEntry("entry", "Quantanium")
        .containsEntry("amount", "4");
    assertThat(counter("delivery_recorded")).isEqualTo(1.0);
  }

  @Test
  void recordRefusesMoreThanTheDealStillLacks() {
    PurchasingDealDelivery earlier = delivery(8.0, DeliveryStatus.RECORDED);
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL)).thenReturn(List.of(earlier));

    assertThatThrownBy(() -> service.record(DEAL, new RecordDeliveryRequest(3.0, null)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            problem ->
                assertThat(problem.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_DELIVERY_EXCEEDS_UNDELIVERED));

    verify(deliveryRepository, never()).saveAndFlush(any());
    assertThat(deal.getStatus()).isEqualTo(DealStatus.FUNDED);
  }

  @Test
  void recordCountsOnlyTheAcceptedPartOfADecidedDelivery() {
    PurchasingDealDelivery decided = delivery(8.0, DeliveryStatus.PARTIALLY_ACCEPTED);
    decided.setAcceptedAmount(2.0);
    deal.setStatus(DealStatus.IN_DELIVERY);
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL)).thenReturn(List.of(decided));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(DELEGATE));
    when(deliveryRepository.saveAndFlush(any(PurchasingDealDelivery.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(orgUnitRepository.findById(UNIT)).thenReturn(Optional.of(unit));
    when(assembler.toDto(deal)).thenReturn(dealDto);

    service.record(DEAL, new RecordDeliveryRequest(8.0, null));

    verify(deliveryRepository).saveAndFlush(any(PurchasingDealDelivery.class));
    verify(dealRepository, never()).saveAndFlush(any());
  }

  @Test
  void recordRefusesADealThatTakesNoDelivery() {
    deal.setStatus(DealStatus.DELIVERED);
    when(dealService.lock(DEAL)).thenReturn(deal);

    assertThatThrownBy(() -> service.record(DEAL, new RecordDeliveryRequest(1.0, null)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            problem ->
                assertThat(problem.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_DEAL_STATE));

    deal.setStatus(DealStatus.IN_DELIVERY);
    deal.setDeliveryClosed(true);
    assertThatThrownBy(() -> service.record(DEAL, new RecordDeliveryRequest(1.0, null)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            problem ->
                assertThat(problem.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_DEAL_STATE));
    verifyNoInteractions(deliveryRepository);
  }

  @Test
  void recordRefusesAFractionForAWholeUnitEntry() {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setName("Medpen");
    deal.setMaterial(null);
    deal.setGameItem(item);
    when(dealService.lock(DEAL)).thenReturn(deal);

    assertThatThrownBy(() -> service.record(DEAL, new RecordDeliveryRequest(1.5, null)))
        .isInstanceOf(BadRequestException.class);

    verifyNoInteractions(deliveryRepository);
  }

  @Test
  void recordRefusesAnUnknownLocation() {
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL)).thenReturn(List.of());
    when(locationRepository.existsById(LOCATION)).thenReturn(false);

    assertThatThrownBy(() -> service.record(DEAL, new RecordDeliveryRequest(1.0, LOCATION)))
        .isInstanceOf(BadRequestException.class);

    verify(deliveryRepository, never()).saveAndFlush(any());
  }

  @Test
  void recordRefusesACallerWhoIsNotTheDelegate() {
    when(dealService.lock(DEAL)).thenReturn(deal);
    doThrow(new AccessDeniedException("not the delegate")).when(dealService).requireOwnDeal(deal);

    assertThatThrownBy(() -> service.record(DEAL, new RecordDeliveryRequest(1.0, null)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(deliveryRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void acceptingAllBooksTheGoodsIntoThePoolAndClosesTheDelivery() {
    PurchasingDealDelivery recorded = delivery(10.0, DeliveryStatus.RECORDED);
    recorded.setVersion(3L);
    deal.setStatus(DealStatus.IN_DELIVERY);
    PurchasingSetting setting = new PurchasingSetting();
    setting.setSettlementDueDays(5);
    allowAcceptance(recorded);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACCEPTOR));
    when(locationRepository.findById(LOCATION)).thenReturn(Optional.of(location));
    when(orgUnitRepository.findById(UNIT)).thenReturn(Optional.of(unit));
    when(stockCommands.bookIntoPool(
            eq(ACCEPTOR), eq(unit), any(StockLot.class), eq(10.0), eq(StockBookingReason.PURCHASE)))
        .thenReturn(BOOKED_ROW);
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL)).thenReturn(List.of(recorded));
    when(settingsService.current()).thenReturn(setting);
    when(assembler.toDto(deal)).thenReturn(dealDto);
    Instant before = Instant.now();

    DealDto result =
        service.accept(
            DEAL, DELIVERY, new AcceptDeliveryRequest(10.0, 0.0, null, 500, LOCATION, 3L));

    assertThat(result).isSameAs(dealDto);
    ArgumentCaptor<StockLot> lot = ArgumentCaptor.forClass(StockLot.class);
    verify(stockCommands)
        .bookIntoPool(
            eq(ACCEPTOR), eq(unit), lot.capture(), eq(10.0), eq(StockBookingReason.PURCHASE));
    assertThat(lot.getValue().material()).isSameAs(material);
    assertThat(lot.getValue().quality()).isEqualTo(500);
    assertThat(lot.getValue().stolen()).isFalse();
    verify(stockCommands).lockLots(ACCEPTOR, List.of(lot.getValue().key()));
    verify(supplyNeeds).creditPurchase(NEED, 10.0);
    assertThat(recorded.getStatus()).isEqualTo(DeliveryStatus.ACCEPTED);
    assertThat(recorded.getBookedRowId()).isEqualTo(BOOKED_ROW);
    assertThat(recorded.getAcceptedBy()).isEqualTo(ACCEPTOR);
    assertThat(recorded.getAcceptedAmount()).isEqualTo(10.0);
    assertThat(recorded.getRejectedAmount()).isZero();
    assertThat(recorded.getMeasuredQuality()).isEqualTo(500);
    assertThat(deal.isDeliveryClosed()).isTrue();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.DELIVERED);
    assertThat(deal.getSettlementDueAt())
        .isBetween(before.plus(Duration.ofDays(5)), Instant.now().plus(Duration.ofDays(5)));
    assertThat(deal.getOverdueNotifiedAt()).isNull();
    verify(dealRepository).saveAndFlush(deal);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_DELIVERY_ACCEPTED), eq(DEAL), eq("Deal 42"), isNull(), any());
    verify(auditRecorder)
        .record(eq(AuditEventType.DEAL_DELIVERY_CLOSED), eq(DEAL), eq("Deal 42"), isNull(), any());
    NoticeEvent notice = publishedNotice();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_DELIVERY_DECIDED);
    assertThat(notice.contextRecipientUserId()).isEqualTo(DELEGATE);
    assertThat(notice.actorSub()).isEqualTo(ACCEPTOR);
    assertThat(notice.renderParams()).containsEntry("outcomeCode", "ACCEPTED");
    assertThat(counter("delivery_accepted")).isEqualTo(1.0);
    assertThat(counter("delivery_closed")).isEqualTo(1.0);
  }

  @Test
  void acceptingPartBooksOnlyThatPartAndKeepsTheDealOpen() {
    PurchasingDealDelivery recorded = delivery(10.0, DeliveryStatus.RECORDED);
    recorded.setVersion(1L);
    deal.setStatus(DealStatus.IN_DELIVERY);
    allowAcceptance(recorded);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACCEPTOR));
    when(locationRepository.findById(LOCATION)).thenReturn(Optional.of(location));
    when(orgUnitRepository.findById(UNIT)).thenReturn(Optional.of(unit));
    when(stockCommands.bookIntoPool(
            eq(ACCEPTOR), eq(unit), any(StockLot.class), eq(6.0), eq(StockBookingReason.PURCHASE)))
        .thenReturn(BOOKED_ROW);
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL)).thenReturn(List.of(recorded));
    when(assembler.toDto(deal)).thenReturn(dealDto);

    service.accept(
        DEAL,
        DELIVERY,
        new AcceptDeliveryRequest(6.0, 4.0, RejectReason.DAMAGED, 700, LOCATION, 1L));

    verify(supplyNeeds).creditPurchase(NEED, 6.0);
    assertThat(recorded.getStatus()).isEqualTo(DeliveryStatus.PARTIALLY_ACCEPTED);
    assertThat(recorded.getRejectedAmount()).isEqualTo(4.0);
    assertThat(recorded.getRejectReason()).isEqualTo(RejectReason.DAMAGED);
    assertThat(deal.isDeliveryClosed()).isFalse();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.IN_DELIVERY);
    verify(dealRepository, never()).saveAndFlush(any());
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_DELIVERY_ACCEPTED), eq(DEAL), eq("Deal 42"), isNull(), any());
    assertThat(publishedNotice().renderParams()).containsEntry("outcomeCode", "PARTIALLY_ACCEPTED");
  }

  @Test
  void rejectingAllBooksNothingAndLeavesTheDealOpen() {
    PurchasingDealDelivery recorded = delivery(10.0, DeliveryStatus.RECORDED);
    recorded.setVersion(1L);
    deal.setStatus(DealStatus.IN_DELIVERY);
    allowAcceptance(recorded);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACCEPTOR));
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL)).thenReturn(List.of(recorded));
    when(assembler.toDto(deal)).thenReturn(dealDto);

    service.accept(
        DEAL,
        DELIVERY,
        new AcceptDeliveryRequest(0.0, 10.0, RejectReason.WRONG_ITEM, null, null, 1L));

    verifyNoInteractions(stockCommands);
    verifyNoInteractions(supplyNeeds);
    assertThat(recorded.getStatus()).isEqualTo(DeliveryStatus.REJECTED);
    assertThat(recorded.getBookedRowId()).isNull();
    assertThat(recorded.getMeasuredQuality()).isNull();
    assertThat(recorded.getRejectReason()).isEqualTo(RejectReason.WRONG_ITEM);
    assertThat(deal.isDeliveryClosed()).isFalse();
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_DELIVERY_REJECTED), eq(DEAL), eq("Deal 42"), isNull(), any());
    assertThat(publishedNotice().renderParams()).containsEntry("outcomeCode", "REJECTED");
    assertThat(counter("delivery_rejected")).isEqualTo(1.0);
  }

  @Test
  void rejectingNeedsAReason() {
    PurchasingDealDelivery recorded = delivery(10.0, DeliveryStatus.RECORDED);
    recorded.setVersion(1L);
    allowAcceptance(recorded);

    assertThatThrownBy(() ->
            service.accept(
                DEAL, DELIVERY, new AcceptDeliveryRequest(0.0, 10.0, null, null, null, 1L)))
        .isInstanceOf(BadRequestException.class);

    verifyNoInteractions(stockCommands);
    verify(deliveryRepository, never()).saveAndFlush(any());
  }

  @Test
  void acceptedPlusRejectedMustEqualTheDelivery() {
    PurchasingDealDelivery recorded = delivery(10.0, DeliveryStatus.RECORDED);
    recorded.setVersion(1L);
    allowAcceptance(recorded);

    assertThatThrownBy(() ->
            service.accept(
                DEAL,
                DELIVERY,
                new AcceptDeliveryRequest(3.0, 3.0, RejectReason.OTHER, 500, LOCATION, 1L)))
        .isInstanceOf(BadRequestException.class);

    verifyNoInteractions(stockCommands);
  }

  @Test
  void acceptingBelowTheTierOfTheNeedIsRefused() {
    QualityTier tier = new QualityTier();
    tier.setMinQuality(600);
    deal.setQualityTier(tier);
    PurchasingDealDelivery recorded = delivery(10.0, DeliveryStatus.RECORDED);
    recorded.setVersion(1L);
    allowAcceptance(recorded);

    assertThatThrownBy(() ->
            service.accept(
                DEAL, DELIVERY, new AcceptDeliveryRequest(10.0, 0.0, null, 500, LOCATION, 1L)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            problem ->
                assertThat(problem.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_QUALITY_BELOW_TIER));

    verifyNoInteractions(stockCommands);
    verifyNoInteractions(supplyNeeds);
    assertThat(recorded.getStatus()).isEqualTo(DeliveryStatus.RECORDED);
  }

  @Test
  void acceptedGoodsNeedAStockLocation() {
    PurchasingDealDelivery recorded = delivery(10.0, DeliveryStatus.RECORDED);
    recorded.setVersion(1L);
    allowAcceptance(recorded);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACCEPTOR));

    assertThatThrownBy(() ->
            service.accept(
                DEAL, DELIVERY, new AcceptDeliveryRequest(10.0, 0.0, null, 500, null, 1L)))
        .isInstanceOf(BadRequestException.class);

    verifyNoInteractions(stockCommands);
  }

  @Test
  void theDelegateMayNotAcceptTheirOwnDelivery() {
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(accessPolicy.canAcceptDeliveries(deal)).thenReturn(true);
    when(accessPolicy.isDealDelegate(deal)).thenReturn(true);

    assertThatThrownBy(() ->
            service.accept(
                DEAL, DELIVERY, new AcceptDeliveryRequest(10.0, 0.0, null, 500, LOCATION, 1L)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            problem ->
                assertThat(problem.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_SELF_ACCEPTANCE));

    verifyNoInteractions(deliveryRepository);
    verifyNoInteractions(stockCommands);
  }

  @Test
  void aCallerWithoutTheAcceptRightIsRefused() {
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(accessPolicy.canAcceptDeliveries(deal)).thenReturn(false);

    assertThatThrownBy(() ->
            service.accept(
                DEAL, DELIVERY, new AcceptDeliveryRequest(10.0, 0.0, null, 500, LOCATION, 1L)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(deliveryRepository);
  }

  @Test
  void aStaleDeliveryVersionIsAConflict() {
    PurchasingDealDelivery recorded = delivery(10.0, DeliveryStatus.RECORDED);
    recorded.setVersion(4L);
    allowAcceptance(recorded);

    assertThatThrownBy(() ->
            service.accept(
                DEAL, DELIVERY, new AcceptDeliveryRequest(10.0, 0.0, null, 500, LOCATION, 3L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verifyNoInteractions(stockCommands);
  }

  @Test
  void closingWithoutAnyAcceptedGoodsFailsTheDeal() {
    PurchasingDealDelivery rejected = delivery(10.0, DeliveryStatus.REJECTED);
    rejected.setAcceptedAmount(0.0);
    rejected.setRejectedAmount(10.0);
    deal.setStatus(DealStatus.IN_DELIVERY);
    PurchasingSetting setting = new PurchasingSetting();
    setting.setSettlementDueDays(7);
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL)).thenReturn(List.of(rejected));
    when(settingsService.current()).thenReturn(setting);
    when(assembler.toDto(deal)).thenReturn(dealDto);

    service.close(DEAL);

    assertThat(deal.getStatus()).isEqualTo(DealStatus.FAILED);
    assertThat(deal.isDeliveryClosed()).isTrue();
    assertThat(deal.getSettlementDueAt()).isAfter(Instant.now().plus(Duration.ofDays(6)));
    verify(dealRepository).saveAndFlush(deal);
    verify(auditRecorder)
        .record(eq(AuditEventType.DEAL_FAILED), eq(DEAL), eq("Deal 42"), isNull(), any());
    assertThat(counter("delivery_failed")).isEqualTo(1.0);
  }

  @Test
  void closingWithSomeAcceptedGoodsDeliversTheDeal() {
    PurchasingDealDelivery accepted = delivery(4.0, DeliveryStatus.ACCEPTED);
    accepted.setAcceptedAmount(4.0);
    accepted.setRejectedAmount(0.0);
    deal.setStatus(DealStatus.IN_DELIVERY);
    PurchasingSetting setting = new PurchasingSetting();
    setting.setSettlementDueDays(7);
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL)).thenReturn(List.of(accepted));
    when(settingsService.current()).thenReturn(setting);
    when(assembler.toDto(deal)).thenReturn(dealDto);

    service.close(DEAL);

    assertThat(deal.getStatus()).isEqualTo(DealStatus.DELIVERED);
    verify(auditRecorder)
        .record(eq(AuditEventType.DEAL_DELIVERY_CLOSED), eq(DEAL), eq("Deal 42"), isNull(), any());
    assertThat(counter("delivery_closed")).isEqualTo(1.0);
  }

  @Test
  void closingIsRefusedWhileARecordedDeliveryIsUndecided() {
    deal.setStatus(DealStatus.IN_DELIVERY);
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(deliveryRepository.findByDealIdOrderByCreatedAtAsc(DEAL))
        .thenReturn(List.of(delivery(4.0, DeliveryStatus.RECORDED)));

    assertThatThrownBy(() -> service.close(DEAL))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            problem ->
                assertThat(problem.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_DEAL_STATE));

    verify(dealRepository, never()).saveAndFlush(any());
    assertThat(deal.isDeliveryClosed()).isFalse();
  }

  private void allowAcceptance(PurchasingDealDelivery delivery) {
    when(dealService.lock(DEAL)).thenReturn(deal);
    when(accessPolicy.canAcceptDeliveries(deal)).thenReturn(true);
    when(accessPolicy.isDealDelegate(deal)).thenReturn(false);
    when(deliveryRepository.findById(DELIVERY)).thenReturn(Optional.of(delivery));
  }

  private PurchasingDealDelivery delivery(double amount, DeliveryStatus status) {
    PurchasingDealDelivery delivery = new PurchasingDealDelivery();
    delivery.setId(DELIVERY);
    delivery.setDealId(DEAL);
    delivery.setAmount(amount);
    delivery.setStatus(status);
    return delivery;
  }

  private NoticeEvent publishedNotice() {
    ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(notice.capture());
    return notice.getValue();
  }

  private double counter(String event) {
    return meterRegistry
        .counter(MetricNames.PURCHASING_DEALS, MetricNames.TAG_EVENT, event)
        .count();
  }
}
