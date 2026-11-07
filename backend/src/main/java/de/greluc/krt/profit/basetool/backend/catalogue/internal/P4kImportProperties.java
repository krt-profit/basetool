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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code krt.p4k-import.*} (REQ-P4K-002).
 *
 * @param decompressedLimitBytes the most bytes a catalogue may hold once decompressed; the
 *     contract's {@code DECOMPRESSED_LIMIT}, 1,000,000,000 by default, also the guard against
 *     decompression bombs
 * @param heldLimitBytes the most bytes of catalogue JSON the kinds the reader holds in memory may
 *     span together, 128,000,000 by default; it bounds the heap of a preview or APPLY where the
 *     decompressed limit alone would not
 */
@Validated
@ConfigurationProperties(prefix = "krt.p4k-import")
public record P4kImportProperties(
    @DefaultValue("1000000000") @Min(1) long decompressedLimitBytes,
    @DefaultValue("128000000") @Min(1) long heldLimitBytes) {}
