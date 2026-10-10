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

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import de.greluc.krt.profit.basetool.backend.architecture.fixture.ListenerAndObserverFixtures;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAuditService;
import de.greluc.krt.profit.basetool.backend.kernel.RequestMemo;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.RequestScopeResolver;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The listener and observer rules of REQ-AUDIT-007: audit stays a synchronous call inside the
 * business transaction, listeners never read the request, and observer SPI implementations join the
 * caller's transaction.
 *
 * <p>A listener's reach is followed through every method of its own class it calls, lambdas and
 * method references included. Every rule is proven able to fail on {@link
 * ListenerAndObserverFixtures}.
 */
class ListenerAndObserverRulesTest {

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend");

  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackagesOf(ListenerAndObserverFixtures.class);

  /** Today's count of after-commit and asynchronous methods. */
  private static final int AFTER_COMMIT_OR_ASYNC_FLOOR = 8;

  /** Today's count of listener methods: after-commit, asynchronous and synchronous. */
  private static final int LISTENER_FLOOR = 14;

  /**
   * Observer SPIs today: {@code MembershipChangeObserver}; the fixtures prove the rule fails on a
   * non-compliant implementation.
   */
  private static final int OBSERVER_SPI_FLOOR = 1;

  /** The audit recorders whose rows must be written inside the business transaction. */
  private static final Set<String> AUDIT_RECORDERS =
      Set.of(
          AuditRecorder.class.getName(),
          AuditService.class.getName(),
          BankAuditService.class.getName());

  /** Types every member of which reads request-bound state. */
  private static final Set<String> REQUEST_BOUND_TYPES =
      Set.of(
          SecurityContextHolder.class.getName(),
          RequestContextHolder.class.getName(),
          RequestMemo.class.getName(),
          RequestScopeResolver.class.getName());

  /**
   * The {@link AuthHelperService} members that do not read the bound security context: {@code
   * runAs} binds an explicit authentication for the work it runs.
   */
  private static final Set<String> AUTH_HELPER_CONTEXT_FREE = Set.of("runAs");

  /**
   * After-commit or asynchronous methods allowed to record an audit row, keyed {@code
   * Class.method}, each with the reason.
   */
  static final Map<String, String> AUDITING_LISTENERS =
      Map.of(
          "de.greluc.krt.profit.basetool.backend.service.exchange.ExchangeDepartureService"
              + ".onDeparture",
          "Audits the departure work it performs itself (Keycloak consent and session removal,"
              + " revocations) in its own REQUIRES_NEW transaction after the roster sync"
              + " committed; it records no mutation of the publishing transaction (REQ-XCH-008).");

  @Test
  @DisplayName("no after-commit or asynchronous method records an audit row")
  void noAfterCommitOrAsyncMethodRecordsAudit() {
    List<JavaMethod> selected = afterCommitOrAsyncMethods(PRODUCTION);
    assertThat(selected)
        .as("after-commit and asynchronous methods (an emptied selection would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(AFTER_COMMIT_OR_ASYNC_FLOOR);
    assertThat(auditingMethods(selected))
        .as(
            """
            After-commit or asynchronous methods that record audit rows, against \
            AUDITING_LISTENERS. REQ-AUDIT-001 requires the audit row inside the transaction of \
            the mutation it records; call the recorder from that transaction instead.\
            """)
        .isEqualTo(new TreeSet<>(AUDITING_LISTENERS.keySet()));
  }

  @Test
  @DisplayName("no listener reads the request-bound scope or security context")
  void noListenerReadsTheRequest() {
    List<JavaMethod> selected = listenerMethods(PRODUCTION);
    assertThat(selected)
        .as("listener methods (an emptied selection would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(LISTENER_FLOOR);
    assertThat(requestReadingMethods(selected))
        .as(
            """
            Listeners that read the request-bound scope or security context. A listener runs \
            after the request, on another thread or at startup; pass what it needs in the event, \
            or bind an explicit principal with AuthHelperService.runAs.\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("every observer-named interface carries the observer SPI marker")
  void everyObserverInterfaceIsMarked() {
    assertThat(unmarkedObserverInterfaces(PRODUCTION))
        .as("interfaces named *Observer without @ObserverSpi")
        .isEmpty();
  }

  @Test
  @DisplayName("every observer SPI implementation is MANDATORY")
  void everyObserverImplementationIsMandatory() {
    assertThat(observerSpis(PRODUCTION))
        .as("observer SPIs")
        .hasSizeGreaterThanOrEqualTo(OBSERVER_SPI_FLOOR);
    assertThat(nonMandatoryObserverMethods(PRODUCTION))
        .as(
            """
            Observer SPI implementations that do not join the caller's transaction. An observer \
            reacts atomically to a change of a lower module (plan §5.3); annotate the method \
            @Transactional(propagation = MANDATORY).\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: planted listeners recording audit rows are reported")
  void plantedAuditingListenersAreReported() {
    String owner = ListenerAndObserverFixtures.PlantedAuditingListener.class.getName();
    assertThat(auditingMethods(afterCommitOrAsyncMethods(FIXTURES)))
        .containsExactlyInAnyOrder(
            owner + ".recordsDirectly",
            owner + ".recordsThroughAHelper",
            owner + ".recordsInALambda");
  }

  @Test
  @DisplayName("proof: planted listeners reading the request are reported")
  void plantedRequestReadingListenersAreReported() {
    String owner = ListenerAndObserverFixtures.PlantedScopeReadingListener.class.getName();
    assertThat(requestReadingMethods(listenerMethods(FIXTURES)))
        .containsExactlyInAnyOrder(
            owner + ".readsTheActiveOrgUnit",
            owner + ".readsTheCurrentUser",
            owner + ".readsTheSecurityContext");
  }

  @Test
  @DisplayName("proof: a planted unmarked observer interface is reported")
  void aPlantedUnmarkedObserverInterfaceIsReported() {
    assertThat(unmarkedObserverInterfaces(FIXTURES))
        .containsExactly(ListenerAndObserverFixtures.PlantedUnmarkedObserver.class.getName());
  }

  @Test
  @DisplayName("proof: planted non-mandatory observer implementations are reported")
  void plantedNonMandatoryObserversAreReported() {
    assertThat(observerSpis(FIXTURES)).hasSize(1);
    assertThat(nonMandatoryObserverMethods(FIXTURES))
        .containsExactlyInAnyOrder(
            ListenerAndObserverFixtures.PlantedRequiredObserver.class.getName() + ".stockChanged",
            ListenerAndObserverFixtures.PlantedUnannotatedObserver.class.getName()
                + ".stockChanged");
  }

  private static List<JavaMethod> afterCommitOrAsyncMethods(JavaClasses classes) {
    return methodsWhere(
        classes,
        m ->
            m.isAnnotatedWith(TransactionalEventListener.class)
                || m.isAnnotatedWith(Async.class)
                || m.getOwner().isAnnotatedWith(Async.class));
  }

  private static List<JavaMethod> listenerMethods(JavaClasses classes) {
    return methodsWhere(
        classes,
        m ->
            m.isAnnotatedWith(TransactionalEventListener.class)
                || m.isAnnotatedWith(EventListener.class)
                || m.isAnnotatedWith(Async.class)
                || m.getOwner().isAnnotatedWith(Async.class));
  }

  private static List<JavaMethod> methodsWhere(
      JavaClasses classes, Predicate<JavaMethod> predicate) {
    return classes.stream()
        .flatMap(c -> c.getMethods().stream())
        .filter(predicate)
        .sorted((a, b) -> a.getFullName().compareTo(b.getFullName()))
        .toList();
  }

  private static Set<String> auditingMethods(List<JavaMethod> methods) {
    return methodsReaching(
        methods,
        target ->
            AUDIT_RECORDERS.contains(target.getOwner().getName())
                && target.getName().equals("record"));
  }

  private static Set<String> requestReadingMethods(List<JavaMethod> methods) {
    return methodsReaching(
        methods,
        target -> {
          String owner = target.getOwner().getName();
          return REQUEST_BOUND_TYPES.contains(owner)
              || (owner.equals(AuthHelperService.class.getName())
                  && !AUTH_HELPER_CONTEXT_FREE.contains(target.getName()));
        });
  }

  private static Set<String> methodsReaching(
      List<JavaMethod> methods, Predicate<AccessTarget> forbidden) {
    Set<String> reaching = new TreeSet<>();
    for (JavaMethod method : methods) {
      for (JavaCodeUnit unit : sameClassReach(method)) {
        for (JavaAccess<?> access : unit.getAccessesFromSelf()) {
          if (forbidden.test(access.getTarget())) {
            reaching.add(method.getOwner().getName() + "." + method.getName());
          }
        }
      }
    }
    return reaching;
  }

  /**
   * Returns {@code method} and every code unit of its class it reaches through calls, method
   * references and the synthetic methods of the lambdas it declares.
   *
   * @param method the entry method
   * @return the reached code units
   */
  private static Set<JavaCodeUnit> sameClassReach(@NotNull JavaMethod method) {
    JavaClass owner = method.getOwner();
    Set<JavaCodeUnit> reached = new LinkedHashSet<>();
    Deque<JavaCodeUnit> pending = new ArrayDeque<>();
    pending.add(method);
    while (!pending.isEmpty()) {
      JavaCodeUnit unit = pending.pop();
      if (!reached.add(unit)) {
        continue;
      }
      for (JavaCodeUnit candidate : owner.getCodeUnits()) {
        if (candidate.getName().startsWith("lambda$" + unit.getName() + "$")) {
          pending.add(candidate);
        }
      }
      for (JavaAccess<?> access : unit.getAccessesFromSelf()) {
        if (!access.getTargetOwner().equals(owner)) {
          continue;
        }
        for (JavaCodeUnit candidate : owner.getCodeUnits()) {
          if (candidate.getName().equals(access.getName())) {
            pending.add(candidate);
          }
        }
      }
    }
    return reached;
  }

  private static Set<String> unmarkedObserverInterfaces(JavaClasses classes) {
    Set<String> unmarked = new TreeSet<>();
    for (JavaClass c : classes) {
      if (c.isInterface()
          && c.getSimpleName().endsWith("Observer")
          && !c.isAnnotatedWith(ObserverSpi.class)) {
        unmarked.add(c.getName());
      }
    }
    return unmarked;
  }

  private static Set<String> observerSpis(JavaClasses classes) {
    Set<String> spis = new TreeSet<>();
    for (JavaClass c : classes) {
      if (c.isInterface() && c.isAnnotatedWith(ObserverSpi.class)) {
        spis.add(c.getName());
      }
    }
    return spis;
  }

  private static Set<String> nonMandatoryObserverMethods(JavaClasses classes) {
    Set<String> offenders = new TreeSet<>();
    for (JavaClass c : classes) {
      if (c.isInterface()) {
        continue;
      }
      for (JavaClass spi : c.getAllRawInterfaces()) {
        if (!spi.isAnnotatedWith(ObserverSpi.class)) {
          continue;
        }
        for (JavaMethod declared : spi.getMethods()) {
          if (declared.getModifiers().contains(JavaModifier.STATIC)) {
            continue;
          }
          Optional<JavaMethod> implementation = implementationOf(c, declared);
          if (implementation.isEmpty() || !isMandatory(implementation.get(), c)) {
            offenders.add(c.getName() + "." + declared.getName());
          }
        }
      }
    }
    return offenders;
  }

  private static Optional<JavaMethod> implementationOf(JavaClass type, JavaMethod declared) {
    return type.getAllMethods().stream()
        .filter(m -> !m.getOwner().isInterface())
        .filter(m -> m.getName().equals(declared.getName()))
        .filter(m -> m.getRawParameterTypes().equals(declared.getRawParameterTypes()))
        .findFirst();
  }

  private static boolean isMandatory(JavaMethod method, JavaClass implementor) {
    Optional<Transactional> onMethod = method.tryGetAnnotationOfType(Transactional.class);
    if (onMethod.isPresent()) {
      return onMethod.get().propagation() == Propagation.MANDATORY;
    }
    return method
        .getOwner()
        .tryGetAnnotationOfType(Transactional.class)
        .or(() -> implementor.tryGetAnnotationOfType(Transactional.class))
        .map(t -> t.propagation() == Propagation.MANDATORY)
        .orElse(false);
  }
}
