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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Distinguishes known physical intervals from unverifiable quantity restored by a real journal. */
public sealed interface StockQuantityEvidence
    permits StockQuantityEvidence.KnownPhysical, LegacyJournalQuantityReference {

  /**
   * Returns the represented physical amount, independently of contribution eligibility.
   *
   * @return positive thousandths of a unit
   */
  long quanta();

  /**
   * Serializes each explicit evidence kind through its closed three-field value.
   *
   * @return the unchanged physical fragment or the real journal-reference interval
   */
  @JsonValue
  default @NotNull Object serializationValue() {
    return switch (this) {
      case KnownPhysical known -> known.quantity();
      case LegacyJournalQuantityReference legacy ->
          Map.of(
              "journalReference", legacy.journalReference(),
              "fromQuanta", legacy.fromQuanta(),
              "toQuanta", legacy.toQuanta());
    };
  }

  /**
   * Decodes only an explicit three-field physical interval or real journal reference.
   *
   * @param value the closed JSON object, with integral offsets and a canonical UUID string
   * @return typed immutable evidence without retaining the input map
   * @throws IllegalArgumentException when the shape, UUID or number kind is invalid
   * @throws ArithmeticException when an integral offset does not fit a long
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  static @NotNull StockQuantityEvidence fromJson(@NotNull Map<String, Object> value) {
    boolean physical = value.keySet().equals(Set.of("origin", "fromQuanta", "toQuanta"));
    boolean reference = value.keySet().equals(Set.of("journalReference", "fromQuanta", "toQuanta"));
    if (!physical && !reference) {
      throw new IllegalArgumentException(
          "Quantity evidence requires one explicit three-field domain");
    }
    UUID identity = uuid(value.get(physical ? "origin" : "journalReference"));
    long from = integral(value.get("fromQuanta"));
    long to = integral(value.get("toQuanta"));
    return physical
        ? new KnownPhysical(new StockQuantityFragment(identity, from, to))
        : new LegacyJournalQuantityReference(identity, from, to);
  }

  /** Requires canonical UUID text without accepting a storage or type-name default. */
  private static UUID uuid(@Nullable Object value) {
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("Quantity evidence requires a UUID string");
    }
    UUID identity = UUID.fromString(text);
    if (!identity.toString().equalsIgnoreCase(text)) {
      throw new IllegalArgumentException("Quantity evidence requires canonical UUID text");
    }
    return identity;
  }

  /** Rejects floating, textual, boolean and overflowing evidence offsets. */
  private static long integral(@Nullable Object value) {
    return switch (value) {
      case Byte number -> number.longValue();
      case Short number -> number.longValue();
      case Integer number -> number.longValue();
      case Long number -> number;
      case BigInteger number -> number.longValueExact();
      case null, default -> throw new IllegalArgumentException("Quantity offsets must be integral");
    };
  }

  /**
   * A proved physical interval that retains its actual immutable quantity identity.
   *
   * @param quantity the existing three-field physical fragment
   */
  record KnownPhysical(@NotNull StockQuantityFragment quantity) implements StockQuantityEvidence {
    /** Requires the actual physical fragment. */
    public KnownPhysical {
      Objects.requireNonNull(quantity);
    }

    @Override
    public long quanta() {
      return quantity.quanta();
    }
  }
}
