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

package de.greluc.krt.profit.basetool.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Compares the live authorization matrix with the committed golden file {@code
 * src/test/resources/api/authorization-matrix.txt} (REQ-SEC-074, plan guard G-02).
 *
 * <p>Every handler mapping appears with its effective {@code @PreAuthorize} and the URL rule of
 * {@code SecurityConfig} that decides it, read from the running {@link FilterChainProxy}; every
 * service-level gate appears in a second section. A diff means a gate changed: review it, then
 * regenerate the file with {@code ./gradlew :backend:test --tests '*AuthorizationMatrixTest'
 * -Dauthz.matrix.update=true} and commit it with the change that caused it.
 */
@Slf4j
@SpringBootTest
class AuthorizationMatrixTest {

  /** The golden file, relative to the backend module directory Gradle runs tests in. */
  private static final Path MATRIX = Path.of("src/test/resources/api/authorization-matrix.txt");

  /** The system property that rewrites the golden file instead of comparing against it. */
  private static final String UPDATE_PROPERTY = "authz.matrix.update";

  /** Handler mappings on 2026-10-02; fewer means the selection lost operations. */
  private static final int OPERATION_FLOOR = 580;

  /** Service-level gates on 2026-10-02; fewer means the selection lost gates. */
  private static final int SERVICE_GATE_FLOOR = 18;

  @Autowired private ApplicationContext applicationContext;
  @Autowired private FilterChainProxy filterChainProxy;

  @Test
  @DisplayName("the authorization matrix equals the committed golden file")
  void matrixMatchesTheGoldenFile() throws IOException {
    RequestMappingHandlerMapping handlerMapping =
        applicationContext.getBean(
            "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    List<AuthorizationMatrix.Operation> operations =
        AuthorizationMatrix.operations(
            handlerMapping.getHandlerMethods(), filterChainProxy.getFilterChains());
    List<String> serviceGates = AuthorizationMatrix.serviceGates(applicationTypes());

    assertThat(operations)
        .as("the matrix must select every handler mapping, or it pins nothing")
        .hasSizeGreaterThanOrEqualTo(OPERATION_FLOOR);
    assertThat(serviceGates)
        .as("the matrix must select every service-level gate, or it pins nothing")
        .hasSizeGreaterThanOrEqualTo(SERVICE_GATE_FLOOR);

    String actual = AuthorizationMatrix.render(operations, serviceGates);
    if (Boolean.getBoolean(UPDATE_PROPERTY)) {
      Files.writeString(MATRIX, actual, StandardCharsets.UTF_8);
      log.info("Authorization matrix rewritten at {}", MATRIX.toAbsolutePath());
      return;
    }
    String expected = Files.exists(MATRIX) ? Files.readString(MATRIX, StandardCharsets.UTF_8) : "";
    List<String> differences = AuthorizationMatrix.differences(expected, actual);
    assertThat(differences)
        .as(
            "the authorization matrix changed. Review every line below; if each change is"
                + " intended, rerun with -D"
                + UPDATE_PROPERTY
                + "=true and commit "
                + MATRIX)
        .isEmpty();
  }

  /**
   * Collects the user classes of every application bean.
   *
   * @return the distinct bean classes in the application's base package
   */
  private List<Class<?>> applicationTypes() {
    String basePackage = "de.greluc.krt.profit.basetool.backend.";
    List<Class<?>> types = new ArrayList<>();
    for (String name : applicationContext.getBeanDefinitionNames()) {
      Class<?> type = applicationContext.getType(name);
      if (type == null) {
        continue;
      }
      Class<?> user = ClassUtils.getUserClass(type);
      if (user.getName().startsWith(basePackage) && !types.contains(user)) {
        types.add(user);
      }
    }
    return types;
  }
}
