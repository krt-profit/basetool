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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.UuidArrayLiteral;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.materialexchange.api.MaterialExchangePatchWipe;
import de.greluc.krt.profit.basetool.backend.materialexchange.internal.MaterialExchangeOfferRepository.OfferStock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends the offers on stock a game patch wiped (REQ-PATCH-013): the interested members hear the
 * stock is gone as on every Lager delete, and the Materialbörse's trail gets one summary event
 * instead of one event per offer (REQ-PATCH-017).
 */
@Service
@RequiredArgsConstructor
public class MaterialExchangePatchWipeService implements MaterialExchangePatchWipe {

  private final MaterialExchangeOfferRepository offerRepository;
  private final MaterialExchangeOfferRatchet offerRatchet;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSyncPublisher;

  @Override
  @Transactional(readOnly = true)
  public long countEndable(@NotNull Collection<UUID> inventoryItemIds) {
    return offerRepository.findActiveStockByInventoryItemIds(inventoryItemIds).size();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int end(
      @NotNull Collection<UUID> inventoryItemIds,
      @NotNull UUID patchChangeId,
      @NotNull String patchLabel) {
    List<OfferStock> offers = offerRepository.findActiveStockByInventoryItemIds(inventoryItemIds);
    offerRatchet.announceGone(offers);
    if (!offers.isEmpty()) {
      offerRepository.deleteByIdArray(
          UuidArrayLiteral.of(offers.stream().map(OfferStock::getId).toList()));
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), List.of("board"));
    }
    auditRecorder.record(
        AuditEventType.MARKET_OFFERS_ENDED_BY_PATCH,
        patchChangeId,
        patchLabel,
        null,
        AuditDetails.of("offers", offers.size()).with("patchChange", patchChangeId));
    return offers.size();
  }
}
