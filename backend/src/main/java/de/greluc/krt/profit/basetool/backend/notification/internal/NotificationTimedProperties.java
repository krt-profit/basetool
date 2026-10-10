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

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration of the time-based notification producer (REQ-NOTIF-026, prefix {@code
 * app.notifications.timed}). A violation refuses to start the context.
 *
 * @param enabled whether the scheduled task exists (default {@code true}, {@code false} under the
 *     {@code test} profile)
 * @param interval the pause between two runs; at least ten seconds, default {@code PT1M}
 */
@Validated
@ConfigurationProperties("app.notifications.timed")
public record NotificationTimedProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("PT1M") @NotNull @DurationMin(seconds = 10) Duration interval) {}
