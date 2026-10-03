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

package de.greluc.krt.profit.basetool.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.testsupport.context.TestContextKeys;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Holds the backend's Spring test classes to a budget of distinct application contexts, so a new
 * {@code @MockitoBean} set or inlined property does not fragment the context cache again
 * (REQ-OPS-041).
 */
class TestContextBudgetTest {

  private static final int BUDGET = 36;

  private static final int SPRING_TEST_CLASS_FLOOR = 236;

  @Test
  void theSpringTestClassesStayWithinTheContextBudget() {
    List<Class<?>> classes =
        TestContextKeys.springTestClasses(
            Path.of("build/classes/java/test"), getClass().getClassLoader());
    Map<MergedContextConfiguration, List<Class<?>>> groups = TestContextKeys.group(classes);

    assertThat(classes)
        .as("the scan must see the backend's Spring test classes")
        .hasSizeGreaterThanOrEqualTo(SPRING_TEST_CLASS_FLOOR);
    assertThat(groups.size())
        .as(
            "%d distinct test contexts exceed the budget of %d. Reuse an existing configuration"
                + " (plain @SpringBootTest, @LeafServiceMockTest) instead of a new @MockitoBean"
                + " set or property; mock a bean by the same field name everywhere. Contexts:%n%s",
            groups.size(), BUDGET, TestContextKeys.report(groups))
        .isLessThanOrEqualTo(BUDGET);
  }
}
