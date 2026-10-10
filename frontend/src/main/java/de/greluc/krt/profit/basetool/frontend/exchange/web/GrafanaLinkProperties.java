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

package de.greluc.krt.profit.basetool.frontend.exchange.web;

import org.hibernate.validator.constraints.URL;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where admin pages link into Grafana (REQ-XCH-028).
 *
 * @param operationsDashboardUrl the {@code https} URL of the operations dashboard, whose panels
 *     show each exchange client's refusals and relay outcomes; blank shows the hint without a link
 */
@Validated
@ConfigurationProperties(prefix = "app.grafana")
public record GrafanaLinkProperties(
    @Nullable @URL(protocol = "https") String operationsDashboardUrl) {}
