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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.UuidArrayLiteral;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferRepository.OfferStock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The Materialbörse side of a patch wipe (REQ-PATCH-013, REQ-PATCH-017, REQ-PATCH-018): the active
 * offers on wiped rows end with the usual „stock gone“ notice and go, the trail gets one summary
 * event instead of one per offer, and the board refreshes after the commit.
 */
@ExtendWith(MockitoExtension.class)
class MaterialExchangePatchWipeServiceTest {

  private static final UUID PATCH_CHANGE = UUID.randomUUID();

  @Mock private MaterialExchangeOfferRepository offerRepository;
  @Mock private MaterialExchangeOfferRatchet offerRatchet;
  @Mock private AuditRecorder auditRecorder;
  @Mock private LiveSyncPublisher liveSyncPublisher;

  private MaterialExchangePatchWipeService service;

  @BeforeEach
  void setUp() {
    service =
        new MaterialExchangePatchWipeService(
            offerRepository, offerRatchet, auditRecorder, liveSyncPublisher);
  }

  @Test
  void theActiveOffersEndWithTheirNoticeAndOneSummary() {
    UUID row = UUID.randomUUID();
    OfferStock offer = mock(OfferStock.class);
    UUID offerId = UUID.randomUUID();
    when(offer.getId()).thenReturn(offerId);
    List<OfferStock> offers = List.of(offer);
    when(offerRepository.findActiveStockByInventoryItemIds(List.of(row))).thenReturn(offers);

    int ended = service.end(List.of(row), PATCH_CHANGE, "4.4");

    assertThat(ended).isEqualTo(1);
    verify(offerRatchet).announceGone(offers);
    verify(offerRepository).deleteByIdArray(UuidArrayLiteral.of(List.of(offerId)));
    verify(auditRecorder)
        .record(
            eq(AuditEventType.MARKET_OFFERS_ENDED_BY_PATCH),
            eq(PATCH_CHANGE),
            eq("4.4"),
            isNull(),
            argThat(details -> details.toString().contains("offers=1")));
    verify(liveSyncPublisher)
        .publishAfterCommit(
            LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), List.of("board"));
  }

  @Test
  void withoutOffersNothingIsDeletedOrPublished() {
    UUID row = UUID.randomUUID();
    when(offerRepository.findActiveStockByInventoryItemIds(List.of(row))).thenReturn(List.of());

    assertThat(service.end(List.of(row), PATCH_CHANGE, "4.4")).isZero();

    verify(offerRepository, never()).deleteByIdArray(anyString());
    verify(auditRecorder)
        .record(
            eq(AuditEventType.MARKET_OFFERS_ENDED_BY_PATCH),
            eq(PATCH_CHANGE),
            eq("4.4"),
            isNull(),
            argThat(details -> details.toString().contains("offers=0")));
    verifyNoInteractions(liveSyncPublisher);
  }
}
