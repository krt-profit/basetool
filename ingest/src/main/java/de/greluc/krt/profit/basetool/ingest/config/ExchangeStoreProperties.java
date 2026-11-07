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

package de.greluc.krt.profit.basetool.ingest.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The hard byte budget of the exchange data the gateway keeps in Redis and its idempotency cache
 * (REQ-XCH-020, REQ-XCH-023, ADR-0221).
 *
 * @param memberBytes the budget per client and member
 * @param clientBytes the budget per client
 * @param totalBytes the budget of the whole exchange
 * @param maxResultBytes the largest answer the idempotency cache keeps, reserved before a write
 * @param idempotencyTtl how long an answer is replayed for its key
 * @param lockTtl how long a write in flight holds its key
 * @param maxMassChangeBytes the largest change set staged for the member's confirmation
 * @param maxDraftsPerClientMember the most live drafts one client stages for one member; the oldest
 *     are evicted (REQ-XCH-019)
 */
@Validated
@ConfigurationProperties(prefix = "app.exchange.store")
public record ExchangeStoreProperties(
    @Min(1024) @DefaultValue("4194304") long memberBytes,
    @Min(1024) @DefaultValue("67108864") long clientBytes,
    @Min(1024) @DefaultValue("134217728") long totalBytes,
    @Min(1024) @DefaultValue("32768") int maxResultBytes,
    @NotNull @DefaultValue("PT1H") Duration idempotencyTtl,
    @NotNull @DefaultValue("PT2M") Duration lockTtl,
    @Min(1024) @DefaultValue("524288") long maxMassChangeBytes,
    @Min(1) @DefaultValue("10") int maxDraftsPerClientMember) {}
