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

package de.greluc.krt.profit.basetool.backend.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring MVC's client-error exceptions reach {@link GlobalExceptionHandler} as the 4xx they are,
 * each with a registered code, a localized title and a correlation id, instead of the catch-all
 * {@code 500 INTERNAL_ERROR} (REQ-API-004, REQ-API-019).
 */
class GlobalExceptionHandlerClientErrorTest {

  private MockMvc mockMvc;

  /** Builds MockMvc over the probe controller with the real handler and the real bundles. */
  @BeforeEach
  void setUp() {
    AppProblemProperties problemProperties =
        new AppProblemProperties("https://profit-base.online/problems/");
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(
                new GlobalExceptionHandler(
                    problemProperties,
                    new ProblemResponseFactory(problemProperties),
                    messageSource,
                    new SimpleMeterRegistry()))
            .build();
  }

  @Test
  @DisplayName("a missing request parameter is 400 BAD_REQUEST")
  void aMissingRequestParameterIsBadRequest() throws Exception {
    JsonNode body = problem(get("/param"), HttpStatus.BAD_REQUEST);

    assertThat(body.path("code").asString()).isEqualTo(CoreProblemCode.BAD_REQUEST.code());
    assertThat(body.path("detail").asString()).contains("topics");
  }

  @Test
  @DisplayName("a missing request header is 400 BAD_REQUEST")
  void aMissingRequestHeaderIsBadRequest() throws Exception {
    JsonNode body = problem(get("/header"), HttpStatus.BAD_REQUEST);

    assertThat(body.path("code").asString()).isEqualTo(CoreProblemCode.BAD_REQUEST.code());
    assertThat(body.path("detail").asString()).contains("X-Probe");
  }

  @Test
  @DisplayName("a missing cookie is 400 BAD_REQUEST")
  void aMissingCookieIsBadRequest() throws Exception {
    JsonNode body = problem(get("/cookie"), HttpStatus.BAD_REQUEST);

    assertThat(body.path("code").asString()).isEqualTo(CoreProblemCode.BAD_REQUEST.code());
  }

  @Test
  @DisplayName("a request the mapping's parameter condition refuses is 400 BAD_REQUEST")
  void anUnsatisfiedParameterConditionIsBadRequest() throws Exception {
    JsonNode body = problem(get("/conditional").param("mode", "loose"), HttpStatus.BAD_REQUEST);

    assertThat(body.path("code").asString()).isEqualTo(CoreProblemCode.BAD_REQUEST.code());
  }

  @Test
  @DisplayName("a missing multipart part is 400 BAD_REQUEST")
  void aMissingRequestPartIsBadRequest() throws Exception {
    JsonNode body =
        problem(
            multipart("/part").file(new MockMultipartFile("other", new byte[] {1})),
            HttpStatus.BAD_REQUEST);

    assertThat(body.path("code").asString()).isEqualTo(CoreProblemCode.BAD_REQUEST.code());
    assertThat(body.path("detail").asString()).contains("file");
  }

  @Test
  @DisplayName("a response no accepted media type can carry is 406 NOT_ACCEPTABLE")
  void anUnacceptableMediaTypeIsNotAcceptable() throws Exception {
    JsonNode body = problem(get("/json").accept("text/csv"), HttpStatus.NOT_ACCEPTABLE);

    assertThat(body.path("code").asString()).isEqualTo(CoreProblemCode.NOT_ACCEPTABLE.code());
  }

  @Test
  @DisplayName("an upload over the multipart limit is 413 REQUEST_BODY_TOO_LARGE")
  void anOversizedUploadIsContentTooLarge() throws Exception {
    JsonNode body = problem(post("/upload"), HttpStatus.CONTENT_TOO_LARGE);

    assertThat(body.path("code").asString())
        .isEqualTo(CoreProblemCode.REQUEST_BODY_TOO_LARGE.code());
  }

  @Test
  @DisplayName("a 429 raised as a ResponseStatusException carries RATE_LIMIT_EXCEEDED")
  void aTooManyRequestsStatusCarriesTheRateLimitCode() throws Exception {
    JsonNode body = problem(get("/too-many"), HttpStatus.TOO_MANY_REQUESTS);

    assertThat(body.path("code").asString()).isEqualTo(CoreProblemCode.RATE_LIMIT_EXCEEDED.code());
  }

  @Test
  @DisplayName("a path variable the mapping does not declare stays a server defect")
  void anUndeclaredPathVariableStaysAServerError() throws Exception {
    JsonNode body = problem(get("/path-variable/7"), HttpStatus.INTERNAL_SERVER_ERROR);

    assertThat(body.path("code").asString()).isEqualTo(CoreProblemCode.INTERNAL_ERROR.code());
  }

  /**
   * Performs a request and asserts the problem envelope every refusal must carry.
   *
   * @param request the request
   * @param expected the status the refusal must answer
   * @return the parsed problem body
   * @throws Exception if the request cannot be performed
   */
  private JsonNode problem(RequestBuilder request, HttpStatus expected) throws Exception {
    MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
    String raw = response.getContentAsString();

    assertThat(response.getStatus()).as("status of %s", raw).isEqualTo(expected.value());
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    JsonNode body = new ObjectMapper().readTree(raw);
    assertThat(body.path("status").asInt()).isEqualTo(expected.value());
    assertThat(body.path("correlationId").asString("")).isNotBlank();
    assertThat(body.path("title").asString(""))
        .as("the title must be localized, not the bundle key")
        .isNotBlank()
        .doesNotStartWith("problem.");
    assertThat(body.path("detail").asString("")).isNotBlank().doesNotStartWith("problem.");
    return body;
  }

  /** Endpoints that raise each client-error exception Spring MVC throws before a handler runs. */
  @RestController
  static class ProbeController {

    /**
     * Requires a query parameter.
     *
     * @param topics the required parameter
     * @return the parameter
     */
    @GetMapping("/param")
    String param(@RequestParam("topics") String topics) {
      return topics;
    }

    /**
     * Requires a header.
     *
     * @param probe the required header
     * @return the header
     */
    @GetMapping("/header")
    String header(@RequestHeader("X-Probe") String probe) {
      return probe;
    }

    /**
     * Requires a cookie.
     *
     * @param probe the required cookie
     * @return the cookie
     */
    @GetMapping("/cookie")
    String cookie(@CookieValue("probe") String probe) {
      return probe;
    }

    /**
     * Matches only when {@code mode=strict} is present.
     *
     * @return a constant
     */
    @GetMapping(path = "/conditional", params = "mode=strict")
    String conditional() {
      return "strict";
    }

    /**
     * Requires a multipart part named {@code file}.
     *
     * @param file the required part
     * @return the part's name
     */
    @PostMapping("/part")
    String part(@RequestPart("file") MultipartFile file) {
      return file.getName();
    }

    /**
     * Produces JSON only.
     *
     * @return a constant
     */
    @GetMapping(path = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    String json() {
      return "{}";
    }

    /**
     * Fails the way the multipart resolver fails on an upload over the limit.
     *
     * @return never returns
     */
    @PostMapping("/upload")
    String upload() {
      throw new MaxUploadSizeExceededException(1024L);
    }

    /**
     * Refuses with a bare 429.
     *
     * @return never returns
     */
    @GetMapping("/too-many")
    String tooMany() {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * Declares a path variable its mapping does not name.
     *
     * @param other the undeclared variable
     * @return the variable
     */
    @GetMapping("/path-variable/{id}")
    String pathVariable(@PathVariable("other") String other) {
      return other;
    }
  }
}
