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

package de.greluc.krt.profit.basetool.testsupport.context;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * A top-level Spring test class without tests, which {@link TestContextKeys#springTestClasses} must
 * find together with its {@code @Nested} class but not its static one.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TestContextKeysTest.FixtureConfig.class)
class SpringFixture {

  /** A nested test class, which inherits the Spring extension. */
  @Nested
  class Inner {}

  /** A static helper, which JUnit never runs. */
  static class StaticHelper {}
}
