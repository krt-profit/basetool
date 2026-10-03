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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.architecturefixture.modulith.ModulithFixtureApplication;
import de.greluc.krt.profit.basetool.backend.BackendApplication;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;
import org.springframework.modulith.docs.Documenter;

/**
 * Spring Modulith verification of the backend in test scope, with explicitly annotated module
 * detection (REQ-MOD-005).
 *
 * <p>The detected modules must equal {@link #DECLARED_MODULES}, the packages that carry
 * {@code @ApplicationModule}; a planted fixture proves the verification reports a module reaching
 * into another module's internals.
 */
class ModularityTest {

  /** The backend packages annotated with {@code @ApplicationModule}, by module name. */
  private static final Set<String> DECLARED_MODULES = Set.of();

  private static final Path DOCUMENTATION = Path.of("build", "spring-modulith-docs");

  @Test
  void backendDetectsExactlyTheDeclaredModulesAndVerifies() {
    ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    assertThat(names(modules)).isEqualTo(DECLARED_MODULES);
    assertThat(modules.withinRootPackages(ModuleSubjects.BACKEND_PACKAGE + ".service")).isTrue();
    modules.verify();
  }

  @Test
  void documenterWritesTheModuleDocumentationIntoTheBuildDirectory() {
    ApplicationModules modules = ApplicationModules.of(BackendApplication.class);

    new Documenter(
            modules, Documenter.Options.defaults().withOutputFolder(DOCUMENTATION.toString()))
        .writeDocumentation();

    assertThat(DOCUMENTATION.resolve("components.puml")).isRegularFile();
  }

  @Test
  void reportsAModuleReachingIntoAnotherModulesInternals() {
    ApplicationModules fixture =
        ApplicationModules.of(ModulithFixtureApplication.class, includeTestClasses());

    assertThat(names(fixture)).containsExactlyInAnyOrder("alpha", "beta");
    Violations violations = fixture.detectViolations();
    assertThat(violations.hasViolations()).isTrue();
    assertThat(violations.getMessages())
        .anySatisfy(message -> assertThat(message).contains("AlphaInternal"));
    assertThatThrownBy(fixture::verify).isInstanceOf(Violations.class);
  }

  private static ImportOption includeTestClasses() {
    return location -> true;
  }

  private static Set<String> names(ApplicationModules modules) {
    return modules.stream()
        .map(module -> module.getIdentifier().toString())
        .collect(Collectors.toUnmodifiableSet());
  }
}
