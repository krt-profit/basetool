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

package de.greluc.krt.profit.basetool.backend.support;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration of the two-window notification retention sweep (REQ-NOTIF-009, prefix
 * {@code app.notifications.retention}). A violation refuses to start the context.
 *
 * @param enabled whether the sweep bean exists (default {@code true}, {@code false} under the
 *     {@code test} profile)
 * @param maxAge how long a READ notification is kept after it was read; at least one day, default
 *     {@code P90D}
 * @param unreadMaxAge how long an UNREAD notification is kept after it was raised; at least one day
 *     and never shorter than {@code maxAge}, default {@code P180D}
 * @param interval the pause between two sweeps; at least one minute, default {@code PT24H}
 */
@Validated
@ConfigurationProperties("app.notifications.retention")
public record NotificationRetentionProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("P90D") @NotNull @DurationMin(days = 1) Duration maxAge,
    @DefaultValue("P180D") @NotNull @DurationMin(days = 1) Duration unreadMaxAge,
    @DefaultValue("PT24H") @NotNull @DurationMin(minutes = 1) Duration interval) {

  /**
   * Whether the unread window is at least as long as the read one (REQ-NOTIF-009).
   *
   * @return {@code true} when {@code unreadMaxAge >= maxAge}, or when either is {@code null}
   *     (reported by its own {@code @NotNull})
   */
  @AssertTrue(
      message =
          "app.notifications.retention.unread-max-age must not be shorter than"
              + " app.notifications.retention.max-age")
  public boolean isUnreadWindowNotShorterThanReadWindow() {
    return maxAge == null || unreadMaxAge == null || unreadMaxAge.compareTo(maxAge) >= 0;
  }
}
