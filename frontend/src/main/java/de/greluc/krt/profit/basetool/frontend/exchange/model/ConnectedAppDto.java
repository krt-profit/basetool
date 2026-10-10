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

package de.greluc.krt.profit.basetool.frontend.exchange.model;

import java.util.List;

/**
 * Mirror of one of the member's connected exchange clients (REQ-XCH-008, REQ-XCH-032).
 *
 * @param clientId the Keycloak client id
 * @param displayName the product name, always shown before an installation's label
 * @param capabilities the scopes the registry grants the client
 * @param installations the live installations, newest first
 * @param activity the client's latest writes to the member's data, newest first
 */
public record ConnectedAppDto(
    String clientId,
    String displayName,
    List<String> capabilities,
    List<ConnectedInstallationDto> installations,
    List<ConnectedAppActivityDto> activity) {}
