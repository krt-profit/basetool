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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * JSON body of the in-place add on the admin default-blueprints page (REQ-INV-017, REQ-FE-001): the
 * product keys the admin staged in the type-ahead, sent in one request and relayed to the backend
 * one add per key. Frontend-only — the backend takes a single {@link DefaultBlueprintCreateRequest}
 * per call.
 *
 * @param productKeys the staged normalized product keys; {@code null} or blank entries are skipped
 */
public record DefaultBlueprintAddSelectionRequest(@Nullable List<String> productKeys) {}
