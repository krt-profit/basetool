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

package de.greluc.krt.profit.basetool.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies that {@link ProblemInstanceAdvice} fills only an empty problem {@code instance}. */
class ProblemInstanceAdviceTest {

  private static final String PATH = "/exchange/v1/me/ships";

  private final ProblemInstanceAdvice advice = new ProblemInstanceAdvice();

  /**
   * Runs the advice for a body written in answer to {@code GET} {@link #PATH}.
   *
   * @param body the body the handler returned
   * @return the body the advice hands on
   */
  private Object write(Object body) {
    return advice.beforeBodyWrite(
        body,
        null,
        MediaType.APPLICATION_PROBLEM_JSON,
        JacksonJsonHttpMessageConverter.class,
        new ServletServerHttpRequest(new MockHttpServletRequest("GET", PATH)),
        new ServletServerHttpResponse(new MockHttpServletResponse()));
  }

  @Test
  void aProblemWithoutInstanceGetsTheRequestPath() {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);

    write(problem);

    assertThat(problem.getInstance()).isEqualTo(URI.create(PATH));
  }

  @Test
  void anInstanceTheHandlerSetIsKept() {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setInstance(URI.create("/exchange/v1/me/stock/changes/42"));

    write(problem);

    assertThat(problem.getInstance()).isEqualTo(URI.create("/exchange/v1/me/stock/changes/42"));
  }

  @Test
  void anyOtherBodyPassesUnchanged() {
    String body = "{\"ships\":[]}";

    assertThat(write(body)).isSameAs(body);
  }
}
