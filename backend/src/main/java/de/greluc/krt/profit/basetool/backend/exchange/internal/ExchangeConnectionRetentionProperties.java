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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long a disconnected exchange installation and a member's client revocation are kept after the
 * disconnect (REQ-XCH-035, prefix {@code app.exchange.connection-retention}). A value below the
 * floor refuses to start the context.
 *
 * @param enabled whether the nightly sweep runs
 * @param maxAge how long an entry is kept after its disconnect; at least the 90-day maximum
 *     lifespan of an exchange client's session (ADR-0217), default {@code P90D}
 */
@Validated
@ConfigurationProperties(prefix = "app.exchange.connection-retention")
public record ExchangeConnectionRetentionProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("P90D") @NotNull @DurationMin(days = 90) Duration maxAge) {}
