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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders the reusable design-system component fragments ({@code fragments/components.html})
 * through the Spring Thymeleaf engine and asserts the produced markup matches the
 * krt-components.css / preview spec. Thymeleaf fragment errors surface only at render time (never
 * at compile/build time), so this test pins button/alert/table down the same way {@code
 * OperationPageControllerMvcTest} happens to cover the alert fragment via the operations list — but
 * here every fragment, including the otherwise-unexercised {@code dataTable} shell and the {@code
 * null}-message self-gating, is driven directly through a test-only harness template.
 */
@SpringBootTest
class ComponentFragmentsRenderTest {

  @Autowired private ITemplateEngine templateEngine;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Drives the harness template and asserts each fragment emits its canonical classes/structure:
   * the button carries {@code btn btn-danger} + a submit type, the alert carries the tinted {@code
   * alert alert-danger} with its extra class, an alert fed a {@code null} message renders nothing
   * (no stray {@code alert-success}), and the data table is wrapped responsively with the {@code
   * krt-table} class plus the injected header cells and body rows.
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

    assertThat(html).contains("class=\"table-responsive\"");
    assertThat(html).contains("class=\"krt-table\"");
    assertThat(html).contains(">COL-A<");
    assertThat(html).contains(">ROW-1<");
  }
}
