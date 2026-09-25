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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies that the {@code org.springframework.web.servlet.PageNotFound} logger is silenced while
 * its sibling web loggers are not (REQ-OBS-001).
 */
@SpringBootTest
@ActiveProfiles("test")
class PageNotFoundLogLevelTest {

  /**
   * The logger {@code DispatcherServlet#noHandlerFound} writes through. Hardcoded because Spring's
   * own constant ({@code DispatcherServlet.PAGE_NOT_FOUND_LOG_CATEGORY}) is the same literal and
   * the whole point is to pin the spelling the YAML key must match.
   */
  private static final String PAGE_NOT_FOUND_LOGGER =
      "org.springframework.web.servlet.PageNotFound";

  @MockitoBean private WebClient webClient;

  @MockitoBean(name = "termsDocumentClient")
  private WebClient termsDocumentClient;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @Test
  void theDeliberate404LoggerDoesNotWarn() {
    assertThat(LoggerFactory.getLogger(PAGE_NOT_FOUND_LOGGER).isWarnEnabled())
        .as("a 404 the application answers on purpose must not also warn (REQ-OBS-001)")
        .isFalse();
  }

  @Test
  void itIsStillReachableAtError() {
    assertThat(LoggerFactory.getLogger(PAGE_NOT_FOUND_LOGGER).isErrorEnabled()).isTrue();
  }

  @Test
  void theRestOfTheWebLoggerTreeIsUntouched() {
    assertThat(LoggerFactory.getLogger("org.springframework.web").isInfoEnabled())
        .as("the pin must sit on the leaf logger, never on its parent")
        .isTrue();
    assertThat(
            LoggerFactory.getLogger("org.springframework.web.servlet.DispatcherServlet")
                .isInfoEnabled())
        .isTrue();
  }
}
