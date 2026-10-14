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

import de.greluc.krt.profit.basetool.backend.notification.api.TimedNoticeProducer;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.purchasing.api.events.PurchasingNotices;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Tells the delegate, the Bereichsleiter and Bereichskoordinatoren of the governing Bereiche and
 * the bank management when a deal's settlement is past its due date (REQ-PUR-018), once per deal:
 * the marker is set by an atomic update that does not touch the deal's version, and only the caller
 * whose update changed the row publishes. While the purchasing surface is off nothing is announced.
 */
@Component
@Order(4)
@RequiredArgsConstructor
public class SettlementOverdueNoticeProducer implements TimedNoticeProducer {

  private final PurchasingDealRepository dealRepository;
  private final PurchasingSettingsService settingsService;
  private final OrgUnitMembershipRepository membershipRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @NotNull
  public String kind() {
    return "purchasing_settlement_overdue";
  }

  @Override
  public int produce(@NotNull Instant now, int limit) {
    if (!settingsService.isEnabled()) {
      return 0;
    }
    List<UUID> areas = settingsService.governingAreaIds();
    Set<UUID> leadership = new HashSet<>();
    if (!areas.isEmpty()) {
      leadership.addAll(
          membershipRepository.findUserIdsByOrgUnitIdsAndRole(
              areas, MembershipRole.BEREICHSLEITER));
      leadership.addAll(
          membershipRepository.findUserIdsByOrgUnitIdsAndRole(
              areas, MembershipRole.BEREICHSKOORDINATOR));
    }
    int raised = 0;
    for (UUID id : dealRepository.findOverdueNotYetAnnouncedIds(now, Limit.of(limit))) {
      if (dealRepository.markOverdueNotified(id, now) != 1) {
        continue;
      }
      PurchasingDeal deal = dealRepository.findById(id).orElse(null);
      if (deal == null) {
        continue;
      }
      eventPublisher.publishEvent(
          PurchasingNotices.settlementOverdue(
              deal.getId(), deal.getDealNo(), deal.getDelegateUserId(), leadership));
      raised++;
    }
    return raised;
  }
}
