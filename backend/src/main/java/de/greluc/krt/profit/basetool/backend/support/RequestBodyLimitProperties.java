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

import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration under {@code app.request-body-limit.*} for the {@code RequestBodySizeLimitFilter},
 * which refuses an oversized non-multipart JSON body on the listed paths with 413 before it is
 * bound.
 *
 * @param enabled whether the request-body-size cap is active
 * @param maxBytes the inclusive maximum body size in bytes; default 2&nbsp;MiB, at least 1&nbsp;KiB
 * @param paths the request URIs (exact match) whose non-multipart body is capped
 */
@Validated
@ConfigurationProperties(prefix = "app.request-body-limit")
public record RequestBodyLimitProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("2097152") @Min(1024) long maxBytes,
    @DefaultValue("/api/v1/refinery-orders/import-extract") List<String> paths) {}
