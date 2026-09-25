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

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration of the audit-trail retention sweep (REQ-AUDIT-006, prefix {@code
 * app.audit.retention}, fed by {@code APP_AUDIT_RETENTION_*}).
 *
 * <p>The sweep irreversibly deletes activity and bank audit rows older than {@code maxAge}; a value
 * below the {@link #MIN_MAX_AGE_DAYS} floor refuses to start the context.
 *
 * @param enabled whether the sweep bean exists; default {@code true}, {@code false} under the
 *     {@code test} profile
 * @param maxAge how long an audit row is kept after it occurred; at least {@link #MIN_MAX_AGE_DAYS}
 *     days, default {@code P730D}
 * @param interval the pause between two sweeps ({@code fixedDelay}); at least one minute, default
 *     {@code PT24H}
 */
@Validated
@ConfigurationProperties("app.audit.retention")
public record AuditRetentionProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("P730D") @NotNull @DurationMin(days = MIN_MAX_AGE_DAYS) Duration maxAge,
    @DefaultValue("PT24H") @NotNull @DurationMin(minutes = 1) Duration interval) {

  /** The floor on {@code maxAge}, in days (REQ-AUDIT-006). */
  public static final long MIN_MAX_AGE_DAYS = 30;
}
