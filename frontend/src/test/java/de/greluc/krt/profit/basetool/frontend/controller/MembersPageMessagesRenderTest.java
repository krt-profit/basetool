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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code /members} in both locales and asserts that the message dictionary its inline
 * script hands to the delete, sync and consolidate dialogs arrives as JavaScript string literals
 * with the resolved text and no unresolved {@code [[#{…}]]} marker.
 */
@SpringBootTest
class MembersPageMessagesRenderTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Map<String, String> CONSTANT_TO_KEY =
      Map.ofEntries(
          Map.entry("MSG_DELETE_TITLE", "members.delete.title"),
          Map.entry("MSG_DELETE_CONFIRM", "members.delete_confirm"),
          Map.entry("MSG_DELETE_LABEL", "members.delete"),
          Map.entry("MSG_CANCEL_LABEL", "general.cancel"),
          Map.entry("MSG_DELETE_SUCCESS", "success.user.delete"),
          Map.entry("MSG_DELETE_ERROR", "error.user.delete"),
          Map.entry("MSG_SYNC_SUCCESS", "members.sync.success"),
          Map.entry("MSG_SYNC_ERROR", "members.sync.error"),
          Map.entry("MSG_CONSOLIDATE_SUCCESS", "success.user.consolidate"),
          Map.entry("MSG_CONSOLIDATE_ERROR", "error.user.consolidate"),
          Map.entry("MSG_CONSOLIDATE_NO_TARGET", "error.user.consolidate.no_target"));

  @Autowired private WebApplicationContext context;

  @MockitoSpyBean private MessageSource messageSource;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    PageResponse<UserDto> page = new PageResponse<>(List.of(), 0, 20, 0, 0, List.of());
    when(backendApiClient.get(eq("/api/v1/users?sort=username,asc"), anyTypeRef()))
        .thenReturn(page);
    when(backendApiClient.get(
            eq("/api/v1/users/{id}/memberships"), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());
  }

  /** The German page carries every dialog message, resolved and JavaScript-escaped. */
  @Test
  void germanPageCarriesEveryDialogMessage() throws Exception {
    assertDictionary(Locale.GERMAN);
  }

  /** The English page carries every dialog message, resolved and JavaScript-escaped. */
  @Test
  void englishPageCarriesEveryDialogMessage() throws Exception {
    assertDictionary(Locale.ENGLISH);
  }

  /**
   * A translation holding quotes, an apostrophe, angle brackets, an ampersand and a backslash still
   * reaches the script as one valid JavaScript string literal with exactly that text.
   */
  @Test
  void aTranslationWithSpecialCharactersSurvivesAsAJavaScriptLiteral() throws Exception {
    String tricky = "It's a \"test\" <b>&amp; \\ done";
    doReturn(tricky)
        .when(messageSource)
        .getMessage(eq("members.delete.title"), any(), any(), any(Locale.class));
    doReturn(tricky)
        .when(messageSource)
        .getMessage(eq("members.delete.title"), any(), any(Locale.class));
    String html =
        mockMvc
            .perform(
                get("/members")
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Matcher declaration =
        Pattern.compile("const MSG_DELETE_TITLE = (.*?);\\s*$", Pattern.MULTILINE).matcher(html);
    assertThat(declaration.find()).isTrue();
    assertThat(declaration.group(1)).doesNotContain("&#").doesNotContain("&quot;");
    assertThat(unescapeJs(declaration.group(1))).isEqualTo(tricky);
  }

  private void assertDictionary(Locale locale) throws Exception {
    String html =
        mockMvc
            .perform(
                get("/members")
                    .cookie(new Cookie("KRT_LOCALE", locale.toLanguageTag()))
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("no unresolved message marker is sent").doesNotContain("[[#{");
    for (Map.Entry<String, String> entry : CONSTANT_TO_KEY.entrySet()) {
      Matcher declaration =
          Pattern.compile("const " + entry.getKey() + " = (.*?);\\s*$", Pattern.MULTILINE)
              .matcher(html);
      assertThat(declaration.find()).as("%s is declared", entry.getKey()).isTrue();
      String literal = declaration.group(1);
      String expected = messageSource.getMessage(entry.getValue(), null, locale);
      assertThat(literal)
          .as("%s is a JavaScript string literal, not HTML-escaped text", entry.getKey())
          .startsWith("\"")
          .endsWith("\"")
          .doesNotContain("&quot;")
          .doesNotContain("&amp;")
          .doesNotContain("&#");
      assertThat(unescapeJs(literal))
          .as("%s carries the %s text of %s", entry.getKey(), locale, entry.getValue())
          .isEqualTo(expected);
    }
  }

  /**
   * Decodes a string literal the way a JavaScript engine reads it; the literal Thymeleaf emits is
   * JSON-compatible, so the JSON reader does the unescaping.
   *
   * @param literal a double-quoted string literal
   * @return the string value the literal denotes
   */
  private static String unescapeJs(String literal) {
    return JSON.readValue(literal, String.class);
  }
}
