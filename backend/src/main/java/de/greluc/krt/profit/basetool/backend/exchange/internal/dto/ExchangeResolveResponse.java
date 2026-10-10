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
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;

/**
 * The answer to a {@code catalog/resolve} request, in the shape of the published {@code
 * resolve-response.schema.json} (REQ-XCH-012).
 *
 * @param results one result per reference, in request order
 * @param warnings what the server noticed but did not refuse, at most {@value #MAX_WARNINGS}, or
 *     {@code null} when there is none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeResolveResponse(List<Result> results, List<Warning> warnings) {

  /** The most candidates an ambiguous result lists. */
  public static final int MAX_CANDIDATES = 10;

  /** The most warnings one response carries. */
  public static final int MAX_WARNINGS = 50;

  /**
   * The result for one reference.
   *
   * @param index the reference's position in the request
   * @param status how it resolved
   * @param ref the catalogue entry, when {@code status} is {@code resolved}
   * @param candidates the entries it could mean, when {@code status} is {@code ambiguous}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Result(int index, Status status, Entry ref, List<Entry> candidates) {}

  /**
   * A catalogue entry.
   *
   * @param bt the Basetool's own key
   * @param name the entry's display name
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Entry(String bt, String name) {}

  /**
   * A warning about one field of the request.
   *
   * @param pointer the JSON Pointer to the field
   * @param code the warning code
   */
  public record Warning(String pointer, String code) {}

  /** How a reference resolved. */
  public enum Status {

    /** Exactly one catalogue entry. */
    RESOLVED,

    /** Several catalogue entries, or only fuzzy suggestions. */
    AMBIGUOUS,

    /** No catalogue entry. */
    UNMATCHED;

    /**
     * Returns the wire form of the status.
     *
     * @return the lower-case name
     */
    @JsonValue
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }
  }
}
