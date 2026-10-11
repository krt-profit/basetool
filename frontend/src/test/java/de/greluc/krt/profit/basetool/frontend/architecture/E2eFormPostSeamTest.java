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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Verifies, reading the source as text, that {@code E2eSupport.awaitFormPost} returns only once the
 * post-submit document has been committed and loaded, so a following navigation cannot overtake it.
 */
class E2eFormPostSeamTest {

  /** The Playwright helper class, relative to the {@code frontend} module the test runs in. */
  private static final Path SEAM =
      Path.of("src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/E2eSupport.java");

  /** The declaration that opens the method under check. */
  private static final String METHOD = "static void awaitFormPost(";

  /** The response-only body the helper had while it raced the next navigation. */
  private static final String RESPONSE_ONLY_BODY =
      """
        page.waitForResponse(
            response -> response.request().isNavigationRequest(),
            new Page.WaitForResponseOptions().setTimeout(15_000),
            submitAction);
      }
      """;

  /**
   * The helper waits for the landing URL to be committed with the {@code load} state after the
   * document response.
   *
   * @throws IOException if {@code E2eSupport} cannot be read
   */
  @Test
  void awaitFormPostWaitsForTheLandingDocumentToLoad() throws IOException {
    String source = Files.readString(SEAM, StandardCharsets.UTF_8).replace("\r\n", "\n");
    assertThat(source).contains(METHOD);
    assertThat(waitsForLanding(methodBody(source)))
        .as(
            "awaitFormPost must waitForURL(..., WaitUntilState.LOAD) after the document response;"
                + " returning on the response lets the next navigate race the landing page")
        .isTrue();
  }

  /** The check rejects a body that only awaits the document response. */
  @Test
  void theCheckRejectsAResponseOnlyBody() {
    assertThat(waitsForLanding(RESPONSE_ONLY_BODY)).isFalse();
  }

  /**
   * Cuts the {@code awaitFormPost} body out of the source, up to the method's closing brace.
   *
   * @param source the whole {@code E2eSupport} source
   * @return the text from the declaration to the first two-space-indented closing brace after it
   */
  private static String methodBody(String source) {
    int start = source.indexOf(METHOD);
    int end = source.indexOf("\n  }\n", start);
    return source.substring(start, end < 0 ? source.length() : end);
  }

  /**
   * Tells whether a method body waits for the response first and then for the committed, loaded
   * landing URL.
   *
   * @param body the method body text
   * @return {@code true} when {@code waitForURL(} with {@code WaitUntilState.LOAD} follows {@code
   *     waitForResponse(}
   */
  private static boolean waitsForLanding(String body) {
    int response = body.indexOf("waitForResponse(");
    int landing = body.indexOf("waitForURL(");
    return response >= 0 && landing > response && body.indexOf("WaitUntilState.LOAD") > landing;
  }
}
