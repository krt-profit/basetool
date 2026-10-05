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

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.architecturefixture.modulith.ModulithFixtureApplication;
import de.greluc.krt.profit.basetool.architecturefixture.modulithapi.ModulithApiFixtureApplication;
import de.greluc.krt.profit.basetool.backend.BackendApplication;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;
import org.springframework.modulith.core.Violations;
import org.springframework.modulith.docs.Documenter;

/**
 * Spring Modulith verification of the backend in test scope, with explicitly annotated module
 * detection (REQ-MOD-005).
 *
 * <p>The detected modules must equal {@link #DECLARED_MODULES}: every backend package named after a
 * module of the domain map carries {@code @ApplicationModule}, its {@code api} package tree is its
 * only named interface, and its allowed dependencies are the declared modules the domain map's
 * ranks and {@code allow} rows permit. A planted fixture proves the verification reports a module
 * reached past its named interface and a dependency the declaration does not allow.
 */
class ModularityTest {

  /** The backend packages annotated with {@code @ApplicationModule}, by module name. */
  private static final Set<String> DECLARED_MODULES =
      Set.of(
          "admin",
          "audit",
          "bank",
          "catalogue",
          "dashboard",
          "exchange",
          "identity",
          "inventory",
          "joborder",
          "kernel",
          "livesync",
          "materialexchange",
          "mission",
          "notification",
          "operation",
          "orgchart",
          "orgunit",
          "platform",
          "privacy",
          "refinery",
          "scope");

  private static final int DECLARED_MODULE_FLOOR = 21;

  /**
   * Modules without an {@code api} package: their types lie in the base package, which is their
   * unnamed interface, and a dependent allows them by bare module name.
   */
  private static final Set<String> BASE_PACKAGE_MODULES = Set.of("kernel");

  /**
   * Modules that publish nothing yet: no {@code api} package and no type in the base package, so no
   * other module may depend on them and no declaration allows them.
   */
  private static final Set<String> INTERNAL_ONLY_MODULES =
      Set.of("dashboard", "operation", "orgchart");

  private static final String API = "api";

  private static final Path DOCUMENTATION = Path.of("build", "spring-modulith-docs");

  private static final ApplicationModules BACKEND = ApplicationModules.of(BackendApplication.class);

  @Test
  void backendDetectsExactlyTheDeclaredModulesAndVerifies() {
    assertThat(DECLARED_MODULES).hasSizeGreaterThanOrEqualTo(DECLARED_MODULE_FLOOR);
    assertThat(names(BACKEND)).isEqualTo(DECLARED_MODULES);
    assertThat(BACKEND.withinRootPackages(ModuleSubjects.BACKEND_PACKAGE + ".service")).isTrue();
    BACKEND.verify();
  }

  @Test
  void everyBackendPackageNamedAfterADomainMapModuleIsDeclared() {
    DomainMap map = DomainMap.load();
    String prefix = ModuleSubjects.BACKEND_PACKAGE + ".";
    Set<String> modulePackages =
        ModuleSubjects.importBackendMainClasses().stream()
            .map(JavaClass::getPackageName)
            .filter(name -> name.startsWith(prefix))
            .map(name -> name.substring(prefix.length()).split("\\.", 2)[0])
            .filter(map.modules()::contains)
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(modulePackages).hasSizeGreaterThanOrEqualTo(DECLARED_MODULE_FLOOR);
    assertThat(modulePackages).isEqualTo(new TreeSet<>(DECLARED_MODULES));
  }

  @Test
  void everyDeclaredModulePublishesItsWholeApiTreeAsItsOnlyNamedInterface() {
    JavaClasses classes = ModuleSubjects.importBackendMainClasses();
    BACKEND.forEach(
        module -> {
          String id = module.getIdentifier().toString();
          if (BASE_PACKAGE_MODULES.contains(id)) {
            assertBasePackageIsTheOnlyInterface(module, classes);
            return;
          }
          if (INTERNAL_ONLY_MODULES.contains(id)) {
            assertModulePublishesNothing(module, classes);
            return;
          }
          String apiPackage = module.getBasePackage().getName() + "." + API;
          Set<String> named =
              module.getNamedInterfaces().stream()
                  .filter(NamedInterface::isNamed)
                  .map(NamedInterface::getName)
                  .collect(Collectors.toUnmodifiableSet());
          Set<String> published =
              module.getNamedInterfaces().getByName(API).stream()
                  .flatMap(NamedInterface::asJavaClasses)
                  .map(JavaClass::getName)
                  .collect(Collectors.toUnmodifiableSet());
          List<String> apiTree =
              ModuleSubjects.topLevelClasses(classes)
                  .map(JavaClass::getName)
                  .filter(name -> name.startsWith(apiPackage + "."))
                  .toList();

          assertThat(named).as(id).containsExactly(API);
          assertThat(apiTree).as(id).isNotEmpty();
          assertThat(published).as(id).containsAll(apiTree);
          assertThat(published).as(id).allMatch(name -> name.startsWith(apiPackage + "."));
        });
  }

  private static void assertBasePackageIsTheOnlyInterface(
      org.springframework.modulith.core.ApplicationModule module, JavaClasses classes) {
    String id = module.getIdentifier().toString();
    String basePackage = module.getBasePackage().getName();
    Set<String> named =
        module.getNamedInterfaces().stream()
            .filter(NamedInterface::isNamed)
            .map(NamedInterface::getName)
            .collect(Collectors.toUnmodifiableSet());
    List<String> moduleTree =
        ModuleSubjects.topLevelClasses(classes)
            .map(JavaClass::getName)
            .filter(name -> name.startsWith(basePackage + "."))
            .toList();

    assertThat(named).as(id).isEmpty();
    assertThat(moduleTree).as(id).isNotEmpty();
    assertThat(moduleTree)
        .as(id + " keeps every type in its base package")
        .allMatch(name -> name.substring(0, name.lastIndexOf('.')).equals(basePackage));
  }

  private static void assertModulePublishesNothing(
      org.springframework.modulith.core.ApplicationModule module, JavaClasses classes) {
    String id = module.getIdentifier().toString();
    String basePackage = module.getBasePackage().getName();
    Set<String> named =
        module.getNamedInterfaces().stream()
            .filter(NamedInterface::isNamed)
            .map(NamedInterface::getName)
            .collect(Collectors.toUnmodifiableSet());
    List<String> moduleTree =
        ModuleSubjects.topLevelClasses(classes)
            .map(JavaClass::getName)
            .filter(name -> name.startsWith(basePackage + "."))
            .toList();

    assertThat(named).as(id).isEmpty();
    assertThat(moduleTree).as(id).isNotEmpty();
    assertThat(moduleTree)
        .as(id + " keeps every type below its base package and outside an api package")
        .allMatch(
            name ->
                !name.substring(0, name.lastIndexOf('.')).equals(basePackage)
                    && !name.startsWith(basePackage + "." + API + "."));
  }

  @Test
  void reportsATypeInTheBasePackageOfAModuleThatMustPublishNothing() {
    org.springframework.modulith.core.ApplicationModule kernel =
        BACKEND.getModuleByName("kernel").orElseThrow();

    assertThatThrownBy(
            () -> assertModulePublishesNothing(kernel, ModuleSubjects.importBackendMainClasses()))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("keeps every type below its base package");
  }

  @Test
  void everyDeclarationAllowsExactlyTheApisOfTheDeclaredModulesTheDomainMapPermits()
      throws ClassNotFoundException {
    DomainMap map = DomainMap.load();
    for (String module : DECLARED_MODULES) {
      ApplicationModule declaration =
          Class.forName(ModuleSubjects.BACKEND_PACKAGE + "." + module + ".package-info")
              .getAnnotation(ApplicationModule.class);
      Set<String> permitted =
          DECLARED_MODULES.stream()
              .filter(target -> !target.equals(module) && map.mayDependOn(module, target))
              .filter(target -> !INTERNAL_ONLY_MODULES.contains(target))
              .map(target -> BASE_PACKAGE_MODULES.contains(target) ? target : target + "::" + API)
              .collect(Collectors.toCollection(TreeSet::new));

      assertThat(declaration).as(module).isNotNull();
      assertThat(declaration.type()).as(module).isEqualTo(ApplicationModule.Type.CLOSED);
      assertThat(new TreeSet<>(Arrays.asList(declaration.allowedDependencies())))
          .as("allowedDependencies of " + module)
          .isEqualTo(permitted);
    }
  }

  @Test
  void documenterWritesTheModuleDocumentationIntoTheBuildDirectory() {
    new Documenter(
            BACKEND, Documenter.Options.defaults().withOutputFolder(DOCUMENTATION.toString()))
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

  @Test
  void reportsAModuleReachedPastItsNamedInterfaceOrBeyondItsAllowedDependencies() {
    ApplicationModules fixture =
        ApplicationModules.of(ModulithApiFixtureApplication.class, includeTestClasses());

    assertThat(names(fixture))
        .containsExactlyInAnyOrder("provider", "consumer", "intruder", "closed");
    Violations violations = fixture.detectViolations();
    assertThat(violations.getMessages())
        .anySatisfy(message -> assertThat(message).contains("IntruderService", "ProviderInternal"))
        .anySatisfy(message -> assertThat(message).contains("ClosedService", "ProviderApi"))
        .noneSatisfy(message -> assertThat(message).contains("ConsumerService"))
        .noneSatisfy(message -> assertThat(message).contains("ProviderEvent"));
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
