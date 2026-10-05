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

import de.greluc.krt.profit.basetool.backend.config.PathControlInventory.Endpoint;
import de.greluc.krt.profit.basetool.backend.platform.api.RateLimitProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves every rate-limit rule, stream path and export segment names at least one real operation,
 * so a moved path cannot silently shed its limit (REQ-SEC-079).
 */
@SpringBootTest
class RateLimitRuleCoverageTest {

  /** The number of endpoint rules configured today. */
  private static final int RULE_FLOOR = 4;

  @Autowired private WebApplicationContext context;

  @Autowired private RateLimitProperties rateLimitProperties;

  @Test
  @DisplayName("every path of every rule matches an operation answering one of its verbs")
  void everyRulePathMatchesAnOperation() {
    List<Endpoint> endpoints = PathControlInventory.dispatcher(context);
    List<String> dead = new ArrayList<>();
    for (RateLimitProperties.Rule rule : rateLimitProperties.rules()) {
      List<HttpMethod> verbs = rule.methods().stream().map(HttpMethod::valueOf).toList();
      PathControlInventory.deadPatterns(rule.paths(), verbs, endpoints)
          .forEach(path -> dead.add(rule.name() + " " + rule.methods() + " " + path));
    }

    assertThat(rateLimitProperties.rules()).hasSizeGreaterThanOrEqualTo(RULE_FLOOR);
    assertThat(dead)
        .as("re-key the rule in application.yml in the same change that moves its operation")
        .isEmpty();
  }

  @Test
  @DisplayName("the per-IP limiter's scope matches operations")
  void theGlobalScopeMatchesOperations() {
    assertThat(
            PathControlInventory.deadPatterns(
                rateLimitProperties.paths(), List.of(), PathControlInventory.dispatcher(context)))
        .isEmpty();
  }

  @Test
  @DisplayName("both stream connects the subject budget counts are real GET operations")
  void theStreamConnectsAreRealOperations() {
    assertThat(
            PathControlInventory.deadPatterns(
                List.of(
                    SubjectRateLimitingFilter.SSE_CONNECT.getPatternString(),
                    SubjectRateLimitingFilter.LIVE_SYNC_CONNECT.getPatternString()),
                List.of(HttpMethod.GET),
                PathControlInventory.dispatcher(context)))
        .isEmpty();
  }

  @Test
  @DisplayName("every export segment occurs in at least one operation")
  void everyExportSegmentIsUsed() {
    assertThat(SubjectRateLimitingFilter.EXPORT_SEGMENTS).isNotEmpty();
    assertThat(
            PathControlInventory.unusedSegments(
                SubjectRateLimitingFilter.EXPORT_SEGMENTS,
                PathControlInventory.dispatcher(context)))
        .as("an export segment no operation carries budgets nothing")
        .isEmpty();
  }
}
