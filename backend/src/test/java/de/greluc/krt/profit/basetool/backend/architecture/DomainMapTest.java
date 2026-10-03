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
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guard G-09 on the backend: every production class belongs to exactly one module of the domain
 * map, no rule of the map is dead, and simple class names are unique (REQ-MOD-001, REQ-MOD-002).
 */
class DomainMapTest {

  private static final JavaClasses CLASSES = ModuleSubjects.importBackendMainClasses();

  private static final DomainMap MAP = DomainMap.load();

  private static final int SOURCE_TYPE_FLOOR = 1389;

  private static final int MAPPER_FLOOR = 49;

  private static final int MODULE_COUNT = 26;

  @Test
  void everyBackendClassIsAssignedToExactlyOneModule() {
    List<JavaClass> subjects = ModuleSubjects.subjects(CLASSES);

    assertThat(subjects).hasSizeGreaterThanOrEqualTo(SOURCE_TYPE_FLOOR);
    assertThat(DomainMapChecks.unassigned(MAP, subjects)).isEmpty();
  }

  @Test
  void everyRuleOfTheDomainMapAssignsAClass() {
    assertThat(DomainMapChecks.deadRules(MAP, ModuleSubjects.subjects(CLASSES))).isEmpty();
  }

  @Test
  void everyDeclaredModuleOwnsAClass() {
    assertThat(MAP.modules()).hasSize(MODULE_COUNT);
    assertThat(DomainMapChecks.emptyModules(MAP, ModuleSubjects.subjects(CLASSES))).isEmpty();
  }

  @Test
  void simpleNamesOfBackendClassesAreUnique() {
    List<JavaClass> topLevel = ModuleSubjects.topLevelClasses(CLASSES).toList();

    assertThat(topLevel).hasSizeGreaterThanOrEqualTo(SOURCE_TYPE_FLOOR + MAPPER_FLOOR);
    assertThat(DomainMapChecks.duplicateSimpleNames(topLevel)).isEmpty();
  }

  @Test
  void generatedMapperImplementationsBelongToTheirMapper() {
    List<JavaClass> implementations =
        ModuleSubjects.topLevelClasses(CLASSES)
            .filter(javaClass -> javaClass.getSimpleName().endsWith("MapperImpl"))
            .toList();

    assertThat(implementations).hasSizeGreaterThanOrEqualTo(MAPPER_FLOOR);
    assertThat(implementations)
        .allSatisfy(
            implementation ->
                assertThat(
                        ModuleSubjects.subjectOf(implementation, CLASSES).getSimpleName() + "Impl")
                    .isEqualTo(implementation.getSimpleName()));
  }

  @Test
  void nestedClassesBelongToTheirTopLevelClass() {
    List<JavaClass> nested =
        CLASSES.stream().filter(javaClass -> javaClass.getName().contains("$")).toList();

    assertThat(nested).isNotEmpty();
    assertThat(nested)
        .allSatisfy(
            javaClass ->
                assertThat(javaClass.getName())
                    .startsWith(ModuleSubjects.subjectOf(javaClass, CLASSES).getName() + "$"));
  }
}
