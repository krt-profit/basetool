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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.bank.internal.BankExportTestSupport;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankPdfExport;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Request completion and failed response delivery both release generated Bank export files. */
class BankReportCleanupFilterTest {
  private final BankReportCleanupFilter filter = new BankReportCleanupFilter();

  @Test
  void successfulConversionStreamsTheBodyAndRemovesStorageAfterwards() throws Exception {
    BankPdfExport export = BankExportTestSupport.file(new byte[] {1, 2, 3});
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        (_, _) -> {
          var attachment = BankReportCleanupFilter.attachment(request, export, "synthetic.pdf");
          new ResourceHttpMessageConverter()
              .write(
                  attachment.getBody(),
                  MediaType.APPLICATION_PDF,
                  new ServletServerHttpResponse(response));
        });
    assertThat(response.getContentAsByteArray()).containsExactly(1, 2, 3);
    assertThatThrownBy(export::getInputStream).isInstanceOf(IOException.class);
  }

  @Test
  void headAndEmptyResponsesCleanUpWithoutOpeningTheBody() throws Exception {
    for (int size : new int[] {0, 3}) {
      BankPdfExport export = BankExportTestSupport.file(new byte[size]);
      MockHttpServletRequest request = new MockHttpServletRequest("HEAD", "/synthetic");
      filter.doFilter(
          request,
          new MockHttpServletResponse(),
          (_, _) -> {
            BankReportCleanupFilter.attachment(request, export, "synthetic.pdf");
            assertThat(export.contentLength()).isEqualTo(size);
          });
      assertThatThrownBy(export::getInputStream).isInstanceOf(IOException.class);
    }
  }

  @Test
  void aResourceCanBeReopenedUntilRequestCompletion() throws Exception {
    BankPdfExport export = BankExportTestSupport.file(new byte[] {1, 2, 3});
    MockHttpServletRequest request = new MockHttpServletRequest();
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (_, _) -> {
          BankReportCleanupFilter.attachment(request, export, "synthetic.pdf");
          for (int i = 0; i < 2; i++) {
            try (var input = export.getInputStream()) {
              assertThat(input.readAllBytes()).containsExactly(1, 2, 3);
            }
          }
        });
    assertThatThrownBy(export::getInputStream).isInstanceOf(IOException.class);
  }

  @Test
  void cancellationBeforeOrDuringConversionClosesStorageAndPreservesTheFailure() {
    for (boolean opened : new boolean[] {false, true}) {
      BankPdfExport export = BankExportTestSupport.file(new byte[] {1, 2, 3});
      MockHttpServletRequest request = new MockHttpServletRequest();
      IOException disconnected = new IOException("synthetic disconnect");
      assertThatThrownBy(() ->
              filter.doFilter(
                  request,
                  new MockHttpServletResponse(),
                  (_, _) -> {
                    BankReportCleanupFilter.attachment(request, export, "synthetic.pdf");
                    if (opened) {
                      try (var input = export.getInputStream()) {
                        input.read();
                      }
                    }
                    throw disconnected;
                  }))
          .isSameAs(disconnected);
      assertThatThrownBy(export::getInputStream).isInstanceOf(IOException.class);
    }
  }
}
