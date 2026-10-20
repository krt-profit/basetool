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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders every design-system component fragment in {@code fragments/components.html} through a
 * test-only harness template and asserts the markup matches the component spec.
 */
@SpringBootTest
class ComponentFragmentsRenderTest {

  @Autowired private ITemplateEngine templateEngine;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Verifies the canonical classes and structure of the button, alert, reorder and data-table
   * fragments, including that an alert with a {@code null} message renders nothing and that the
   * reorder controls name their item and mark the unavailable end.
   */
  @Test
  void rendersButtonAlertAndDataTableFragmentsToSpec() {
    Context context = new Context(Locale.ENGLISH);
    context.setVariable("msgKey", "info.delete");
    context.setVariable("rows", List.of("ROW-1"));

    String html = templateEngine.process("component-fragment-harness", context);

    assertThat(html).contains("class=\"btn btn-danger\"");
    assertThat(html).contains("type=\"submit\"");
    assertThat(html).contains(">DELETE<");

    assertThat(html).contains("class=\"alert alert-danger mt-2\"");

    assertThat(html).doesNotContain("alert-success");

    assertThat(html).contains("aria-label=\"Move ITEM-X up\"");
    assertThat(html).contains("aria-label=\"Move ITEM-X down\"");
    assertThat(html).containsPattern("data-reorder-move=\"up\"[^>]*aria-disabled=\"true\"");
    assertThat(html).containsPattern("data-reorder-move=\"down\"[^>]*aria-disabled=\"false\"");
    assertThat(html).contains("aria-keyshortcuts=\"Alt+ArrowUp\"");
    assertThat(html)
        .contains(
            "data-reorder-announce=\"{name} moved from position {from} to position {to} of"
                + " {total}.\"");
    assertThat(html).contains("aria-live=\"polite\"");

    assertThat(html).contains("class=\"table-responsive\"");
    assertThat(html).contains("class=\"krt-table\"");
    assertThat(html).contains(">COL-A<");
    assertThat(html).contains(">ROW-1<");
  }
}
