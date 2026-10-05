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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the order create and item-edit form on the form pattern (REQ-UI-027): the back-link page
 * head, the kind as a segmented control, numbered sections with the material column head and the
 * remove control, the scmdb import as a dialog, the comment counter, and one primary action per
 * form in the sticky action bar.
 */
@SpringBootTest
class OrdersCreateFormPatternRenderTest {

  private static final Pattern FORM = Pattern.compile("(?s)<form\\b.*?</form>");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds the MockMvc and answers every cached catalogue with an empty list. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    doReturn(List.of()).when(backendApiClient).getCached(any(CachedCatalog.class), anyTypeRef());
  }

  /**
   * Renders a page in German.
   *
   * @param path the request path
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull String path) throws Exception {
    return mockMvc
        .perform(get(path).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Splits the page's {@code <main>} into its forms, leaving out the layout's own forms.
   *
   * @param html the rendered page
   * @return each {@code <form>} element's markup inside {@code <main>}, in document order
   */
  private static @NotNull List<String> forms(@NotNull String html) {
    String main = html.substring(html.indexOf("<main>"), html.indexOf("</main>"));
    List<String> forms = new ArrayList<>();
    Matcher matcher = FORM.matcher(main);
    while (matcher.find()) {
      forms.add(matcher.group());
    }
    return forms;
  }

  /** The create form renders the head, the kind segment, the sections and the sticky actions. */
  @Test
  @WithMockUser
  void createFormRendersTheFormPattern() throws Exception {
    String html = render("/orders/create");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\" href=\"/orders\"")
        .contains("<span>Aufträge</span>")
        .contains("<h1>Neuer Auftrag</h1>")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("Zurück zur Übersicht")
        .doesNotContain("<details");
    assertThat(html)
        .contains("class=\"segmented segmented--block segmented--lg orders-create__kind\"")
        .contains("data-testid=\"order-mode-material\"")
        .contains("data-testid=\"order-mode-item\"")
        .containsPattern("name=\"orderModeToggle\" value=\"material\"[^>]*checked=\"checked\"")
        .containsPattern("id=\"mode-item\" hidden=\"hidden\"");
    assertThat(html)
        .contains("class=\"form-layout card orders-create\"")
        .contains(">1 · Auftrag<")
        .contains("2 · Materialien")
        .contains("<span>Kommentar</span>")
        .contains("· optional")
        .containsPattern("class=\"material-grid material-grid--head\" aria-hidden=\"true\"")
        .containsPattern("data-trigger=\"orders-remove-material\"[^>]*disabled=\"disabled\"")
        .contains("data-testid=\"order-material-select\"")
        .contains("data-testid=\"order-material-amount\"")
        .contains("data-trigger=\"orders-add-material\"");
    assertThat(html)
        .containsPattern("<dialog[^>]*id=\"orders-scmdb-modal\"")
        .contains("data-modal-id=\"orders-scmdb-modal\" data-testid=\"orders-scmdb-open\"")
        .contains("id=\"scmdb-import-text\"")
        .containsPattern("id=\"comment-count\"[^>]*>0 / 1000<")
        .contains("id=\"minquality-options-template\" hidden");

    try (InputStream template = getClass().getResourceAsStream("/templates/orders-create.html")) {
      assertThat(template).isNotNull();
      assertThat(new String(template.readAllBytes(), StandardCharsets.UTF_8))
          .as("the template carries no migrated krtm class")
          .doesNotContain("krtm-");
    }

    List<String> forms = forms(html);
    assertThat(forms).hasSize(2);
    for (String form : forms) {
      assertThat(form).contains("class=\"form-actions--sticky\"");
      assertThat(form.split("btn--cta", -1)).hasSize(2);
      assertThat(form).contains("Auftrag anlegen");
    }
    assertThat(forms.get(0))
        .contains("data-testid=\"order-submit\"")
        .contains("id=\"orders-material-summary\"");
    assertThat(forms.get(1))
        .contains("data-testid=\"order-item-submit\"")
        .contains("id=\"orders-item-summary\"");
  }

  /** The item editor hides the kind segment and the material form and saves changes. */
  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void itemEditFormHidesTheKindAndSavesChanges() throws Exception {
    UUID id = UUID.randomUUID();
    JobOrderDto order =
        new JobOrderDto(
            id,
            7,
            null,
            null,
            "Handle",
            "Kurz",
            1,
            "OPEN",
            "ITEM",
            true,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    doReturn(order)
        .when(backendApiClient)
        .get(eq("/api/v1/orders/{id}"), eq(JobOrderDto.class), eq(id));

    String html = render("/orders/" + id + "/items/edit");

    assertThat(html)
        .contains("<h1>Auftrag bearbeiten</h1>")
        .containsPattern("class=\"segmented [^\"]*\"[^>]*hidden=\"hidden\"")
        .containsPattern("name=\"orderModeToggle\" value=\"item\"[^>]*checked=\"checked\"")
        .containsPattern("id=\"mode-material\" hidden=\"hidden\"")
        .doesNotContain("id=\"mode-item\" hidden")
        .containsPattern("id=\"item-comment-count\"[^>]*>4 / 1000<")
        .contains("/orders/" + id + "/items/update");
    List<String> forms = forms(html);
    assertThat(forms.get(1)).contains("Änderungen speichern").doesNotContain("Auftrag anlegen");
  }
}
