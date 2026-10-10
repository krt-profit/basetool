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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferRepository.OfferStock;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Verifies that the offer ratchet lowers and audits exactly the offers a stock change moves, with
 * the change's reason and nothing but bounded facts in the details (REQ-MARKET-013, REQ-AUDIT-001).
 */
@ExtendWith(MockitoExtension.class)
class MaterialExchangeOfferRatchetTest {

  private static final UUID ROW = UUID.randomUUID();
  private static final UUID OWNER = UUID.randomUUID();

  @Mock private MaterialExchangeOfferRepository offerRepository;
  @Mock private AuditService auditService;
  @Mock private MaterialExchangeInterestRepository interestRepository;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private MaterialExchangeOfferRatchet ratchet;

  @Test
  void lowerAuditsAMaterialOfferAboveTheNewStockAndClampsIt() {
    OfferStock offer = material(80.0, "Laranite");
    when(offerRepository.findActiveStockByInventoryItemIds(List.of(ROW)))
        .thenReturn(List.of(offer));

    int lowered = ratchet.lower(ROW, 30.0, StockChangeReason.CHECKOUT);

    assertThat(lowered).isEqualTo(1);
    assertThat(details(AuditEventType.MARKET_OFFER_REDUCED, offer.getId(), "Laranite"))
        .isEqualTo("kind=MATERIAL from=80.0 to=30.0 reason=checkout");
    verify(offerRepository).clampOfferedAmountToStock(ROW, 30.0);
    verify(offerRepository).clampItemQuantityToStock(ROW, 30);
  }

  @Test
  void lowerAuditsAnItemOfferAgainstTheWholePiecesLeft() {
    OfferStock offer = item(5, "Arden-SL Helmet");
    when(offerRepository.findActiveStockByInventoryItemIds(List.of(ROW)))
        .thenReturn(List.of(offer));

    int lowered = ratchet.lower(ROW, 2.0, StockChangeReason.HANDOVER);

    assertThat(lowered).isEqualTo(1);
    assertThat(details(AuditEventType.MARKET_OFFER_REDUCED, offer.getId(), "Arden-SL Helmet"))
        .isEqualTo("kind=ITEM from=5 to=2 reason=handover");
    verify(offerRepository).clampItemQuantityToStock(ROW, 2);
  }

  @Test
  void lowerLeavesAnOfferTheStockStillCoversUnaudited() {
    when(offerRepository.findActiveStockByInventoryItemIds(List.of(ROW)))
        .thenReturn(List.of(material(20.0, "Laranite"), item(3, "Arden-SL Helmet")));

    int lowered = ratchet.lower(ROW, 20.0, StockChangeReason.PRODUCTION);

    assertThat(lowered).isZero();
    verifyNoInteractions(auditService);
  }

  @Test
  void lowerOnARowWithoutAnOfferWritesNothing() {
    when(offerRepository.findActiveStockByInventoryItemIds(List.of(ROW))).thenReturn(List.of());

    int lowered = ratchet.lower(ROW, 1.0, StockChangeReason.TRANSFER);

    assertThat(lowered).isZero();
    verify(offerRepository, never()).clampOfferedAmountToStock(any(), anyDouble());
    verify(offerRepository, never()).clampItemQuantityToStock(any(), anyInt());
    verifyNoInteractions(auditService);
  }

  @Test
  void beforeDeleteAuditsEveryActiveOfferOnTheRowsAsRemoved() {
    OfferStock first = material(4.0, "Laranite");
    OfferStock second = item(2, "Arden-SL Helmet");
    when(offerRepository.findActiveStockByInventoryItemIds(List.of(ROW)))
        .thenReturn(List.of(first, second));

    int removed = ratchet.beforeDelete(List.of(ROW), StockChangeReason.REBOOK);

    assertThat(removed).isEqualTo(2);
    assertThat(details(AuditEventType.MARKET_OFFER_REMOVED, first.getId(), "Laranite"))
        .isEqualTo("kind=MATERIAL reason=rebook");
    assertThat(details(AuditEventType.MARKET_OFFER_REMOVED, second.getId(), "Arden-SL Helmet"))
        .isEqualTo("kind=ITEM reason=rebook");
  }

  @Test
  void beforeDeleteTellsTheInterestedMembersTheirOfferIsGoneOnePerOffer() {
    OfferStock first = material(4.0, "Laranite");
    OfferStock second = item(2, "Arden-SL Helmet");
    UUID fan = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    when(offerRepository.findActiveStockByInventoryItemIds(List.of(ROW)))
        .thenReturn(List.of(first, second));
    when(interestRepository.findRecipientsByOfferIdIn(List.of(first.getId(), second.getId())))
        .thenReturn(List.of(new MaterialExchangeInterestRecipient(first.getId(), fan)));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actor));

    ratchet.beforeDelete(List.of(ROW), StockChangeReason.CHECKOUT);

    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(2)).publishEvent(published.capture());
    NoticeEvent toFirst = (NoticeEvent) published.getAllValues().get(0);
    NoticeEvent toSecond = (NoticeEvent) published.getAllValues().get(1);
    assertThat(toFirst.eventType())
        .isEqualTo(NotificationEventType.MATERIAL_EXCHANGE_OFFER_UNAVAILABLE);
    assertThat(toFirst.entityId()).isEqualTo(first.getId());
    assertThat(toFirst.contextRecipientUserIds()).containsExactly(fan);
    assertThat(toFirst.actorSub()).isEqualTo(actor);
    assertThat(toFirst.renderParams())
        .containsEntry("item", "Laranite")
        .containsEntry("reasonCode", "STOCK_GONE");
    assertThat(toSecond.contextRecipientUserIds()).isEmpty();
    assertThat(toSecond.resolvesNotificationTypes())
        .containsExactly(NotificationType.MATERIAL_EXCHANGE_INTEREST_REGISTERED);
  }

  @Test
  void beforeDeleteOfNoRowsQueriesNothing() {
    int removed = ratchet.beforeDelete(List.of(), StockChangeReason.CHECKOUT);

    assertThat(removed).isZero();
    verify(offerRepository, never()).findActiveStockByInventoryItemIds(anyCollection());
    verifyNoInteractions(auditService, eventPublisher);
  }

  @Test
  void beforeWipeReadsTheWipesScopeAndAuditsWithReasonWipe() {
    UUID unit = UUID.randomUUID();
    OfferStock offer = material(10.0, "Quantainium");
    when(offerRepository.findActiveStockOnNonPersonalRows(false, unit, Set.of()))
        .thenReturn(List.of(offer));

    int removed = ratchet.beforeWipe(false, unit, Set.of());

    assertThat(removed).isEqualTo(1);
    assertThat(details(AuditEventType.MARKET_OFFER_REMOVED, offer.getId(), "Quantainium"))
        .isEqualTo("kind=MATERIAL reason=wipe");
  }

  @Test
  void beforeUserPurgeAuditsTheMembersOffersWithReasonUserDeletion() {
    OfferStock offer = material(10.0, "Quantainium");
    when(offerRepository.findActiveStockByRowOwner(OWNER)).thenReturn(List.of(offer));

    int removed = ratchet.beforeUserPurge(OWNER);

    assertThat(removed).isEqualTo(1);
    assertThat(details(AuditEventType.MARKET_OFFER_REMOVED, offer.getId(), "Quantainium"))
        .isEqualTo("kind=MATERIAL reason=user-deletion");
  }

  /**
   * Captures the details of the one event of a type recorded for an offer, and checks its label and
   * that the offer's owner is the target.
   *
   * @param type the event type
   * @param offer the offer
   * @param label the expected label
   * @return the rendered details
   */
  private String details(AuditEventType type, UUID offer, String label) {
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService).record(eq(type), eq(offer), eq(label), eq(OWNER), details.capture());
    return details.getValue().toString();
  }

  /**
   * An active material offer.
   *
   * @param amount its offered amount
   * @param materialName the material of its row
   * @return the offer
   */
  private static OfferStock material(double amount, String materialName) {
    return new Offer(
        UUID.randomUUID(), MaterialExchangeOfferKind.MATERIAL, amount, null, null, materialName);
  }

  /**
   * An active stock-backed item offer.
   *
   * @param quantity its quantity
   * @param itemName its item name
   * @return the offer
   */
  private static OfferStock item(int quantity, String itemName) {
    return new Offer(
        UUID.randomUUID(), MaterialExchangeOfferKind.ITEM, null, quantity, itemName, null);
  }

  /**
   * A projection of an active offer.
   *
   * @param id the offer
   * @param kind its kind
   * @param offeredAmount its offered amount, or {@code null}
   * @param itemQuantity its item quantity, or {@code null}
   * @param itemName its item name, or {@code null}
   * @param materialName the material of its row, or {@code null}
   */
  private record Offer(
      UUID id,
      MaterialExchangeOfferKind kind,
      Double offeredAmount,
      Integer itemQuantity,
      String itemName,
      String materialName)
      implements OfferStock {

    @Override
    public UUID getId() {
      return id;
    }

    @Override
    public Double getOfferedAmount() {
      return offeredAmount;
    }

    @Override
    public Integer getItemQuantity() {
      return itemQuantity;
    }

    @Override
    public MaterialExchangeOfferKind getKind() {
      return kind;
    }

    @Override
    public String getItemName() {
      return itemName;
    }

    @Override
    public String getMaterialName() {
      return materialName;
    }

    @Override
    public UUID getOwnerId() {
      return OWNER;
    }
  }
}
