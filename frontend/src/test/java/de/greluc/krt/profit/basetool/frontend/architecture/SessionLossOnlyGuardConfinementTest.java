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

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.OrgUnitContextAdvice;
import de.greluc.krt.profit.basetool.frontend.kernel.security.BackendRoleSyncFilter;
import de.greluc.krt.profit.basetool.frontend.kernel.security.SessionGateRead;
import de.greluc.krt.profit.basetool.frontend.kernel.web.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.navigation.web.NavigationPreferencesLoader;
import de.greluc.krt.profit.basetool.frontend.navigation.web.NavigationPreferencesReader;
import de.greluc.krt.profit.basetool.frontend.purchasing.web.PurchasingFeatureState;
import de.greluc.krt.profit.basetool.frontend.supply.web.SupplyFeatureState;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the session-loss-only fallback guard {@link
 * SectionFallback#rethrowIfReauthenticationRequired} to the code a session gate's refusal must not
 * leave (ADR-0385): the role-sync filter, a gate's own status read, the error handler and the
 * layout chrome, which also renders on the pages a gate leaves open. Every other fallback hands a
 * gate's refusal off through {@link SectionFallback#rethrowHandOff}.
 */
class SessionLossOnlyGuardConfinementTest {

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.frontend");

  /** The classes that may answer a gate's refusal locally. */
  private static final Set<String> ALLOWED =
      Stream.of(
              BackendRoleSyncFilter.class,
              SessionGateRead.class,
              GlobalExceptionHandler.class,
              LayoutContextLoader.class,
              OrgUnitContextAdvice.class,
              SupplyFeatureState.class,
              PurchasingFeatureState.class,
              NavigationPreferencesLoader.class,
              NavigationPreferencesReader.class)
          .map(Class::getName)
          .collect(Collectors.toCollection(TreeSet::new));

  @Test
  void onlyTheGateMachineryAndTheLayoutChromeGuardASessionLossAlone() {
    Set<String> callers =
        MAIN.stream()
            .filter(SessionLossOnlyGuardConfinementTest::callsTheSessionLossOnlyGuard)
            .map(JavaClass::getName)
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(callers)
        .as(
            "classes guarding only a session loss; a handler's fallback calls"
                + " SectionFallback.rethrowHandOff so a session gate's refusal reaches the gate")
        .isEqualTo(ALLOWED);
  }

  /**
   * Tells whether a class calls the session-loss-only guard, in a lambda body included.
   *
   * @param type the class
   * @return {@code true} when it calls {@link SectionFallback#rethrowIfReauthenticationRequired}
   */
  private static boolean callsTheSessionLossOnlyGuard(JavaClass type) {
    return type.getMethodCallsFromSelf().stream()
        .anyMatch(call ->
            call.getTargetOwner().isEquivalentTo(SectionFallback.class)
                && "rethrowIfReauthenticationRequired".equals(call.getName()));
  }
}
