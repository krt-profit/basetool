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

package de.greluc.krt.profit.basetool.frontend.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Proves {@link ChromeNeutraliser} reduces both page shapes to the same markers. */
class ChromeNeutraliserTest {

  private static final String SIDEBAR =
      "<th:block th:replace=\"~{kernel/fragments/sidebar :: sidebar}\" />";

  private static final String HEADER =
      "<th:block th:replace=\"~{kernel/fragments/header :: header}\" />";

  private static final String TOAST =
      "<th:block th:replace=\"~{kernel/fragments/toast :: toast}\" />";

  @Test
  void aLegacyPageKeepsItsChromeCallsAndLosesItsOwnMarkup() {
    String source =
        "<html><body>"
            + SIDEBAR
            + HEADER
            + "<main th:text=\"${missing.value}\">x</main>"
            + TOAST
            + "<script>y</script></body></html>";

    assertThat(ChromeNeutraliser.neutralise(source))
        .isEqualTo(
            "<html><body>"
                + SIDEBAR
                + HEADER
                + ChromeNeutraliser.CONTENT
                + TOAST
                + ChromeNeutraliser.TAIL
                + "</body></html>");
  }

  @Test
  void aLegacyPageWithoutToastHasOneContentMarker() {
    String source = "<body>" + SIDEBAR + HEADER + "<main>a</main><script>b</script></body>";

    assertThat(ChromeNeutraliser.neutralise(source))
        .isEqualTo("<body>" + SIDEBAR + HEADER + ChromeNeutraliser.CONTENT + "</body>");
  }

  @Test
  void aLegacyPageWithTheToastLastHasNoTailMarker() {
    String source = "<body>" + SIDEBAR + HEADER + "<main>a</main>" + TOAST + "</body>";

    assertThat(ChromeNeutraliser.neutralise(source))
        .isEqualTo("<body>" + SIDEBAR + HEADER + ChromeNeutraliser.CONTENT + TOAST + "</body>");
  }

  @Test
  void aLayoutPageEmptiesItsContentAndTailBlocksAroundNestedBlocks() {
    String source =
        "<html th:replace=\"~{kernel/fragments/layout ::"
            + " page(pageContent=~{::pageContent})}\"><body><th:block"
            + " th:fragment=\"pageContent\"><th:block th:if=\"${a > b}\"><th:block th:replace=\"~{x"
            + " :: y}\" /></th:block><main>a</main></th:block><th:block"
            + " th:fragment=\"pageTail\"><script>b</script></th:block></body></html>";

    assertThat(ChromeNeutraliser.neutralise(source))
        .isEqualTo(
            "<html th:replace=\"~{kernel/fragments/layout ::"
                + " page(pageContent=~{::pageContent})}\"><body><th:block"
                + " th:fragment=\"pageContent\">"
                + ChromeNeutraliser.CONTENT
                + "</th:block>"
                + "<th:block th:fragment=\"pageTail\">"
                + ChromeNeutraliser.TAIL
                + "</th:block></body></html>");
  }

  @Test
  void anUnbalancedLayoutBlockIsRefused() {
    String source =
        "<html th:replace=\"~{kernel/fragments/layout :: page()}\"><body>"
            + "<th:block th:fragment=\"pageContent\"><th:block>"
            + "</th:block></body></html>";

    assertThatThrownBy(() -> ChromeNeutraliser.neutralise(source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pageContent");
  }

  @Test
  void aPageWithoutBodyIsRefused() {
    assertThatThrownBy(() -> ChromeNeutraliser.neutralise("<html></html>"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
