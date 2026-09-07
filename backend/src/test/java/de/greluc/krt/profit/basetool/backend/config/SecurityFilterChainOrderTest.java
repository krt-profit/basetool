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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;

/**
 * The order of the gates, which is a decision and not an accident.
 *
 * <p><strong>Why this test exists at all.</strong> {@code SubjectRateLimitingFilter} used to be
 * anchored on {@code AnonymousPageSizeFilter} rather than on the terms filter, and the comment
 * beside it said why: {@code addFilterAfter} inserts directly after its anchor, so <em>two</em>
 * calls naming one anchor end up in reverse registration order. Naming the page-size filter was
 * what stated this filter's position instead of leaving it to the order the calls happen to appear
 * in. ADR-0159 deleted that filter — there is no unauthenticated caller left on a paginated path to
 * bound — and the limiter moved onto {@code TermsAcceptanceAccessFilter}, which today has exactly
 * one filter after it.
 *
 * <p>"Exactly one today" is a property of the current registrations, not of the code, and the next
 * filter registered on that anchor would silently swap the two. So the order is asserted here
 * rather than argued for in a comment: {@code ApiClientMetricsChainTest} pins the
 * bearer/metrics/acting-member edge at the top of the chain, and this pins the gate sequence below
 * it.
 *
 * <p>The sequence itself is load-bearing in both directions. Pending-approval before terms, so a
 * member who is both pending and unconsented is told the thing they can act on. Both before the
 * per-subject limiter, so a refused caller is turned away on its own terms rather than spending a
 * token first — and, symmetrically, so a client cannot hide from the rate counter behind its own
 * 403s.
 */
@SpringBootTest
class SecurityFilterChainOrderTest {

  @Autowired private FilterChainProxy filterChainProxy;

  /**
   * Returns the simple class names of the API chain's filters, in order.
   *
   * @return the filter names as the chain executes them
   */
  private List<String> filterNames() {
    return filterChainProxy.getFilterChains().stream()
        .flatMap(chain -> chain.getFilters().stream())
        .map(Filter::getClass)
        .map(Class::getSimpleName)
        .toList();
  }

  @Test
  @DisplayName("the three gates run in the order their rationale assumes")
  void gatesRunInTheDocumentedOrder() {
    List<String> names = filterNames();

    int pending = names.indexOf("PendingApprovalAccessFilter");
    int terms = names.indexOf("TermsAcceptanceAccessFilter");
    int subjectLimit = names.indexOf("SubjectRateLimitingFilter");

    assertThat(pending)
        .as("PendingApprovalAccessFilter must be registered in the chain")
        .isGreaterThanOrEqualTo(0);
    assertThat(terms)
        .as("TermsAcceptanceAccessFilter must be registered in the chain")
        .isGreaterThanOrEqualTo(0);
    assertThat(subjectLimit)
        .as("SubjectRateLimitingFilter must be registered in the chain")
        .isGreaterThanOrEqualTo(0);

    assertThat(pending)
        .as(
            "pending-approval runs before the terms gate, so a member who is both pending and"
                + " unconsented is told the one they can act on")
        .isLessThan(terms);
    assertThat(terms)
        .as(
            "the per-subject limiter runs after both refusing gates (ADR-0159 re-anchored it from"
                + " the deleted AnonymousPageSizeFilter onto TermsAcceptanceAccessFilter); two"
                + " addFilterAfter calls on one anchor end up reversed, so this is asserted rather"
                + " than assumed")
        .isLessThan(subjectLimit);
  }

  @Test
  @DisplayName("the anonymous page-size filter is gone, not merely unused")
  void theAnonymousPageSizeFilterIsNotInTheChain() {
    assertThat(filterNames())
        .as(
            "ADR-0159 removed AnonymousPageSizeFilter with the callers it bounded. A filter left"
                + " registered but never triggered is the kind of thing a later reader restores a"
                + " dependency on.")
        .doesNotContain("AnonymousPageSizeFilter");
  }
}
