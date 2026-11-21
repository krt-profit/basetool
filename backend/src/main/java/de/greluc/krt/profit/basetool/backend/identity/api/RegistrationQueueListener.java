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

package de.greluc.krt.profit.basetool.backend.identity.api;

import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationChangedEvent;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes a {@link DiscordRegistrationChangedEvent} for every update or removal of a pending or
 * rejected account, whatever wrote it (REQ-SEC-090), so the open registration queues re-read the
 * optimistic-lock versions they echo. Hibernate takes the listener from the Spring context and
 * calls it inside the flush of the writing transaction.
 */
@Component
@RequiredArgsConstructor
public class RegistrationQueueListener {

  private final ApplicationEventPublisher eventPublisher;

  /**
   * Announces a written account that the queue lists; an active account is on no queue.
   *
   * @param user the account just updated or removed
   */
  @PostUpdate
  @PostRemove
  void written(@NotNull User user) {
    if (user.getApprovalStatus() != ApprovalStatus.ACTIVE) {
      eventPublisher.publishEvent(new DiscordRegistrationChangedEvent(user.getId()));
    }
  }
}
