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

package de.greluc.krt.profit.basetool.frontend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Trusted reverse-proxy allowlist for resolving the originating client IP (REQ-SEC-011).
 *
 * <p>{@code X-Forwarded-For} is honoured only when the immediate TCP peer matches an entry. Entries
 * are exact IPs or CIDR ranges; {@code "*"} is not honoured.
 *
 * @param trustedProxies exact IPs or CIDR ranges of trusted reverse proxies; empty (the default via
 *     {@link DefaultValue}) trusts none, so the raw TCP peer is used
 */
@Validated
@ConfigurationProperties(prefix = "app.client-ip")
public record ClientIpProperties(@DefaultValue List<String> trustedProxies) {}
