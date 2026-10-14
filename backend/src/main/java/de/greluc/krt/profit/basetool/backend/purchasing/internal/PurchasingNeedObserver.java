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

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.events.PurchasingNotices;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedLifecycleObserver;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hears of the needs the supply module changes: tells the delegates of a need released for
 * purchase, and cancels the deals of a withdrawn need that hold no confirmed money (REQ-PUR-020,
 * REQ-PUR-024). Deals with money out run on to their settlement.
 */
@Component
@RequiredArgsConstructor
class PurchasingNeedObserver implements NeedLifecycleObserver {

  private final PurchasingDelegateRepository delegateRepository;
  private final PurchasingDealRepository dealRepository;
  private final PurchasingDealService dealService;
  private final PurchasingSettingsService settingsService;
  private final OrgUnitRepository orgUnitRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void onReleasedForPurchase(@NotNull NeedRef need, @Nullable UUID actorSub) {
    if (!settingsService.isEnabled()) {
      return;
    }
    Set<UUID> delegates = Set.copyOf(delegateRepository.findActiveUserIds(Instant.now()));
    if (delegates.isEmpty()) {
      return;
    }
    String unitName =
        orgUnitRepository.findById(need.orgUnitId()).map(unit -> unit.getName()).orElse("—");
    eventPublisher.publishEvent(
        PurchasingNotices.needReleased(
            need.id(),
            unitName,
            need.label(),
            PurchasingTrancheService.amountText(need.openAmount()),
            delegates,
            actorSub));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void onWithdrawn(@NotNull UUID needId) {
    List<PurchasingDeal> open =
        dealRepository.findByNeedIdAndStatusIn(
            needId, List.of(DealStatus.DRAFT, DealStatus.SUBMITTED));
    for (PurchasingDeal deal : open) {
      dealService.cancelWithinTransaction(dealService.lock(deal.getId()), "NEED_WITHDRAWN");
    }
    if (settingsService.isEnabled()) {
      eventPublisher.publishEvent(PurchasingNotices.needClosed(needId));
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void onFulfilled(@NotNull UUID needId) {
    if (settingsService.isEnabled()) {
      eventPublisher.publishEvent(PurchasingNotices.needClosed(needId));
    }
  }
}
