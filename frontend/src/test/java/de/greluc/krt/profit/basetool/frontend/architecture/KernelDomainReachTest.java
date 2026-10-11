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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Holds the frontend kernel's reaches into the domain packages to a frozen list that may only
 * shrink (plan §5.1: the kernel is rank 0 and closed to domain meaning; §5.9 for the frontend).
 *
 * <p>A new kernel class that depends on a domain type fails the build; a removed reach fails it too
 * until the entry is deleted, so the list cannot hold a reach that no longer exists. The rule is
 * proven against a planted reach.
 */
class KernelDomainReachTest {

  private static final String BASE = "de.greluc.krt.profit.basetool.frontend";

  private static final String KERNEL = BASE + ".kernel.";

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  /**
   * The kernel's reaches into domain packages when the rule was introduced, as {@code kernel class
   * -> domain class}, relative to the frontend package. Delete an entry when its reach is inverted;
   * never add one.
   */
  private static final Set<String> FROZEN =
      Set.of(
          "kernel.backend.BackendApiClient -> identity.model.TermsDocumentDto",
          "kernel.backend.QualityTierCatalog -> catalogue.model.QualityTierDto",
          "kernel.layout.CapabilityFlagsAdvice -> orgunit.model.SquadronDto",
          "kernel.layout.LayoutContextLoader -> orgunit.model.OrgUnitMembershipOptionDto",
          "kernel.layout.LayoutMiscAdvice -> orgunit.model.OrgUnitMembershipOptionDto",
          "kernel.layout.OrgUnitContextAdvice -> orgunit.model.OrgUnitMembershipOptionDto",
          "kernel.layout.OrgUnitContextAdvice -> orgunit.model.SquadronDto",
          "kernel.security.BackendRoleSyncFilter -> identity.model.RegistrationStatusDto",
          "kernel.security.BackendRoleSyncFilter -> identity.model.UserDto",
          "kernel.security.TermsAcceptanceGateFilter -> identity.model.TermsStatusDto");

  @Test
  void theKernelReachesNoDomainBeyondTheFrozenList() {
    Set<String> reaches = reaches(MAIN, KERNEL);

    assertThat(reaches)
        .as(
            "kernel classes depending on a domain package; invert the dependency (move the type"
                + " into the kernel, or let the domain supply it) instead of adding a reach")
        .isSubsetOf(FROZEN);
    assertThat(FROZEN)
        .as("reaches that no longer exist; delete them from FROZEN so the list keeps shrinking")
        .isSubsetOf(reaches);
  }

  @Test
  void theRuleCatchesAPlantedReach() {
    JavaClasses planted = new ClassFileImporter().importClasses(PlantedKernelReach.class);

    assertThat(reaches(planted, BASE + ".architecture"))
        .containsExactly("architecture.KernelDomainReachTest -> identity.model.UserDto");
  }

  /**
   * Lists the dependencies from classes under a prefix into the domain packages, folded to their
   * top-level classes.
   *
   * @param classes the classes to search
   * @param kernelPrefix the package prefix of the classes treated as kernel
   * @return {@code origin -> target}, relative to the frontend package, sorted
   */
  static Set<String> reaches(JavaClasses classes, String kernelPrefix) {
    Set<String> found = new TreeSet<>();
    for (JavaClass type : classes) {
      if (!type.getPackageName().startsWith(kernelPrefix)) {
        continue;
      }
      for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
        JavaClass target = outermost(dependency.getTargetClass());
        String targetPackage = target.getPackageName();
        if (targetPackage.startsWith(BASE + ".")
            && !targetPackage.startsWith(KERNEL)
            && !targetPackage.startsWith(kernelPrefix)) {
          found.add(relative(outermost(type)) + " -> " + relative(target));
        }
      }
    }
    return found;
  }

  private static String relative(JavaClass type) {
    return type.getName().substring(BASE.length() + 1);
  }

  private static JavaClass outermost(JavaClass type) {
    JavaClass current = type;
    while (current.getEnclosingClass().isPresent()) {
      current = current.getEnclosingClass().get();
    }
    return current;
  }

  /** A planted kernel-side class reading a domain type. */
  static final class PlantedKernelReach {

    /**
     * Reads a domain type the kernel must not know.
     *
     * @param user a user
     * @return the user's id
     */
    String read(UserDto user) {
      return String.valueOf(user.id());
    }
  }
}
