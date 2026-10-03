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

package de.greluc.krt.profit.basetool.testsupport.context;

import java.lang.reflect.Method;
import org.jetbrains.annotations.NotNull;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * The bean shape of a running application context that a module move must not change silently
 * (G-24, REQ-OPS-038).
 *
 * <p>Only beans whose class lies in the given package prefix are counted, except the security
 * filter chains, which are counted whatever their class.
 *
 * @param scheduledMethods methods carrying {@code @Scheduled} on the counted beans
 * @param transactionalEventListeners methods carrying {@code @TransactionalEventListener} on the
 *     counted beans
 * @param controllers counted beans annotated {@code @Controller}, {@code @RestController} included
 * @param securityFilterChains {@code SecurityFilterChain} beans in the context
 */
public record ContextShape(
    int scheduledMethods,
    int transactionalEventListeners,
    int controllers,
    int securityFilterChains) {

  private static final String TRANSACTIONAL_EVENT_LISTENER =
      "org.springframework.transaction.event.TransactionalEventListener";

  private static final String SECURITY_FILTER_CHAIN =
      "org.springframework.security.web.SecurityFilterChain";

  /**
   * Measures a context without instantiating any bean that is not yet created.
   *
   * @param context the context, or its bean factory
   * @param packagePrefix the package whose beans are counted, e.g. {@code de.greluc.krt}
   * @return the shape
   */
  public static @NotNull ContextShape of(
      @NotNull ListableBeanFactory context, @NotNull String packagePrefix) {
    String prefix = packagePrefix + ".";
    int scheduled = 0;
    int transactional = 0;
    int controllers = 0;
    for (String name : context.getBeanDefinitionNames()) {
      if (ScopedProxyUtils.isScopedTarget(name)) {
        continue;
      }
      Class<?> type = context.getType(name, false);
      if (type == null) {
        continue;
      }
      Class<?> userClass = ClassUtils.getUserClass(type);
      if (!userClass.getName().startsWith(prefix)) {
        continue;
      }
      if (MergedAnnotations.from(userClass, SearchStrategy.TYPE_HIERARCHY)
          .isPresent(Controller.class)) {
        controllers++;
      }
      for (Method method :
          ReflectionUtils.getUniqueDeclaredMethods(
              userClass, ReflectionUtils.USER_DECLARED_METHODS)) {
        if (!AnnotatedElementUtils.findMergedRepeatableAnnotations(method, Scheduled.class)
            .isEmpty()) {
          scheduled++;
        }
        if (MergedAnnotations.from(method, SearchStrategy.TYPE_HIERARCHY)
            .isPresent(TRANSACTIONAL_EVENT_LISTENER)) {
          transactional++;
        }
      }
    }
    return new ContextShape(scheduled, transactional, controllers, securityFilterChains(context));
  }

  /**
   * Counts the {@code SecurityFilterChain} beans, or none when Spring Security is not on the
   * classpath.
   *
   * @param context the context
   * @return the number of filter chain beans
   */
  private static int securityFilterChains(@NotNull ListableBeanFactory context) {
    ClassLoader loader = ContextShape.class.getClassLoader();
    if (!ClassUtils.isPresent(SECURITY_FILTER_CHAIN, loader)) {
      return 0;
    }
    return context.getBeanNamesForType(
            ClassUtils.resolveClassName(SECURITY_FILTER_CHAIN, loader), true, false)
        .length;
  }
}
