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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The shape of the error model (ADR-0235, REQ-API-019): the sealed root permits only the kernel's
 * generic kinds and {@link DomainProblem}, and every module exception and every module problem-code
 * enum lives in its module's {@code api} package, never in the kernel.
 */
class ErrorModelShapeTest {

  /** A module's published package: one segment below the backend root, then {@code api}. */
  private static final Pattern MODULE_API_PACKAGE =
      Pattern.compile("de\\.greluc\\.krt\\.profit\\.basetool\\.backend\\.[a-z]+\\.api");

  /** How many module exceptions existed when the floor was last raised. */
  private static final int MODULE_EXCEPTION_FLOOR = 6;

  /** How many module problem-code enums existed when the floor was last raised. */
  private static final int MODULE_ENUM_FLOOR = 6;

  /** A planted module exception declared in the kernel package. */
  static final class FixtureKernelDomainProblem extends DomainProblem {

    /** Creates the fixture. */
    FixtureKernelDomainProblem() {
      super("fixture");
    }

    /**
     * Returns the fixture status.
     *
     * @return {@code 409}
     */
    @Override
    public HttpStatus status() {
      return HttpStatus.CONFLICT;
    }

    /**
     * Returns the fixture code.
     *
     * @return {@code PLANTED}
     */
    @Override
    public String code() {
      return "PLANTED";
    }

    /**
     * Returns the fixture log label.
     *
     * @return {@code "Planted"}
     */
    @Override
    public String logLabel() {
      return "Planted";
    }
  }

  @Test
  @DisplayName("the sealed root permits exactly the generic kinds and DomainProblem")
  void theSealedRootPermitsTheGenericKindsAndDomainProblem() {
    Set<String> permitted = new TreeSet<>();
    for (Class<?> type : AppException.class.getPermittedSubclasses()) {
      permitted.add(type.getSimpleName());
      assertThat(type.getPackageName()).isEqualTo(AppException.class.getPackageName());
    }

    assertThat(permitted)
        .as(
            "a module exception extends DomainProblem from its module's api package; a new generic"
                + " kind of the kernel is a reviewed change of this list (ADR-0235)")
        .containsExactlyInAnyOrder(
            "BadRequestException",
            "BusinessConflictException",
            "DomainProblem",
            "DuplicateEntityException",
            "EntityInUseException",
            "ExternalServiceException",
            "NotFoundException",
            "RateLimitExceededException",
            "ReportGenerationException");
    assertThat(DomainProblem.class.isSealed()).isFalse();
  }

  @Test
  @DisplayName("every module exception lives in a module's api package")
  void everyModuleExceptionLivesInAModuleApiPackage() {
    List<Class<?>> exceptions = ProblemCodeRegistry.mainTypes(DomainProblem.class);

    assertThat(exceptions).hasSizeGreaterThanOrEqualTo(MODULE_EXCEPTION_FLOOR);
    assertThat(outsideModuleApi(exceptions))
        .as("a DomainProblem subclass outside a module's api package (ADR-0235)")
        .isEmpty();
  }

  @Test
  @DisplayName("every problem-code enum but the kernel's lives in a module's api package")
  void everyModuleProblemCodeEnumLivesInAModuleApiPackage() {
    List<Class<?>> enums =
        ProblemCodeRegistry.registryEnums().stream()
            .filter(type -> type != CoreProblemCode.class)
            .toList();

    assertThat(enums).hasSizeGreaterThanOrEqualTo(MODULE_ENUM_FLOOR);
    assertThat(outsideModuleApi(enums))
        .as("a module's ProblemCode enum outside its api package (ADR-0235)")
        .isEmpty();
  }

  @Test
  @DisplayName("a planted kernel-package module exception and code enum are caught")
  void aPlantedKernelPackageTypeIsCaught() {
    assertThat(
            outsideModuleApi(
                Arrays.asList(
                    FixtureKernelDomainProblem.class,
                    ProblemCodeRegistryTest.FixtureClashingCodes.class)))
        .containsExactly(
            FixtureKernelDomainProblem.class.getName(),
            ProblemCodeRegistryTest.FixtureClashingCodes.class.getName());
  }

  /**
   * Lists the classes that are not top-level classes of a module's {@code api} package.
   *
   * @param types the classes to check
   * @return the names of the misplaced classes, sorted
   */
  private static @NotNull Set<String> outsideModuleApi(@NotNull Collection<Class<?>> types) {
    Set<String> misplaced = new TreeSet<>();
    for (Class<?> type : types) {
      if (type.isMemberClass() || !MODULE_API_PACKAGE.matcher(type.getPackageName()).matches()) {
        misplaced.add(type.getName());
      }
    }
    return misplaced;
  }
}
