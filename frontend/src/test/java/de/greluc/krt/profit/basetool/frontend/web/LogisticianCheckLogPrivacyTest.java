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

package de.greluc.krt.profit.basetool.frontend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.joborder.web.JobOrderPageController;
import de.greluc.krt.profit.basetool.frontend.joborder.web.JobOrderWriteController;
import de.greluc.krt.profit.basetool.frontend.refinery.web.RefineryOrderPageController;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Guards REQ-OBS-004 for the three {@code isLogistician} helpers: no log line they emit carries the
 * principal name or a hash derived from it.
 */
class LogisticianCheckLogPrivacyTest {

  private static final String PRINCIPAL_NAME = "alice.example-pilot";

  static Stream<Arguments> controllers() {
    return Stream.of(
        Arguments.of(JobOrderPageController.class),
        Arguments.of(JobOrderWriteController.class),
        Arguments.of(RefineryOrderPageController.class));
  }

  /**
   * Sets the controller's typed backend client to a real client over the mocked kernel client.
   *
   * @param controller the controller under test
   * @param type its class
   * @param api the mocked kernel client
   * @throws ReflectiveOperationException when the client cannot be built
   */
  private static void injectTypedClient(Object controller, Class<?> type, BackendApiClient api)
      throws ReflectiveOperationException {
    for (java.lang.reflect.Field field : type.getDeclaredFields()) {
      if (field.getType().getSimpleName().endsWith("BackendClient")) {
        Object client = field.getType().getConstructor(BackendApiClient.class).newInstance(api);
        ReflectionTestUtils.setField(controller, field.getName(), client);
        return;
      }
    }
    throw new IllegalStateException(type.getSimpleName() + " has no typed backend client");
  }

  @ParameterizedTest
  @MethodSource("controllers")
  void isLogistician_neverLogsPrincipalNameOrItsHash(Class<?> type) throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(type);
    Level original = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      Object controller = mock(type, withSettings().defaultAnswer(CALLS_REAL_METHODS));
      BackendApiClient api = mock(BackendApiClient.class);
      UserDto me = mock(UserDto.class);
      when(me.isLogistician()).thenReturn(Boolean.TRUE);
      when(api.get(any(String.class), any(Class.class))).thenReturn(me);
      RoleHierarchy hierarchy = mock(RoleHierarchy.class);
      when(hierarchy.getReachableGrantedAuthorities(any()))
          .thenAnswer(inv -> List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_X")));
      injectTypedClient(controller, type, api);
      ReflectionTestUtils.setField(controller, "roleHierarchy", hierarchy);
      OidcUser principal = mock(OidcUser.class);
      when(principal.getName()).thenReturn(PRINCIPAL_NAME);
      when(principal.getAuthorities()).thenAnswer(inv -> List.of());

      ReflectionTestUtils.invokeMethod(controller, "isLogistician", principal);

      String hash = Integer.toHexString(Objects.hashCode(PRINCIPAL_NAME));
      assertThat(appender.list).isNotEmpty();
      assertThat(appender.list)
          .allSatisfy(
              e -> {
                assertThat(e.getFormattedMessage()).doesNotContain(PRINCIPAL_NAME);
                assertThat(e.getFormattedMessage()).doesNotContain(hash);
                assertThat(e.getFormattedMessage()).doesNotContain("u-");
              });
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(original);
    }
  }
}
