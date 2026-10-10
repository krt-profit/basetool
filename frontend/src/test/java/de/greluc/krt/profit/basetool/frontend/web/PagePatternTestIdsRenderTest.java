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

package de.greluc.krt.profit.basetool.frontend.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.bank.model.BankAccountDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankDashboardDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.operation.model.OperationDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the smoke-test ids of the page patterns (REQ-UI-027) on one representative page each: the
 * page head's primary action, the toolbar search, a segment, the overflow toggle and the row link
 * on the operation and member lists, and the toolbar search of the bank dashboard's account filter.
 */
@SpringBootTest
class PagePatternTestIdsRenderTest {

  private static final UUID OPERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000077");

  private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds the MockMvc instance with the security filter chain. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Renders a page in German for a logged-in user with the given roles.
   *
   * @param path the app-relative path
   * @param roles the roles, without the {@code ROLE_} prefix
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull String path, @NotNull String... roles) throws Exception {
    OidcLoginRequestPostProcessor login = oidcLogin();
    SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length];
    for (int i = 0; i < roles.length; i++) {
      authorities[i] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
    }
    return mockMvc
        .perform(get(path).locale(Locale.GERMAN).with(login.authorities(authorities)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The opening tag of the first element whose attributes contain the given fragment.
   *
   * @param markup the markup to search
   * @param attribute an attribute fragment such as {@code data-testid="x"}
   * @return the whole opening tag
   */
  private static @NotNull String tagWith(@NotNull String markup, @NotNull String attribute) {
    Matcher matcher =
        Pattern.compile("<[a-z]+[^>]*" + Pattern.quote(attribute) + "[^>]*>").matcher(markup);
    assertThat(matcher.find()).as("tag with %s", attribute).isTrue();
    return matcher.group();
  }

  /**
   * The markup of the page head's action row.
   *
   * @param html the page
   * @return the part from {@code .page-actions} to the end of the page head
   */
  private static @NotNull String pageActions(@NotNull String html) {
    int start = html.indexOf("class=\"page-actions\"");
    assertThat(start).as("page-actions rendered").isNotNegative();
    int end = html.indexOf("</main>", start);
    int form = html.indexOf("<form", start);
    return html.substring(start, form > 0 && form < end ? form : end);
  }

  /**
   * The operation list pins the head's primary action inside the action row, the toolbar search,
   * the period segments and the row link.
   *
   * @throws Exception if the request fails
   */
  @Test
  void operationListPinsTheListPatternIds() throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/operations/search?"), anyTypeRef(), any(Object[].class)))
        .thenReturn(
            new PageResponse<>(
                List.of(
                    new OperationDto(
                        OPERATION_ID,
                        "Op Alpha",
                        null,
                        "PLANNED",
                        null,
                        0L,
                        Instant.parse("2030-10-08T18:00:00Z"),
                        null,
                        null)),
                0,
                20,
                1L,
                1,
                List.of()));

    String html = render("/operations", "OFFICER", "MISSION_MANAGER");

    String primary = tagWith(pageActions(html), "data-testid=\"page-head-primary\"");
    assertThat(primary).contains("btn--cta");
    assertThat(tagWith(html, "data-testid=\"toolbar-search\"")).contains("type=\"search\"");
    assertThat(html)
        .contains("data-testid=\"segment-period-upcoming\"")
        .contains("data-testid=\"segment-period-past\"")
        .contains("data-testid=\"segment-period-all\"")
        .contains("data-filter-chips");
    assertThat(tagWith(html, "data-testid=\"row-link\""))
        .contains("class=\"row-link\"")
        .contains("href=\"/operations/" + OPERATION_ID + "\"");
  }

  /**
   * The member list pins the overflow toggle in the head's action row, the toolbar search and the
   * row link.
   *
   * @throws Exception if the request fails
   */
  @Test
  void memberListPinsTheOverflowToggleAndRowLink() throws Exception {
    UserDto member =
        new UserDto(
            MEMBER_ID,
            "Alice",
            "Alice",
            "Alice",
            null,
            5,
            null,
            Set.of("ROLE_KRT_MEMBER"),
            Set.of(),
            null,
            false,
            false,
            true,
            null,
            List.of(),
            1L,
            null,
            Boolean.FALSE);
    when(backendApiClient.get(eq("/api/v1/users?sort=username,asc"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(member), 0, 20, 1L, 1, List.of("username,asc")));
    when(backendApiClient.get(eq("/api/v1/users/{id}/memberships"), anyTypeRef(), eq(MEMBER_ID)))
        .thenReturn(List.of());

    String html = render("/members", "ADMIN");

    assertThat(tagWith(pageActions(html), "data-testid=\"overflow-menu-toggle\""))
        .contains("aria-expanded=\"false\"");
    assertThat(tagWith(html, "data-testid=\"toolbar-search\"")).contains("id=\"member-search\"");
    assertThat(tagWith(html, "data-testid=\"row-link\""))
        .contains("href=\"/members/" + MEMBER_ID + "/edit?source=members\"");
  }

  /**
   * The bank dashboard's account filter is the page's toolbar search and keeps its own id.
   *
   * @throws Exception if the request fails
   */
  @Test
  void bankDashboardAccountFilterIsTheToolbarSearch() throws Exception {
    when(backendApiClient.get(eq("/api/v1/bank/dashboard"), eq(BankDashboardDto.class)))
        .thenReturn(
            new BankDashboardDto(
                true,
                List.of(
                    new BankDashboardAccountDto(
                        UUID.randomUUID(),
                        "KB-0001",
                        "Staffel IRIDIUM",
                        "ORG_UNIT",
                        "ACTIVE",
                        new BigDecimal("1000"),
                        BigDecimal.ZERO,
                        List.of(),
                        null,
                        null,
                        null)),
                null));
    when(backendApiClient.get(startsWith("/api/v1/bank/accounts"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(
                    new BankAccountDto(
                        UUID.randomUUID(),
                        "KB-0001",
                        "Staffel IRIDIUM",
                        "ORG_UNIT",
                        "ACTIVE",
                        null,
                        null,
                        new BigDecimal("1000"),
                        null,
                        null,
                        null,
                        0L,
                        Instant.parse("2026-01-01T00:00:00Z"))),
                0,
                500,
                1,
                1,
                List.of()));

    String html = render("/bank", "BANK_MANAGEMENT");

    assertThat(tagWith(html, "data-testid=\"toolbar-search\""))
        .contains("id=\"bank-acc-filter\"")
        .contains("data-bank-acc-filter");
  }
}
