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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code ShopTerminalOptionDto}: a UEX terminal an admin can link a
 * shop to (REQ-P4K-043).
 *
 * @param id the terminal
 * @param name its UEX name, or {@code null}
 * @param starSystem its star system's name, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@DtoMirror
public record ShopTerminalOptionDto(UUID id, String name, String starSystem) {}
