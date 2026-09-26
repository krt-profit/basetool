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

package de.greluc.krt.profit.basetool.backend.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Entities}: both message styles, present-value pass-through and the laziness
 * of the {@code Supplier} overload.
 */
class EntitiesTest {

  @Test
  void require_present_stringMessage_returnsSameValue() {
    Object value = new Object();
    assertSame(value, Entities.require(Optional.of(value), "must not be thrown"));
  }

  @Test
  void require_present_supplierMessage_returnsSameValue() {
    Object value = new Object();
    assertSame(value, Entities.require(Optional.of(value), () -> "must not be thrown"));
  }

  @Test
  void require_empty_stringMessage_throwsNotFoundWithThatMessage() {
    NotFoundException ex =
        assertThrows(
            NotFoundException.class, () -> Entities.require(Optional.empty(), "Mission not found"));
    assertEquals("Mission not found", ex.getMessage(), "detail is the caller's bare message");
  }

  @Test
  void require_empty_supplierMessage_throwsNotFoundWithInterpolatedMessage() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> Entities.require(Optional.empty(), () -> "Mission not found: " + id));
    assertEquals(
        "Mission not found: " + id,
        ex.getMessage(),
        "detail is the caller's id-suffixed message, byte-identical to the old lambda");
  }

  @Test
  void require_present_supplierIsNotEvaluated() {
    Object value = new Object();
    assertSame(
        value,
        Entities.require(Optional.of(value), () -> fail("message supplier evaluated on present")));
  }
}
