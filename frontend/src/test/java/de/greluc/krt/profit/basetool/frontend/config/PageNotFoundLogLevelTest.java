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
 * The deliberate-404 logger stays silent, and its siblings do not (REQ-OBS-001).
 *
 * <p><strong>Why a test for a configuration line.</strong> A {@code logging.level} entry asserts
 * nothing on its own: a typo in the logger name, a stray {@code org.springframework.web} pin that
 * swallows the whole tree, or a later edit that "tidies" the entry away all leave a green build and
 * a silently changed log stream. The last of those is the realistic one — the entry looks like dead
 * configuration to anyone who does not know what writes through that logger.
 *
 * <p>What it is guarding: Spring's {@code DispatcherServlet} writes "No mapping for GET
 * /favicon.ico" through {@code org.springframework.web.servlet.PageNotFound} at {@code WARN},
 * <em>unconditionally</em> — before {@code throwExceptionIfNoHandlerFound} is consulted, so before
 * {@code GlobalExceptionHandler.handleNotFound} renders the 404 page this application is designed
 * to render for that path. Ten such lines landed in the September production log for a 404 that is
 * the documented, intended answer ({@code WebMvcConfig#addResourceHandlers}).
 *
 * <p>The negative half is the one that would catch the over-broad fix: pinning {@code
 * org.springframework.web} instead of the leaf would also silence every other web logger, and that
 * is exactly the sort of change that reads as equivalent.
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
    // ERROR rather than OFF, so the logger can still say something if Spring ever writes a genuine
    // failure through it, and so /actuator/loggers still shows a level rather than a disabled
    // logger.
    assertThat(LoggerFactory.getLogger(PAGE_NOT_FOUND_LOGGER).isErrorEnabled()).isTrue();
  }

  @Test
  void theRestOfTheWebLoggerTreeIsUntouched() {
    // The over-broad fix, pinned out: silencing org.springframework.web would also silence this,
    // and
    // a build that stayed green while the whole web tree went quiet is precisely the failure this
    // class exists to prevent.
    assertThat(LoggerFactory.getLogger("org.springframework.web").isInfoEnabled())
        .as("the pin must sit on the leaf logger, never on its parent")
        .isTrue();
    assertThat(
            LoggerFactory.getLogger("org.springframework.web.servlet.DispatcherServlet")
                .isInfoEnabled())
        .isTrue();
  }
}
