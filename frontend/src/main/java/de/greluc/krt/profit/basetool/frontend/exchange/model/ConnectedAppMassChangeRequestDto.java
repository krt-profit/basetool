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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import org.jetbrains.annotations.NotNull;

/**
 * A staged change set the page hands back to the backend for a preview or a confirmation
 * (REQ-XCH-021).
 *
 * @param clientId the client that sent it
 * @param installationKey the installation that sent it
 * @param resource {@code blueprints}, {@code stock} or {@code ships}
 * @param changeSet the change set as the client sent it, as JSON
 * @param stagedAt when the gateway staged it
 */
@DtoMirror
public record ConnectedAppMassChangeRequestDto(
    @NotNull String clientId,
    @NotNull String installationKey,
    @NotNull String resource,
    @NotNull String changeSet,
    @NotNull Instant stagedAt) {}
