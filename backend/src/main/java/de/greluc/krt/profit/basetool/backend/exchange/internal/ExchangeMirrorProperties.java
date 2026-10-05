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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the exchange registry's Redis mirror (REQ-XCH-003); off by default, so the backend
 * starts without Redis.
 *
 * @param enabled whether the registry is mirrored into Redis at all
 * @param key the Redis key of the mirror document; must stay under {@code exchange:}, the only
 *     family the backend's ACL user may write
 * @param reconcileInterval the delay between two reconcile runs
 * @param closeWhenOff whether a start with mirroring off switches off a document left under {@code
 *     key}; a start that tries it reaches Redis once and survives a failure
 */
@Validated
@ConfigurationProperties("app.exchange.mirror")
public record ExchangeMirrorProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("exchange:registry") @NotBlank @Pattern(regexp = "exchange:[a-z0-9:_-]+")
        String key,
    @DefaultValue("PT60S") Duration reconcileInterval,
    @DefaultValue("true") boolean closeWhenOff) {}
