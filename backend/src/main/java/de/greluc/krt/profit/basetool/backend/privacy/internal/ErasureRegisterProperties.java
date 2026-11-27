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
 * Validated configuration of the erasure register's job (REQ-DATA-048, prefix {@code
 * app.privacy.erasure-register}, fed by {@code APP_PRIVACY_ERASURE_REGISTER_*}). A value below a
 * floor refuses to start the context.
 *
 * @param enabled whether the job bean exists; default {@code true}, {@code false} under the {@code
 *     test} profile
 * @param maxAge how long a deleted account stays registered; at least 200 days so it outlives the
 *     backups (about six months), default {@code P365D} (12 months, as the privacy policy states)
 * @param interval the pause between two runs ({@code fixedDelay}, the first run at startup); at
 *     least one minute, default {@code PT24H}
 */
@Validated
@ConfigurationProperties("app.privacy.erasure-register")
public record ErasureRegisterProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("P365D") @NotNull @DurationMin(days = 200) Duration maxAge,
    @DefaultValue("PT24H") @NotNull @DurationMin(minutes = 1) Duration interval) {}
