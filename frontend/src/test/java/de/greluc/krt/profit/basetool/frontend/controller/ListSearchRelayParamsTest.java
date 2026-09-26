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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.exception.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.service.MarkdownRenderer;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies how the mission and operation list pages pass their filters into the backend search URI
 * (REQ-SEC-051): as URI template variables, never concatenated or pre-encoded.
 *
 * <p>Checks the exact template and variables against a mocked {@link BackendApiClient}, and the
 * query the backend actually receives against a {@link MockWebServer}.
 */
class ListSearchRelayParamsTest {

  private static final String HOSTILE_TERM = "a&size=1 #+{x}%";
  private static final String START = "2026-01-01T00:00:00Z";
  private static final String END = "2026-02-01T12:30:00Z";
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0,\"sort\":[]}";

  private BackendApiClient mockedBackend;
  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception {
    mockedBackend = mock(BackendApiClient.class);
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void missionListRelaysEveryCallerValueAsATemplateVariable() throws Exception {
    when(mockedBackend.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(emptyPage());

    missionsMvc(mockedBackend)
        .perform(
            get("/missions")
                .param("search", HOSTILE_TERM)
                .param("start", START)
                .param("end", END)
                .param("fragment", "results"))
        .andExpect(status().isOk());

    verify(mockedBackend)
        .get(
            eq(
                "/api/v1/missions/search?query={query}&start={start}&end={end}"
                    + "&sort=plannedStartTime,desc&status=PLANNED&status=ACTIVE&"),
            anyTypeRef(),
            eq(HOSTILE_TERM),
            eq(Instant.parse(START)),
            eq(Instant.parse(END)));
  }

  @Test
  void missionListDropsAStatusTheBackendDoesNotKnow() throws Exception {
    when(mockedBackend.get(anyString(), anyTypeRef())).thenReturn(emptyPage());

    missionsMvc(mockedBackend)
        .perform(
            get("/missions")
                .param("status", "COMPLETED")
                .param("status", "ACTIVE&role=ADMIN")
                .param("fragment", "results"))
        .andExpect(status().isOk());

    verify(mockedBackend)
        .get(
            eq("/api/v1/missions/search?sort=plannedStartTime,desc&status=COMPLETED&"),
            anyTypeRef());
  }

  @Test
  void missionListRefusesAPeriodThatIsNotAnInstantWithoutCallingTheBackend() throws Exception {
    missionsMvc(mockedBackend)
        .perform(get("/missions").param("start", START + "&status=CANCELLED"))
        .andExpect(status().isBadRequest());

    verify(mockedBackend, never()).get(anyString(), anyTypeRef());
    verify(mockedBackend, never()).get(anyString(), anyTypeRef(), any(Object[].class));
  }

  @Test
  void operationListRelaysItsPeriodAsTemplateVariablesNotPreEncodedText() throws Exception {
    when(mockedBackend.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(emptyPage());

    operationsMvc(mockedBackend)
        .perform(
            get("/operations")
                .param("search", HOSTILE_TERM)
                .param("start", START)
                .param("end", END)
                .param("fragment", "results"))
        .andExpect(status().isOk());

    verify(mockedBackend)
        .get(
            eq(
                "/api/v1/operations/search?query={query}&start={start}&end={end}"
                    + "&page=0&size=20&sort=createdAt,desc&status=PLANNED&status=ACTIVE&"),
            anyTypeRef(),
            eq(HOSTILE_TERM),
            eq(Instant.parse(START)),
            eq(Instant.parse(END)));
  }

  @Test
  void theBackendReceivesTheMissionFiltersAsSingleDecodedParameters() throws Exception {
    server.enqueue(jsonPage());

    missionsMvc(realBackend())
        .perform(
            get("/missions")
                .param("search", HOSTILE_TERM)
                .param("start", START)
                .param("fragment", "results"))
        .andExpect(status().isOk());

    HttpUrl url = receivedUrl();
    assertEquals(List.of(HOSTILE_TERM), url.queryParameterValues("query"));
    assertEquals(List.of(START), url.queryParameterValues("start"));
    assertTrue(
        url.queryParameterValues("size").isEmpty(),
        "the search term must not open a second `size` parameter: " + url);
    assertEquals(List.of("PLANNED", "ACTIVE"), url.queryParameterValues("status"));
  }

  @Test
  void theBackendReceivesTheOperationPeriodDecodedOnceSoItParsesAsAnInstant() throws Exception {
    server.enqueue(jsonPage());

    operationsMvc(realBackend())
        .perform(
            get("/operations")
                .param("search", HOSTILE_TERM)
                .param("start", START)
                .param("end", END)
                .param("fragment", "results"))
        .andExpect(status().isOk());

    HttpUrl url = receivedUrl();
    assertEquals(List.of(HOSTILE_TERM), url.queryParameterValues("query"));
    assertEquals(List.of(START), url.queryParameterValues("start"));
    assertEquals(List.of(END), url.queryParameterValues("end"));
    assertEquals(List.of("20"), url.queryParameterValues("size"));
  }

  private MockMvc missionsMvc(BackendApiClient backend) {
    return standalone(
        new MissionPageController(
            backend, mock(FrontendAuthHelperService.class), mock(ParallelPageLoader.class)));
  }

  private MockMvc operationsMvc(BackendApiClient backend) {
    return standalone(
        new OperationPageController(
            backend, mock(MarkdownRenderer.class), mock(ParallelPageLoader.class)));
  }

  private static MockMvc standalone(Object controller) {
    return MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
        .build();
  }

  private BackendApiClient realBackend() {
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    return new BackendApiClient(
        webClient, webClient, new SimpleMeterRegistry(), new NoOpCacheManager());
  }

  private HttpUrl receivedUrl() throws InterruptedException {
    RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
    assertNotNull(request, "the page must have queried the backend");
    HttpUrl url = request.getRequestUrl();
    assertNotNull(url);
    return url;
  }

  private static <T> PageResponse<T> emptyPage() {
    return new PageResponse<>(List.of(), 0, 20, 0L, 0, List.of());
  }

  private static MockResponse jsonPage() {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(EMPTY_PAGE);
  }
}
