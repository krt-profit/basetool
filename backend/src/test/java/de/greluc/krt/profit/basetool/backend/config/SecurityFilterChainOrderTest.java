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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Asserts the order of the security gates: pending approval, then terms acceptance, then the
 * per-subject rate limiter, so a refused caller is told what they can act on and never spends or
 * dodges a rate token.
 *
 * <p>Also checks that every document-rendering endpoint is covered by the limiter's export budget.
 */
@SpringBootTest
class SecurityFilterChainOrderTest {

  @Autowired private FilterChainProxy filterChainProxy;
  @Autowired private ApplicationContext applicationContext;

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

  /**
   * Every API handler returning a raw {@code byte[]} body must fall under the per-subject export
   * budget (REQ-SEC-033), judged independently of the filter's own path list; path variables are
   * replaced by a placeholder before matching.
   */
  @Test
  @DisplayName("every document-rendering endpoint spends from the export budget")
  void everyDocumentEndpointIsUnderTheExportBudget() {
    RequestMappingHandlerMapping mapping =
        applicationContext.getBean(
            "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    List<String> documentPaths = new ArrayList<>();
    mapping
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              if (!rendersDocument(handler) || info.getPathPatternsCondition() == null) {
                return;
              }
              info.getPathPatternsCondition().getPatternValues().stream()
                  .filter(pattern -> pattern.startsWith("/api/"))
                  .forEach(documentPaths::add);
            });

    assertThat(documentPaths)
        .as(
            "the sweep must actually find the document endpoints (ten on 2026-09-22), or it"
                + " asserts nothing")
        .hasSizeGreaterThanOrEqualTo(10);
    assertThat(documentPaths)
        .allSatisfy(
            pattern ->
                assertThat(
                        SubjectRateLimitingFilter.isExportPath(
                            PathContainer.parsePath(pattern.replaceAll("\\{[^}]+}", "x"))))
                    .as(pattern + " renders a document but is not under the export budget")
                    .isTrue());
  }

  /**
   * Whether a handler answers with a raw binary body.
   *
   * @param handler the mapped handler method
   * @return {@code true} for {@code byte[]} or {@code ResponseEntity<byte[]>}
   */
  private static boolean rendersDocument(HandlerMethod handler) {
    ResolvableType type = ResolvableType.forMethodReturnType(handler.getMethod());
    if (ResponseEntity.class.isAssignableFrom(type.toClass())) {
      type = type.getGeneric(0);
    }
    return byte[].class.equals(type.toClass());
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
