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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.greluc.krt.profit.basetool.backend.service.MissionFinanceEntryService;
import de.greluc.krt.profit.basetool.backend.service.PromotionCategoryService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * No class calls one of its own method-security-gated methods, because a call through {@code this}
 * skips the Spring proxy and with it the check (REQ-SEC-076, plan guard G-25).
 *
 * <p>A method is gated when it carries {@code @PreAuthorize} or {@code @PostAuthorize}, or when it
 * is a public instance method of a class that carries one. Calls from lambdas inside the class
 * count as calls of the class.
 */
class GatedSelfInvocationTest {

  /** The backend's main classes. */
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend");

  /** Gated methods on 2026-10-02; fewer means the rule lost its subject. */
  private static final int GATED_METHOD_FLOOR = 593;

  /** Method-level service gates on 2026-10-02. */
  private static final int SERVICE_GATE_FLOOR = 18;

  /** The rule: no call from a class to a gated method of that class. */
  private static final ArchRule NO_GATED_SELF_INVOCATION =
      classes()
          .that()
          .resideInAPackage("de.greluc.krt.profit.basetool.backend..")
          .should(notCallTheirOwnGatedMethods())
          .because(
              "a call through this bypasses the method-security proxy, so the gate never runs"
                  + " (REQ-SEC-076)");

  /** A fixture that calls its own gated method directly and from a lambda. */
  static class SelfInvokingFixture {

    /** A gated method. */
    @PreAuthorize("hasRole('ADMIN')")
    public void gated() {}

    /** Calls the gated method through {@code this}. */
    public void direct() {
      gated();
    }

    /**
     * Calls the gated method from a lambda.
     *
     * @return a supplier that calls it
     */
    public Supplier<String> deferred() {
      return () -> {
        gated();
        return "done";
      };
    }
  }

  /** A fixture whose class-level gate covers a public method it calls itself. */
  @PreAuthorize("hasRole('ADMIN')")
  static class ClassGatedFixture {

    /** Gated by the class annotation. */
    public void covered() {}

    /** Calls the class-gated method through {@code this}. */
    public void caller() {
      covered();
    }
  }

  /** A fixture that calls only an ungated helper of its own. */
  static class CleanFixture {

    /** A gated entry point. */
    @PreAuthorize("hasRole('ADMIN')")
    public void gated() {
      helper();
    }

    /** An ungated helper. */
    void helper() {}
  }

  @Test
  @DisplayName("no backend class calls one of its own gated methods")
  void noClassCallsItsOwnGatedMethods() {
    long gated =
        CLASSES.stream().flatMap(c -> c.getMethods().stream()).filter(m -> isGated(m)).count();
    assertThat(gated)
        .as("the rule must see every gated method, or it pins nothing")
        .isGreaterThanOrEqualTo(GATED_METHOD_FLOOR);
    long serviceGates =
        CLASSES.stream()
            .filter(c -> c.getSimpleName().endsWith("Service"))
            .flatMap(c -> c.getMethods().stream())
            .filter(
                m ->
                    m.isAnnotatedWith(PreAuthorize.class) || m.isAnnotatedWith(PostAuthorize.class))
            .count();
    assertThat(serviceGates)
        .as("the service-level gates this rule protects")
        .isGreaterThanOrEqualTo(SERVICE_GATE_FLOOR);

    NO_GATED_SELF_INVOCATION.check(CLASSES);
  }

  @Test
  @DisplayName("the rule fails on a direct, a lambda and a class-gated self-invocation")
  void ruleFailsOnPlantedSelfInvocations() {
    EvaluationResult selfInvoking =
        NO_GATED_SELF_INVOCATION.evaluate(
            new ClassFileImporter().importClasses(SelfInvokingFixture.class));
    assertThat(selfInvoking.hasViolation()).isTrue();
    assertThat(selfInvoking.getFailureReport().getDetails()).hasSize(2);

    assertThat(
            NO_GATED_SELF_INVOCATION
                .evaluate(new ClassFileImporter().importClasses(ClassGatedFixture.class))
                .hasViolation())
        .isTrue();
    assertThat(
            NO_GATED_SELF_INVOCATION
                .evaluate(new ClassFileImporter().importClasses(CleanFixture.class))
                .hasViolation())
        .isFalse();
  }

  @Test
  @DisplayName("the finance-entry and promotion-category writes keep their service gates")
  void theOnlyRealServiceGatesStay() throws NoSuchMethodException {
    String financeGate = "@missionSecurityService.canEditFinanceEntry(#entryId, authentication)";
    assertThat(gate(MissionFinanceEntryService.class, "updateEntry")).isEqualTo(financeGate);
    assertThat(gate(MissionFinanceEntryService.class, "deleteEntry")).isEqualTo(financeGate);
    for (String write : new String[] {"create", "update", "delete"}) {
      assertThat(gate(PromotionCategoryService.class, write)).isEqualTo(Roles.ADMIN_OR_OFFICER);
    }
  }

  /**
   * Reads the {@code @PreAuthorize} of the one public method of that name.
   *
   * @param type the service class
   * @param name the method name
   * @return the expression, or {@code null} when the method carries none
   * @throws NoSuchMethodException when the class has no public method of that name
   */
  private static @Nullable String gate(Class<?> type, String name) throws NoSuchMethodException {
    Method method =
        Arrays.stream(type.getMethods())
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new NoSuchMethodException(type.getSimpleName() + "#" + name));
    PreAuthorize pre = method.getAnnotation(PreAuthorize.class);
    return pre == null ? null : pre.value();
  }

  /**
   * Whether a method is gated: annotated itself, or a public instance method of an annotated class.
   *
   * @param method the method
   * @return {@code true} when a call through the proxy would run a security check
   */
  static boolean isGated(JavaMethod method) {
    if (method.isAnnotatedWith(PreAuthorize.class) || method.isAnnotatedWith(PostAuthorize.class)) {
      return true;
    }
    JavaClass owner = method.getOwner();
    return (owner.isAnnotatedWith(PreAuthorize.class) || owner.isAnnotatedWith(PostAuthorize.class))
        && method.getModifiers().contains(JavaModifier.PUBLIC)
        && !method.getModifiers().contains(JavaModifier.STATIC);
  }

  /**
   * The condition: none of a class's own calls targets a gated method of that class.
   *
   * @return the condition
   */
  private static ArchCondition<JavaClass> notCallTheirOwnGatedMethods() {
    return new ArchCondition<>("not call a method-security-gated method of their own class") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
          if (!javaClass.isAssignableTo(call.getTargetOwner().getName())) {
            continue;
          }
          call.getTarget()
              .resolveMember()
              .filter(GatedSelfInvocationTest::isGated)
              .ifPresent(
                  target ->
                      events.add(
                          SimpleConditionEvent.violated(
                              call,
                              call.getDescription()
                                  + " calls the gated "
                                  + target.getFullName()
                                  + " of its own class, bypassing the proxy")));
        }
      }
    };
  }
}
