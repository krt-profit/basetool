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

package de.greluc.krt.profit.basetool.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.security.SecurityExpressionAnalyzer.Analysis;
import de.greluc.krt.profit.basetool.backend.security.SecurityExpressionAnalyzer.BeanCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves that the SpEL guards of {@link SecurityExpressionRulesTest} and {@link
 * SecurityExpressionBeanResolutionTest} can fail: a missing bean, a wrong arity and every forbidden
 * construct are reported (REQ-SEC-075).
 */
class SecurityExpressionAnalyzerTest {

  /** A fixture security bean. */
  static class FixtureGate {

    /**
     * A one-argument check.
     *
     * @param id the checked id
     * @return whether an id was given
     */
    public boolean allows(Object id) {
      return id != null;
    }

    /**
     * A varargs check.
     *
     * @param first the first checked value
     * @param rest further checked values
     * @return whether any value was given
     */
    public boolean any(Object first, Object... rest) {
      return first != null || rest.length > 0;
    }
  }

  /** The fixture context: one bean named {@code fixtureGate}. */
  private static final Map<String, Class<?>> BEANS = Map.of("fixtureGate", FixtureGate.class);

  @Test
  @DisplayName("a valid expression yields its bean calls and no violation")
  void validExpressionPasses() {
    Analysis analysis =
        SecurityExpressionAnalyzer.analyze(
            "isAuthenticated() and (hasRole('ADMIN') or @fixtureGate.allows(#dto.id()))"
                + " and !@fixtureGate.any(#a, #b, authentication)",
            "Fixture#ok");

    assertThat(analysis.violations()).isEmpty();
    assertThat(analysis.beanCalls())
        .containsExactly(
            new BeanCall("fixtureGate", "allows", 1, "Fixture#ok"),
            new BeanCall("fixtureGate", "any", 3, "Fixture#ok"));
    assertThat(SecurityExpressionAnalyzer.unresolved(analysis.beanCalls(), BEANS::get)).isEmpty();
  }

  @Test
  @DisplayName("a reference to a missing bean is unresolved")
  void missingBeanFails() {
    List<BeanCall> calls =
        SecurityExpressionAnalyzer.analyze("@missingGate.allows(#id)", "Fixture#missing")
            .beanCalls();

    assertThat(SecurityExpressionAnalyzer.unresolved(calls, BEANS::get))
        .containsExactly("Fixture#missing: no bean named 'missingGate'");
  }

  @Test
  @DisplayName("a wrong arity and a missing method are unresolved")
  void wrongArityFails() {
    List<BeanCall> calls =
        SecurityExpressionAnalyzer.analyze(
                "@fixtureGate.allows(#id, authentication) or @fixtureGate.denies(#id)",
                "Fixture#arity")
            .beanCalls();

    assertThat(SecurityExpressionAnalyzer.unresolved(calls, BEANS::get))
        .containsExactly(
            "Fixture#arity: @fixtureGate.allows with 2 argument(s) has no overload of that arity"
                + " on FixtureGate",
            "Fixture#arity: @fixtureGate.denies with 1 argument(s) does not exist on FixtureGate");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "T(java.lang.Runtime).getRuntime() != null",
        "hasRole('AD' + 'MIN')",
        "@fixtureGate.allows(@fixtureGate.allows(#id))",
        "@fixtureGate.allows(#id).booleanValue()",
        "@fixtureGate",
        "@fixtureGate.allows(#dto.lookup(#id))",
        "@fixtureGate?.allows(#id)",
        "new java.lang.Object() != null",
        "#id = 'x'",
        "#fn()",
        "{1, 2}.contains(#id)",
        "#ids[0] == 'x'",
        "#name matches '.*'",
        "#name ?: 'x'",
        "hasRole("
      })
  @DisplayName("every forbidden construct is a violation")
  void forbiddenConstructsAreViolations(String expression) {
    assertThat(SecurityExpressionAnalyzer.analyze(expression, "Fixture#bad").violations())
        .isNotEmpty();
  }
}
