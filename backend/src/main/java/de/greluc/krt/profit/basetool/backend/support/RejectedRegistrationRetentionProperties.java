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
 * Validated configuration of the rejected-registration retention sweep (REQ-SEC-057, prefix {@code
 * app.registrations.rejected-retention}, fed by {@code APP_REGISTRATIONS_REJECTED_RETENTION_*}).
 *
 * <p>The sweep deletes a refused registration — its {@code app_user} row, its approval events and
 * its Keycloak user — once the rejection is older than {@code maxAge}. The spec states the window
 * is "not zero on purpose": it is also the period in which an erroneous rejection can still be
 * reopened (REQ-SEC-034). Bound through {@code @Value} it accepted {@code P0D} and negative values,
 * either of which would purge every rejection on the next run, including one decided a minute ago
 * (BE-MOD-03). The one-day floor makes "not zero" a startup check: a value under it refuses to
 * start the context.
 *
 * @param enabled whether the sweep bean exists at all ({@code @ConditionalOnProperty} on the task
 *     reads the same key; default {@code true}, {@code false} under the {@code test} profile)
 * @param maxAge how long a rejected registration is kept after the rejection; at least one day,
 *     default {@code P90D}
 * @param interval the pause between two sweeps ({@code fixedDelay}); at least one minute, default
 *     {@code PT24H}
 */
@Validated
@ConfigurationProperties("app.registrations.rejected-retention")
public record RejectedRegistrationRetentionProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("P90D") @NotNull @DurationMin(days = 1) Duration maxAge,
    @DefaultValue("PT24H") @NotNull @DurationMin(minutes = 1) Duration interval) {}
