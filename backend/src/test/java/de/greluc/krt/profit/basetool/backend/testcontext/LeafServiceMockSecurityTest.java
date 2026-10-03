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

package de.greluc.krt.profit.basetool.backend.testcontext;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.prepost.PreFilter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Keeps the security beans real in the {@link LeafServiceMockTest} context: no filter chain,
 * filter, token converter, argument resolver, interceptor or bean a method-security expression
 * names may reach one of its mocks, directly or through other beans (REQ-OPS-041).
 */
@LeafServiceMockTest
class LeafServiceMockSecurityTest {

  private static final String APPLICATION_PACKAGE = "de.greluc.krt.profit.basetool.backend.";

  private static final Pattern BEAN_REFERENCE = Pattern.compile("@([A-Za-z_][A-Za-z0-9_]*)");

  private static final List<Class<?>> SECURITY_TYPES =
      List.of(
          SecurityFilterChain.class,
          Filter.class,
          JwtDecoder.class,
          Converter.class,
          HandlerMethodArgumentResolver.class,
          HandlerInterceptor.class,
          WebMvcConfigurer.class,
          PermissionEvaluator.class);

  private static final List<Class<? extends java.lang.annotation.Annotation>> METHOD_SECURITY =
      List.of(PreAuthorize.class, PostAuthorize.class, PreFilter.class, PostFilter.class);

  @Autowired private ConfigurableApplicationContext context;

  @Test
  void noSecurityBeanReachesAnAgreedMock() {
    ConfigurableListableBeanFactory beans = context.getBeanFactory();
    Set<String> mocks = mockedBeans(beans);
    Set<String> expressionBeans = methodSecurityBeans(beans);
    Set<String> roots = new TreeSet<>(expressionBeans);
    for (Class<?> type : SECURITY_TYPES) {
      roots.addAll(Arrays.asList(beans.getBeanNamesForType(type, true, false)));
    }

    assertThat(mocks).as("the mocks @LeafServiceMockTest declares").hasSizeGreaterThanOrEqualTo(8);
    assertThat(expressionBeans)
        .as("the beans method-security expressions name")
        .hasSizeGreaterThanOrEqualTo(8);
    assertThat(roots).as("the security roots").hasSizeGreaterThanOrEqualTo(36);
    assertThat(
            BeanReach.reachedTargets(
                bean -> List.of(beans.getDependenciesForBean(bean)), roots, mocks))
        .as(
            "a @LeafServiceMockTest mock is reached by a security bean, so a shared context would"
                + " run security with a mock in it. Take it out of the set and mock it in the"
                + " test that needs it")
        .isEmpty();
  }

  /**
   * Names the beans the {@link LeafServiceMockTest} set replaces with mocks.
   *
   * @param beans the bean factory
   * @return one bean name per mocked type
   */
  private static Set<String> mockedBeans(ConfigurableListableBeanFactory beans) {
    Set<String> names = new TreeSet<>();
    for (Class<?> type :
        MergedAnnotations.from(LeafServiceMockTest.class)
            .get(MockitoBean.class)
            .getClassArray("types")) {
      String[] candidates = beans.getBeanNamesForType(type, true, false);
      assertThat(candidates).as("exactly one bean of " + type.getName()).hasSize(1);
      names.add(candidates[0]);
    }
    return names;
  }

  /**
   * Collects the beans that a {@code @PreAuthorize}, {@code @PostAuthorize}, {@code @PreFilter} or
   * {@code @PostFilter} expression on an application bean names with {@code @beanName}.
   *
   * @param beans the bean factory
   * @return the referenced bean names that exist
   */
  private static Set<String> methodSecurityBeans(ConfigurableListableBeanFactory beans) {
    Set<String> names = new TreeSet<>();
    for (String name : beans.getBeanDefinitionNames()) {
      Class<?> type = beans.getType(name, false);
      if (type == null) {
        continue;
      }
      Class<?> user = ClassUtils.getUserClass(type);
      if (!user.getName().startsWith(APPLICATION_PACKAGE)) {
        continue;
      }
      List<String> expressions = new ArrayList<>(expressions(MergedAnnotations.from(user)));
      for (Method method : ReflectionUtils.getUniqueDeclaredMethods(user)) {
        expressions.addAll(
            expressions(MergedAnnotations.from(method, SearchStrategy.TYPE_HIERARCHY)));
      }
      for (String expression : expressions) {
        Matcher matcher = BEAN_REFERENCE.matcher(expression);
        while (matcher.find()) {
          if (beans.containsBean(matcher.group(1))) {
            names.add(matcher.group(1));
          }
        }
      }
    }
    return names;
  }

  /**
   * Reads the expressions of the method-security annotations present.
   *
   * @param annotations the annotations of one class or method
   * @return their {@code value} attributes
   */
  private static List<String> expressions(MergedAnnotations annotations) {
    List<String> values = new ArrayList<>();
    for (Class<? extends java.lang.annotation.Annotation> type : METHOD_SECURITY) {
      MergedAnnotation<?> annotation = annotations.get(type);
      if (annotation.isPresent()) {
        values.add(annotation.getString("value"));
      }
    }
    return values;
  }
}
