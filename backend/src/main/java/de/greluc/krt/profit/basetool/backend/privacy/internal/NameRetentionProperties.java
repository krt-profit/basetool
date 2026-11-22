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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration of the name retention (REQ-DATA-047, prefix {@code
 * app.privacy.name-retention}, fed by {@code APP_PRIVACY_NAME_RETENTION_*}). A value below a floor
 * refuses to start the context.
 *
 * @param enabled whether the task bean exists; default {@code true}, {@code false} under the {@code
 *     test} profile
 * @param maxAge how long names and free texts are kept after the event; at least one year, default
 *     {@code P1095D} (three years, as the privacy policy states)
 * @param interval the pause between two runs ({@code fixedDelay}); at least one minute, default
 *     {@code PT24H}
 */
@Validated
@ConfigurationProperties("app.privacy.name-retention")
public record NameRetentionProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("P1095D") @NotNull @DurationMin(days = 365) Duration maxAge,
    @DefaultValue("PT24H") @NotNull @DurationMin(minutes = 1) Duration interval) {}
