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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default single-instance {@link NotificationFanout}: delivers only to this backend instance's SSE
 * emitters — byte-for-byte the behaviour before ADR-0094.
 *
 * <p>Active unless {@code app.notifications.redis-fanout.enabled} is {@code true} (mutually
 * exclusive with {@code RedisNotificationFanout} on the same property, so exactly one binding is
 * present and no bean-ordering condition is needed). Default off, so dev/test and any environment
 * without Redis keep the local-only push.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.notifications.redis-fanout",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true)
@RequiredArgsConstructor
public class LocalNotificationFanout implements NotificationFanout {

  private final NotificationStreamService notificationStreamService;

  /** {@inheritDoc} */
  @Override
  public void publish(
      @NotNull Collection<UUID> recipientUserIds, @NotNull NotificationSignal signal) {
    notificationStreamService.publish(recipientUserIds, signal);
  }
}
