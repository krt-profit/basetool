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

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.time.Instant;

/**
 * Mirror of the global exchange switch (REQ-XCH-003).
 *
 * @param enabled whether the exchange serves any request
 * @param updatedAt when the switch last changed, or {@code null}
 * @param version the optimistic-lock version
 */
@DtoMirror
public record ExchangeSettingsDto(boolean enabled, Instant updatedAt, Long version) {}
