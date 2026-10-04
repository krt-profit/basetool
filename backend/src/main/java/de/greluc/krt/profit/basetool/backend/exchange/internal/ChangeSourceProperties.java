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
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Which Keycloak clients count as the web and as the app when the change feed records who changed
 * an entry (REQ-XCH-013, ADR-0224).
 *
 * @param webClientIds the clients of the member's browser session
 * @param appClientIds the clients of the Android app
 */
@Validated
@ConfigurationProperties(prefix = "app.exchange.change-source")
public record ChangeSourceProperties(
    @DefaultValue("basetool-frontend") @NotNull List<String> webClientIds,
    @DefaultValue("basetool-android") @NotNull List<String> appClientIds) {}
