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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.bank.client.BankBackendClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendCallPolicyFixture;
import de.greluc.krt.profit.basetool.frontend.kernel.web.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.view.AbstractView;

/** Exercises HTML error rendering after an uncommitted servlet download fails. */
class BankDownloadErrorMvcTest {

  private MockWebServer backend;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws IOException {
    backend = new MockWebServer();
    backend.start();
    WebClient transport = WebClient.builder().baseUrl(backend.url("/").toString()).build();
    BackendApiClient kernel =
        new BackendApiClient(
            transport,
            transport,
            new SimpleMeterRegistry(),
            new NoOpCacheManager(),
            BackendCallPolicyFixture.create(Duration.ofSeconds(1)));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new BankReportProxyController(new BankBackendClient(kernel)))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .addFilters(
                (jakarta.servlet.Filter)
                    (request, response, chain) -> {
                      ((HttpServletResponse) response).setHeader("X-Security-Test", "keep");
                      chain.doFilter(request, response);
                    })
            .setSingleView(
                new AbstractView() {
                  /**
                   * Writes an HTML view through the servlet writer selected by template rendering.
                   */
                  @Override
                  protected void renderMergedOutputModel(
                      Map<String, Object> model,
                      HttpServletRequest request,
                      HttpServletResponse response)
                      throws IOException {
                    response.setContentType("text/html;charset=UTF-8");
                    response
                        .getWriter()
                        .write("<!doctype html><html><body>Download unavailable</body></html>");
                  }
                })
            .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    backend.shutdown();
  }

  @Test
  void aForbiddenDownloadCanRenderTheHtmlErrorPage() throws Exception {
    backend.enqueue(new MockResponse().setResponseCode(403));

    expectHtmlError(403);
  }

  @Test
  void aKnownSuccessWhoseBodyStallsBeforeItsFirstByteCanRenderTheHtmlErrorPage() throws Exception {
    backend.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/pdf")
            .setBody("%PDF")
            .setBodyDelay(2, TimeUnit.SECONDS));

    expectHtmlError(504);
  }

  private void expectHtmlError(int expectedStatus) throws Exception {
    mvc.perform(
            get("/api/proxy/bank/accounts/00000000-0000-0000-0000-000000000001/statement")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .accept(MediaType.TEXT_HTML))
        .andExpect(status().is(expectedStatus))
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(
            content().string("<!doctype html><html><body>Download unavailable</body></html>"))
        .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
        .andExpect(header().doesNotExist(HttpHeaders.CONTENT_LENGTH))
        .andExpect(header().string("X-Security-Test", "keep"));
  }
}
