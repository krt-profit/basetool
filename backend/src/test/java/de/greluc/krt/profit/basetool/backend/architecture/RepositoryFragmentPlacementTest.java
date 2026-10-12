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

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins that a Spring Data fragment implementation lies in the package of its fragment interface.
 *
 * <p>Spring Data looks the {@code Impl} class up beside the interface; when it is not found there
 * the method is silently derived from its name as a query, which changes flushing and result
 * semantics without any startup error.
 */
class RepositoryFragmentPlacementTest {

  private static final int FRAGMENTS_FLOOR = 2;

  @Test
  void everyFragmentImplementationLiesBesideItsInterface() {
    List<JavaClass> fragments =
        ModuleSubjects.importBackendMainClasses().stream()
            .filter(c -> c.isInterface() && c.getSimpleName().endsWith("Fragment"))
            .toList();

    assertThat(fragments)
        .as("the fragment interfaces the test looks at")
        .hasSizeGreaterThanOrEqualTo(FRAGMENTS_FLOOR);
    for (JavaClass fragment : fragments) {
      String impl = fragment.getName() + "Impl";
      assertThat(
              ModuleSubjects.importBackendMainClasses().stream()
                  .filter(c -> c.getSimpleName().equals(fragment.getSimpleName() + "Impl"))
                  .map(JavaClass::getName)
                  .toList())
          .as("the implementation of %s", fragment.getName())
          .containsExactly(impl);
    }
  }
}
