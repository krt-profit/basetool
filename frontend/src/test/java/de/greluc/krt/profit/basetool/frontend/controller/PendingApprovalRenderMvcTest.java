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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full Thymeleaf render of the account-status page across all three approval states (REQ-SEC-017).
 *
 * <p>The bug this pins: {@code BackendRoleSyncFilter} routes {@code PENDING} and {@code REJECTED}
 * to the same path, and the page used to render the waiting copy unconditionally — so a
 * registration an admin had declined kept reading "waiting for the approval of an administrator …
 * can take 1 to 2 days" indefinitely, and was reported as a stuck approval. A model-attribute
 * assertion alone would not have caught it, because the defect lived in the template; these
 * assertions therefore go through the real render: which block exists, which one is visible, which
 * copy it carries, and whether the status poll is wired at all.
 *
 * <p>The expected strings are resolved from the message bundles rather than hardcoded, so the test
 * pins the render against the shipped wording instead of duplicating (and drifting from) it. The
 * locale is pinned per request via {@code ?lang=} — the app resolves it from the {@code KRT_LOCALE}
 * cookie and would otherwise fall back to its German default.
 */
@SpringBootTest
class PendingApprovalRenderMvcTest {

  private static final String REGISTRATION_STATUS = "/api/v1/users/me/registration-status";

  @Autowired private WebApplicationContext context;

  @Autowired private MessageSource messageSource;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void pendingRegistration_showsTheWaitingCopyAndWiresThePoll() throws Exception {
    String html = render("PENDING", "de");

    assertThat(openingTagOf(html, "pending-approval-waiting"))
        .as("the waiting block is the visible one for a pending registration")
        .doesNotContain("hidden");
    assertThat(html)
        .contains(message("pendingApproval.title", Locale.GERMAN))
        .contains(message("pendingApproval.message", Locale.GERMAN))
        .contains(message("pendingApproval.patience", Locale.GERMAN));

    assertThat(openingTagOf(html, "pending-approval-rejected"))
        .as("the rejection block ships hidden alongside it, so the poll can reveal it in place")
        .contains("hidden");
    assertThat(html)
        .as("the poll is what forwards a member into the tool the moment an admin approves")
        .contains("pending-approval.js");
  }

  @Test
  void rejectedRegistration_showsTheRejectionCopy() throws Exception {
    String html = render("REJECTED", "de");

    assertThat(openingTagOf(html, "pending-approval-rejected"))
        .as("the rejection block is the visible one for a declined registration")
        .doesNotContain("hidden");
    assertThat(html)
        .contains(message("pendingApproval.rejected.title", Locale.GERMAN))
        .contains(message("pendingApproval.rejected.message", Locale.GERMAN))
        .contains(message("pendingApproval.rejected.noAutoUnlock", Locale.GERMAN))
        .contains(message("pendingApproval.rejected.contact", Locale.GERMAN));
  }

  @Test
  void rejectedRegistration_neverPromisesAnApprovalThatCannotArrive() throws Exception {
    // The regression itself. REJECTED is terminal — the backend answers a second decision with a
    // 409 — so none of the waiting wording may reach a rejected user: not the "an administrator
    // will approve it" body, not the "1 to 2 days" expectation, and not the "this page continues
    // automatically" promise. The waiting block is left out of the document entirely rather than
    // hidden, so this is an absence assertion and not a visibility one.
    String html = render("REJECTED", "de");

    assertThat(html).doesNotContain("id=\"pending-approval-waiting\"");
    assertThat(html)
        .as("no waiting copy survives on the rejection page")
        .doesNotContain(message("pendingApproval.message", Locale.GERMAN))
        .doesNotContain(message("pendingApproval.patience", Locale.GERMAN))
        .doesNotContain(message("pendingApproval.help", Locale.GERMAN));
    assertThat(html)
        .as("a rejected account must not poll for an approval that can never arrive")
        .doesNotContain("pending-approval.js");
  }

  @Test
  void rejectedRegistration_titlesTheTabForTheRejectionToo() throws Exception {
    // The tab title is the one string a user sees without scrolling back to the page, and it is
    // rendered from a separate expression than the heading, so it can drift on its own.
    String html = render("REJECTED", "de");

    assertThat(html)
        .contains("<title>" + message("pendingApproval.rejected.title", Locale.GERMAN))
        .doesNotContain("<title>" + message("pendingApproval.title", Locale.GERMAN));
  }

  @Test
  void rejectedRegistration_isRenderedInEnglishToo() throws Exception {
    // Guards the bundle wiring in both locales: a key added to only one of them renders as the raw
    // key, which the German-only assertions above would not notice.
    String html = render("REJECTED", "en");

    assertThat(html)
        .contains(message("pendingApproval.rejected.title", Locale.ENGLISH))
        .contains(message("pendingApproval.rejected.contact", Locale.ENGLISH));
    assertThat(html)
        .as("an unresolved key renders as its literal name")
        .doesNotContain("pendingApproval.rejected.");
  }

  @Test
  void approvedCaller_isSentIntoTheToolInsteadOfShownAWaitingPage() throws Exception {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));

    mockMvc
        .perform(get("/pending-approval").with(oidcLogin()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/"));
  }

  @Test
  void unreadableBackend_fallsBackToTheWaitingCopy() throws Exception {
    // Fail-safe direction end to end: an outage must not render an accusation.
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class)).thenReturn(null);

    String html =
        mockMvc
            .perform(get("/pending-approval").param("lang", "de").with(oidcLogin()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains(message("pendingApproval.message", Locale.GERMAN));
    assertThat(openingTagOf(html, "pending-approval-rejected")).contains("hidden");
  }

  /**
   * Renders {@code /pending-approval} for a caller whose backend approval status is {@code status},
   * in the given locale.
   *
   * @param status the approval status the backend reports for the caller
   * @param lang the {@code ?lang=} locale tag pinning the render locale
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private String render(String status, String lang) throws Exception {
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto(status));

    return mockMvc
        .perform(get("/pending-approval").param("lang", lang).with(oidcLogin()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Resolves a bundle key so the assertions compare the render against the shipped wording instead
   * of a copy of it.
   *
   * @param key the message key
   * @param locale the locale to resolve in
   * @return the resolved message
   */
  private String message(String key, Locale locale) {
    return messageSource.getMessage(key, null, locale);
  }

  /**
   * Returns the opening tag of the element carrying {@code id}, so a test can assert on the
   * attributes Thymeleaf emitted on it (here: whether {@code hidden} survived).
   *
   * @param html the rendered document
   * @param id the element id to locate
   * @return the element's opening tag, verbatim
   */
  private static String openingTagOf(String html, String id) {
    Matcher matcher =
        Pattern.compile("<[a-zA-Z]+[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>").matcher(html);
    assertThat(matcher.find()).as("element #%s is rendered", id).isTrue();
    return matcher.group();
  }
}
