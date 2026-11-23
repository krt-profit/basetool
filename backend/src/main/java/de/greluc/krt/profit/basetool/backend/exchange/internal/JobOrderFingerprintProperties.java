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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The job-order fingerprint worker (REQ-OXCH-003); its run interval is {@code
 * app.exchange.job-order-fingerprint.interval} (default {@code PT5S}).
 *
 * @param enabled whether the worker runs; it runs whatever the job-order switches say
 * @param batchSize how many marked orders one run recomputes, the longest marked first
 */
@Validated
@ConfigurationProperties(prefix = "app.exchange.job-order-fingerprint")
public record JobOrderFingerprintProperties(
    @DefaultValue("true") boolean enabled, @DefaultValue("200") @Min(1) @Max(1000) int batchSize) {}
