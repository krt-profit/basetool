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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A {@code catalog/resolve} request, in the shape of the published {@code
 * resolve-request.schema.json} (REQ-XCH-012).
 *
 * @param kind the catalogue every reference is looked up in
 * @param refs the references, 1 to {@value #MAX_REFS}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeResolveRequest(
    @NotNull ExchangeCatalogKind kind,
    @NotNull @Size(min = 1, max = MAX_REFS) List<@NotNull @Valid ExchangeItemRef> refs) {

  /** The most references one request may carry. */
  public static final int MAX_REFS = 500;
}
