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

package de.greluc.krt.profit.basetool.testsupport.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code <springProfile>} resolution the three prod-sink masking tests rely on. A resolver
 * that kept the wrong block would let those tests exercise the dev appenders and pass for the wrong
 * reason, so both directions — the kept block and the dropped one — are asserted.
 */
class ProfiledLogbackConfigTest {

  private static final String XML =
      """
      <configuration>
        <springProfile name="prod"><appender name="PROD"/></springProfile>
        <springProfile name="!prod"><appender name="DEV"/></springProfile>
        <springProfile name="test, prod"><appender name="EITHER"/></springProfile>
      </configuration>
      """;

  @Test
  void keepsTheProdBlocksAndDropsTheNegatedOneWhenProdIsActive() {
    String resolved = ProfiledLogbackConfig.resolveProfiles(XML, Set.of("prod"));

    assertThat(resolved)
        .contains("<appender name=\"PROD\"/>", "<appender name=\"EITHER\"/>")
        .doesNotContain("DEV", "springProfile");
  }

  @Test
  void keepsOnlyTheNegatedBlockWhenNoProfileIsActive() {
    String resolved = ProfiledLogbackConfig.resolveProfiles(XML, Set.of());

    assertThat(resolved)
        .contains("<appender name=\"DEV\"/>")
        .doesNotContain("PROD", "EITHER", "springProfile");
  }

  @Test
  void rejectsANestedBlockInsteadOfMisreadingIt() {
    String nested =
        "<springProfile name=\"prod\"><springProfile name=\"x\"/></springProfile></springProfile>";

    assertThatThrownBy(() -> ProfiledLogbackConfig.resolveProfiles(nested, Set.of("prod")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nested");
  }

  @Test
  void evaluatesACommaListAsAnyOf() {
    assertThat(ProfiledLogbackConfig.matches("test, prod", Set.of("prod"))).isTrue();
    assertThat(ProfiledLogbackConfig.matches("test, prod", Set.of("dev"))).isFalse();
    assertThat(ProfiledLogbackConfig.matches("!prod", Set.of("dev"))).isTrue();
    assertThat(ProfiledLogbackConfig.matches("!prod", Set.of("prod"))).isFalse();
  }
}
