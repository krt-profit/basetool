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

package de.greluc.krt.profit.basetool.frontend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

/** Pins which record components {@link WireComponents} reports as the wire's properties. */
class WireComponentsTest {

  /**
   * A record without unwrapped components.
   *
   * @param first the first component
   * @param second the second component
   */
  record Plain(String first, Long second) {}

  /**
   * A record that unwraps another between two own components.
   *
   * @param before the component before the unwrapped one
   * @param plain the unwrapped record
   * @param after the component after the unwrapped one
   */
  record Wrapping(String before, @JsonUnwrapped Plain plain, String after) {}

  /**
   * A record that unwraps a type which is no record.
   *
   * @param text the unwrapped string
   */
  record UnwrapsAString(@JsonUnwrapped String text) {}

  @Test
  void aRecordWithoutUnwrappedComponentsIsItsOwnWire() {
    assertThat(WireComponents.of(Plain.class))
        .extracting(RecordComponent::getName)
        .containsExactly("first", "second");
  }

  @Test
  void anUnwrappedComponentStandsForTheComponentsOfItsRecordInPlace() {
    assertThat(WireComponents.of(Wrapping.class))
        .extracting(RecordComponent::getName)
        .containsExactly("before", "first", "second", "after");
  }

  @Test
  void unwrappingATypeThatIsNoRecordIsRefused() {
    assertThatThrownBy(() -> WireComponents.of(UnwrapsAString.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UnwrapsAString.text");
  }
}
