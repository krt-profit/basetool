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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * A change set the ingest gateway staged because the mass-change guard held it back, as the
 * member's browser session hands it back for a preview or a confirmation (REQ-XCH-021).
 *
 * @param clientId the client that sent it
 * @param installationKey the installation that sent it
 * @param resource {@code blueprints}, {@code stock} or {@code ships}
 * @param changeSet the change set as the client sent it, as JSON
 * @param stagedAt when the gateway staged it, in UTC
 */
public record ConnectedAppMassChangeRequestDto(
    @NotBlank @Size(max = 64) String clientId,
    @NotBlank @Size(max = 64) String installationKey,
    @NotBlank @Pattern(regexp = "^(blueprints|stock|ships)$") String resource,
    @NotBlank @Size(max = 524288) String changeSet,
    @NotNull Instant stagedAt) {}
