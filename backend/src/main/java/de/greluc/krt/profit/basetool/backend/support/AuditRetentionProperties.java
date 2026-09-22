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
 * <p>The sweep deletes, irreversibly and on a schedule, every activity and bank audit row older
 * than {@code maxAge}. Bound through {@code @Value} it accepted any duration, so a mistyped {@code
 * P0D} or a negative value would have put the cutoff at "now" or in the future and deleted the
 * entire trail on the next run (BE-MOD-03). The floor below is a guard against that
 * misconfiguration, not a retention policy — the policy is the two-year default. It sits at 30
 * days: short enough not to stand in the way of a deliberate shorter window, long enough that no
 * slip of a digit or a sign can reach the rows of the current month. A value under it refuses to
 * start the context.
 *
 * <p>Lives in the dependency-leaf {@code support} package like {@link AuthoritiesCacheProperties}
 * and is registered by {@code @ConfigurationPropertiesScan}.
 *
 * @param enabled whether the sweep bean exists at all ({@code @ConditionalOnProperty} on the task
 *     reads the same key; default {@code true}, {@code false} under the {@code test} profile)
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
