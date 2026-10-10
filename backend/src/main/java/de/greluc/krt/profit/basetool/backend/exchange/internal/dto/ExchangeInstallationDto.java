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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * The calling installation, in the shape of the published {@code installation.schema.json}
 * (REQ-XCH-007).
 *
 * @param installationId the opaque installation id, never the key thumbprint
 * @param label the label, or {@code null} while the client has set none
 * @param firstSeenAt when the installation was first seen
 * @param lastSeenAt when it was last seen
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeInstallationDto(
    String installationId, String label, Instant firstSeenAt, Instant lastSeenAt) {}
