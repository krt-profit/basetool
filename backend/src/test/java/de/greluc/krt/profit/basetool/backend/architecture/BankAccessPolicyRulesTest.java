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

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.architecture.fixture.BankAccessPolicyFixtures;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The three rules of the one bank access policy (ADR-0352): the policy and everything it calls
 * never read who calls from the security context, no bank class outside the policy reads the
 * caller's roles or seats, which is what every bank rule needs, and the exchange reaches the bank
 * only through the policy, the actor adapter and the reads (REQ-BXCH-001).
 *
 * <p>Every rule is proven able to fail on {@link BankAccessPolicyFixtures}.
 */
class BankAccessPolicyRulesTest {

  private static final String ROOT = "de.greluc.krt.profit.basetool.backend";

  private static final String BANK = ROOT + ".bank";

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackagesOf(BankAccessPolicyFixtures.class);

  /** The policy: its interface and decision types in {@code bank.api} and its implementation. */
  private static final Set<String> POLICY =
      Set.of(
          BANK + ".api.BankAccessPolicy",
          BANK + ".api.BankAccountAccess",
          BANK + ".api.BankAccountRight",
          BANK + ".api.BankActor",
          BANK + ".api.BankActors",
          BANK + ".api.BankAdmission",
          BANK + ".api.BankRequestApprover",
          BANK + ".api.BankStaffRights",
          BANK + ".internal.BankAccessRules",
          BANK + ".internal.BankAccessJudgement",
          BANK + ".internal.DatabaseBankActors");

  /** What the policy must never reach: the request's security context and its two readers. */
  private static final Set<String> CALLER_READERS =
      Set.of(
          SecurityContextHolder.class.getName(),
          AuthHelperService.class.getName(),
          RequestScopeResolver.class.getName());

  /** The role checks of {@link AuthHelperService}. */
  private static final Set<String> ROLE_CHECKS =
      Set.of("isAdmin", "hasReachableRole", "isMemberOrAbove", "isLogisticianOrAbove");

  /** The one {@link RequestScopeResolver} read a bank adapter may make: the pin as sent. */
  private static final Set<String> PIN_READS = Set.of("readActiveSquadronFromHeader");

  /** The exchange module, which reaches the bank on behalf of a connected application. */
  private static final String EXCHANGE = ROOT + ".exchange";

  /**
   * What the exchange may use of the bank (REQ-BXCH-001): the policy with its decision types, the
   * actor adapter and the reads, with their value types.
   */
  private static final Set<String> EXCHANGE_SURFACE =
      Set.of(
          BANK + ".api.BankAccessPolicy",
          BANK + ".api.BankAccountAccess",
          BANK + ".api.BankAccountRight",
          BANK + ".api.BankActor",
          BANK + ".api.BankActors",
          BANK + ".api.BankAdmission",
          BANK + ".api.BankRequestApprover",
          BANK + ".api.BankStaffRights",
          BANK + ".api.BankBookingRequestType",
          BANK + ".api.BankAccountReads");

  @Test
  @DisplayName("the bank access policy never reads the security context, directly or by a call")
  void thePolicyNeverReadsTheSecurityContext() {
    assertThat(PRODUCTION.stream().filter(c -> POLICY.contains(topLevel(c).getName())))
        .as("the policy classes the rule checks")
        .extracting(c -> topLevel(c).getName())
        .containsAll(POLICY);
    assertThat(callerReads(PRODUCTION, c -> POLICY.contains(topLevel(c).getName())))
        .as(
            """
            Calls by which the bank access policy reaches the security context. The policy \
            decides for an explicit BankActor; who calls is the adapter's business (ADR-0352).\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("no bank class outside the policy reads the caller's roles or seats")
  void noBankRuleLivesOutsideThePolicy() {
    assertThat(
            ruleReaders(
                PRODUCTION,
                c ->
                    c.getPackageName().startsWith(BANK) && !POLICY.contains(topLevel(c).getName())))
        .as(
            """
            Bank classes that read the caller's roles or seats themselves. Build the actor with \
            BankCaller and ask BankAccessRules instead (ADR-0352).\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("exchange code reaches the bank only through the policy, the reads and their values")
  void theExchangeReachesTheBankOnlyThroughThePolicy() {
    assertThat(bankReaches(PRODUCTION, c -> c.getPackageName().startsWith(EXCHANGE)))
        .as(
            """
            Exchange classes that use a bank type outside the policy, BankActors and \
            BankAccountReads. Every bank decision of the exchange goes through the policy \
            (REQ-BXCH-001, ADR-0352).\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: an exchange class using a bank internal is reported, the reads are not")
  void plantedExchangeReaderIsReported() {
    String reader = BankAccessPolicyFixtures.PlantedExchangeReader.class.getName();
    assertThat(bankReaches(FIXTURES, c -> c.getName().equals(reader)))
        .containsExactly(reader + " -> " + BANK + ".internal.BankAccountRepository");
  }

  @Test
  @DisplayName("proof: a policy reaching the security context one call away is reported")
  void plantedPolicyIsReported() {
    String policy = BankAccessPolicyFixtures.PlantedPolicy.class.getName();
    assertThat(callerReads(FIXTURES, c -> c.getName().equals(policy)))
        .containsExactly(
            policy
                + " -> "
                + BankAccessPolicyFixtures.PlantedContextReader.class.getName()
                + " -> "
                + SecurityContextHolder.class.getName());
  }

  @Test
  @DisplayName("proof: a planted role and seat check is reported, the caller and pin reads are not")
  void plantedRuleIsReported() {
    String fixtures = BankAccessPolicyFixtures.class.getName();
    String rule = BankAccessPolicyFixtures.PlantedRuleOutsideThePolicy.class.getName();
    assertThat(ruleReaders(FIXTURES, c -> c.getName().startsWith(fixtures)))
        .containsExactly(
            rule + ".checksTheRole -> AuthHelperService.isAdmin",
            rule + ".checksTheSeat -> RequestScopeResolver.currentUserIsOlMember");
  }

  /**
   * Follows the method calls from the selected classes through every called class of the backend
   * and reports each path that ends in a caller reader.
   *
   * @param classes the imported classes
   * @param start the classes to start from
   * @return one path per reached caller reader, as {@code start -> … -> reader}
   */
  @NotNull
  private static Set<String> callerReads(
      @NotNull JavaClasses classes, @NotNull Predicate<JavaClass> start) {
    Map<String, String> pathTo = new HashMap<>();
    Deque<JavaClass> queue = new ArrayDeque<>();
    for (JavaClass javaClass : classes) {
      if (start.test(javaClass)) {
        pathTo.put(javaClass.getName(), javaClass.getName());
        queue.add(javaClass);
      }
    }
    Set<String> reads = new TreeSet<>();
    while (!queue.isEmpty()) {
      JavaClass current = queue.removeFirst();
      String path = pathTo.get(current.getName());
      for (JavaCall<?> call : callsFrom(current)) {
        JavaClass target = call.getTargetOwner();
        if (CALLER_READERS.contains(target.getName())) {
          reads.add(path + " -> " + target.getName());
        } else if (target.getName().startsWith(ROOT)
            && classes.contain(target.getName())
            && !pathTo.containsKey(target.getName())) {
          pathTo.put(target.getName(), path + " -> " + target.getName());
          queue.add(classes.get(target.getName()));
        }
      }
    }
    return reads;
  }

  /**
   * Reports every call from the selected classes to a role check of {@link AuthHelperService} or to
   * a {@link RequestScopeResolver} method other than the pin read.
   *
   * @param classes the imported classes
   * @param scope the classes to check
   * @return {@code origin -> Owner.method} per offending call
   */
  @NotNull
  private static Set<String> ruleReaders(
      @NotNull JavaClasses classes, @NotNull Predicate<JavaClass> scope) {
    Set<String> readers = new TreeSet<>();
    for (JavaClass javaClass : classes) {
      if (!scope.test(javaClass)) {
        continue;
      }
      for (JavaCall<?> call : callsFrom(javaClass)) {
        String owner = call.getTargetOwner().getName();
        String name = call.getName();
        boolean roleCheck =
            owner.equals(AuthHelperService.class.getName()) && ROLE_CHECKS.contains(name);
        boolean seatRead =
            owner.equals(RequestScopeResolver.class.getName()) && !PIN_READS.contains(name);
        if (roleCheck || seatRead) {
          readers.add(
              javaClass.getName()
                  + "."
                  + call.getOrigin().getName()
                  + " -> "
                  + call.getTargetOwner().getSimpleName()
                  + "."
                  + name);
        }
      }
    }
    return readers;
  }

  /**
   * Reports every bank type outside {@link #EXCHANGE_SURFACE} the selected classes depend on.
   *
   * @param classes the imported classes
   * @param scope the classes to check
   * @return {@code origin -> bank type} per offending class and top-level bank type
   */
  @NotNull
  private static Set<String> bankReaches(
      @NotNull JavaClasses classes, @NotNull Predicate<JavaClass> scope) {
    Set<String> reaches = new TreeSet<>();
    for (JavaClass javaClass : classes) {
      if (!scope.test(javaClass)) {
        continue;
      }
      javaClass.getDirectDependenciesFromSelf().stream()
          .map(dependency -> topLevel(dependency.getTargetClass()).getName())
          .filter(target -> target.startsWith(BANK + ".") && !EXCHANGE_SURFACE.contains(target))
          .forEach(target -> reaches.add(javaClass.getName() + " -> " + target));
    }
    return reaches;
  }

  /**
   * The method and constructor calls a class makes.
   *
   * @param javaClass the class
   * @return its calls
   */
  @NotNull
  private static List<JavaCall<?>> callsFrom(@NotNull JavaClass javaClass) {
    return Stream.<JavaCall<?>>concat(
            javaClass.getMethodCallsFromSelf().stream(),
            javaClass.getConstructorCallsFromSelf().stream())
        .toList();
  }

  /**
   * The outermost class enclosing a class.
   *
   * @param javaClass the class
   * @return the top-level class
   */
  @NotNull
  private static JavaClass topLevel(@NotNull JavaClass javaClass) {
    JavaClass current = javaClass;
    while (current.getEnclosingClass().isPresent()) {
      current = current.getEnclosingClass().get();
    }
    return current;
  }
}
