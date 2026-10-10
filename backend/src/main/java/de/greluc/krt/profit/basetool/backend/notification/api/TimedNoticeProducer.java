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

package de.greluc.krt.profit.basetool.backend.notification.api;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;

/**
 * Raises the notices no user action triggers, such as a reminder before a mission or a refinery
 * order that is ready (REQ-NOTIF-026).
 *
 * <p>Owned by the notification module and implemented by the module that owns the time. The
 * notification task calls every producer on a schedule, holding the cross-instance lock.
 */
public interface TimedNoticeProducer {

  /**
   * The bounded, PII-free tag value naming what this producer raises, used as the {@code kind}
   * label of the produced-notices counter.
   *
   * @return a short snake-case identifier, unique among the producers
   */
  @NotNull
  String kind();

  /**
   * Finds the entities whose time has come and publishes a notification event for each.
   *
   * <p>Called inside its own transaction. An implementation sets the entity's „already notified"
   * marker in that same transaction, so a notice fires at most once, and publishes the event with
   * {@code ApplicationEventPublisher} so it is delivered after the commit. It handles at most
   * {@code limit} entities and marks only those, so the rest follow on the next run.
   *
   * @param now the instant the run is evaluated against
   * @param limit the most events to publish in this run; at least one
   * @return the number of notices published; never negative
   */
  int produce(@NotNull Instant now, int limit);
}
