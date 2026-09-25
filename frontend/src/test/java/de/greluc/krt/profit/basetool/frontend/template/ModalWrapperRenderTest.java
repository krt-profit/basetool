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

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders {@code fragments/modal-wrapper :: modal} with every parameter shape the templates use and
 * asserts the emitted shell (REQ-UI-013, ADR-0177): a {@code <dialog class="krt-modal-overlay">}
 * whose {@code .krt-modal} frame has an {@code <h2>} title, one ✕ close control and the page body
 * below the head.
 */
@SpringBootTest
class ModalWrapperRenderTest {

  @Autowired private ITemplateEngine templateEngine;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private String html;

  /** Renders the harness once per test, German, with two iteration rows. */
  @BeforeEach
  void render() {
    Context context = new Context(Locale.GERMAN);
    context.setVariable("rows", List.of("A", "B"));
    html = templateEngine.process("modal-wrapper-harness", context);
  }

  /**
   * The plain call: a native dialog, hidden by default, labelled by its title, with an {@code
   * <h2>}, the shared close trigger aimed at itself, and the body and footer below the head.
   */
  @Test
  void aPlainCallRendersTheCanonicalShell() {
    String dialog = dialog("h-plain");
    assertThat(dialog)
        .startsWith("<dialog class=\"krt-modal-overlay\" id=\"h-plain\" aria-label=\"")
        .doesNotContain("krtm-modal-open")
        .doesNotContain("aria-labelledby")
        .contains("<div class=\"krt-modal\">")
        .contains("<div class=\"krt-modal-head\">")
        .containsPattern("<h2>[^<]+</h2>")
        .contains("data-trigger=\"close-modal-display\"")
        .contains("data-modal-id=\"h-plain\"")
        .contains(">&#10005;</button>")
        .contains("PLAIN-BODY")
        .contains("<div class=\"krt-modal-foot\">");
    assertThat(dialog.indexOf("PLAIN-BODY"))
        .as("the body comes after the head")
        .isGreaterThan(dialog.indexOf("krt-modal-head"));
    assertThat(dialog).doesNotContain("th:").doesNotContain("h-plain-body");
  }

  /**
   * Every optional parameter at once: frame variant, a heading id that labels the dialog, the
   * server-driven open state, a page close handler, a class hook and an id on the close control.
   */
  @Test
  void everyOptionalParameterLandsOnItsElement() {
    String dialog = dialog("h-full");
    assertThat(dialog)
        .as("the dialog is labelled by its heading, not by a copy of the title")
        .startsWith(
            "<dialog class=\"krt-modal-overlay krtm-modal-open\" id=\"h-full\""
                + " aria-labelledby=\"h-full-title\">")
        .contains("<div class=\"krt-modal krt-modal--wide krt-modal--danger\">")
        .containsPattern("<h2 id=\"h-full-title\">[^<]+</h2>")
        .contains("class=\"krt-modal-close close-h-full\"")
        .contains("id=\"h-full-x\"")
        .contains("data-trigger=\"page-close-handler\"")
        .contains("data-modal-id=\"h-full\"")
        .contains("<form class=\"h-full-form\">");
  }

  /** An empty close trigger leaves the close control to a page script bound by its class. */
  @Test
  void anEmptyCloseTriggerRendersNoTrigger() {
    String dialog = dialog("h-class-bound");
    assertThat(dialog)
        .doesNotContain("data-trigger")
        .doesNotContain("krtm-modal-open")
        .contains("class=\"krt-modal-close close-h-class\"");
  }

  /** Inside an iteration, the id is built per row and the body sees the row variable. */
  @Test
  void anIteratedCallRendersOneDialogPerRow() {
    assertThat(dialog("h-row-A")).contains("ROW-BODY-A").contains("data-modal-id=\"h-row-A\"");
    assertThat(dialog("h-row-B")).contains("ROW-BODY-B").contains("data-modal-id=\"h-row-B\"");
  }

  /** A condition around the call suppresses the whole dialog, body included. */
  @Test
  void aConditionAroundTheCallSuppressesTheDialog() {
    assertThat(html).doesNotContain("h-hidden").doesNotContain("HIDDEN-BODY");
  }

  /**
   * Extracts one rendered dialog by id, with every run of whitespace collapsed to one space so the
   * assertions do not depend on how the fragment's attributes are wrapped.
   *
   * @param id the dialog id
   * @return its markup, from {@code <dialog} to {@code </dialog>}
   */
  private String dialog(String id) {
    Matcher m =
        Pattern.compile("<dialog\\b[^>]*\\bid=\"" + Pattern.quote(id) + "\"[\\s\\S]*?</dialog>")
            .matcher(html);
    assertThat(m.find()).as("dialog #%s is rendered", id).isTrue();
    return m.group().replaceAll("\\s+", " ");
  }
}
