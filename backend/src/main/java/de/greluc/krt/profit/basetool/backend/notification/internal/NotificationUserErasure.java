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

package de.greluc.krt.profit.basetool.backend.notification.internal;

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRuleRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes a departing member's notifications and notification-rule selectors for the account
 * deletion; the notification module's participant, which moves with the module's services.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationUserErasure implements UserErasureParticipant {

  static final int POSITION = 60;

  private final NotificationRepository notificationRepository;
  private final NotificationRuleRepository notificationRuleRepository;

  @Override
  public @NotNull Phase phase() {
    return Phase.DELETE;
  }

  @Override
  public int position() {
    return POSITION;
  }

  /**
   * Deletes the notifications the member received, then the rule selectors that name the member as
   * a recipient.
   *
   * @param userId the departing member's {@code app_user.id}
   * @param fallbackAdminId unused
   * @return the notifications as {@link Erased#NOTIFICATIONS} and the selectors as {@link
   *     Erased#RULE_SELECTORS}
   */
  @Override
  public @NotNull Map<Erased, Integer> erase(@NotNull UUID userId, @NotNull UUID fallbackAdminId) {
    int notifications = notificationRepository.deleteAllForRecipient(userId);
    int ruleSelectors = notificationRuleRepository.deleteSelectorsByUserId(userId);
    return Map.of(Erased.NOTIFICATIONS, notifications, Erased.RULE_SELECTORS, ruleSelectors);
  }
}
