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

package de.greluc.krt.profit.basetool.frontend.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Proves, through the real advice in a standalone {@link MockMvc}, that a backend refusal reaches a
 * caller of every kind with the backend's HTTP status: the error page for a navigation or a plain
 * {@code fetch}, and the JSON body for an XHR caller.
 */
class BackendErrorPageStatusMvcTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();
  }

  /** A navigation (no JSON accept, no XHR header) gets the error page with the backend status. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
  void aNavigationGetsTheErrorPageWithTheBackendStatus(int backendStatus) throws Exception {
    mockMvc
        .perform(get("/fail/" + backendStatus).header("Accept", "text/html"))
        .andExpect(status().is(backendStatus))
        .andExpect(view().name("error/error"))
        .andExpect(model().attribute("status", String.valueOf(backendStatus)));
  }

  /** A {@code fetch} asking for a PDF reads the status; it must not see a 200 error page. */
  @Test
  void aBinaryFetchSeesTheFailureAsAFailure() throws Exception {
    mockMvc
        .perform(get("/fail/404").header("Accept", "application/pdf"))
        .andExpect(status().isNotFound())
        .andExpect(view().name("error/error"));
  }

  /** An XHR caller still gets the JSON body with the same status. */
  @Test
  void anXhrCallerStillGetsTheJsonBody() throws Exception {
    mockMvc
        .perform(get("/fail/409").header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  /** A status HTTP has no name for falls back to 500, page and JSON alike. */
  @Test
  void anUnknownStatusFallsBackTo500() throws Exception {
    mockMvc.perform(get("/fail/599")).andExpect(status().isInternalServerError());
  }

  /**
   * Throws the {@link BackendServiceException} a backend answer of the requested status maps to.
   */
  @Controller
  static class FailingController {

    /**
     * Fails like a backend call that answered {@code status}.
     *
     * @param status the backend status to fail with
     * @return never
     */
    @GetMapping("/fail/{status}")
    String fail(@PathVariable int status) {
      throw new BackendServiceException(
          "backend answered " + status,
          null,
          status,
          status == 409 ? "CONFLICT" : "UNKNOWN",
          null,
          List.of(),
          null);
    }
  }
}
