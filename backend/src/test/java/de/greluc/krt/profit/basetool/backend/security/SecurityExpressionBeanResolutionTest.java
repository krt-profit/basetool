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

import de.greluc.krt.profit.basetool.backend.security.SecurityExpressionAnalyzer.BeanCall;
import de.greluc.krt.profit.basetool.backend.security.SecurityExpressionSources.Declared;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * Resolves every {@code @bean.method(args)} of every backend security expression against the
 * running application context by bean name, method name and arity (REQ-SEC-075, plan guard G-04).
 *
 * <p>An unresolvable reference fails at evaluation time as HTTP 400 on every call of the gated
 * operation; this test fails the build instead.
 */
@SpringBootTest
class SecurityExpressionBeanResolutionTest {

  /** Bean references on 2026-10-02; fewer means the scan lost its subject. */
  private static final int REFERENCE_FLOOR = 166;

  @Autowired private ApplicationContext applicationContext;

  @Test
  @DisplayName("every bean reference resolves to a bean method of that arity")
  void everyBeanReferenceResolves() {
    List<BeanCall> calls = new ArrayList<>();
    for (Declared entry : SecurityExpressionSources.declared()) {
      calls.addAll(
          SecurityExpressionAnalyzer.analyze(entry.expression(), entry.origin()).beanCalls());
    }
    assertThat(calls)
        .as("the scan must find the bean references, or it resolves nothing")
        .hasSizeGreaterThanOrEqualTo(REFERENCE_FLOOR);

    assertThat(SecurityExpressionAnalyzer.unresolved(calls, this::beanType))
        .as("security expressions naming a bean or method the context does not have")
        .isEmpty();
  }

  /**
   * Looks a bean up by name in the running context.
   *
   * @param name the bean name
   * @return the bean's user class, or {@code null} when the context has no such bean
   */
  private @Nullable Class<?> beanType(String name) {
    if (!applicationContext.containsBean(name)) {
      return null;
    }
    Class<?> type = applicationContext.getType(name);
    return type == null ? null : ClassUtils.getUserClass(type);
  }
}
