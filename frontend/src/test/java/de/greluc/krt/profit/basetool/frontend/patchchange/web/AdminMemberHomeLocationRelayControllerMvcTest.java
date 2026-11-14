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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.ChooseHomeLocationRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationOptionDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.RelocationResultDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test of {@link AdminMemberHomeLocationRelayController} (REQ-PATCH-022): the admin's choice on
 * a member's behalf reaches the backend as a typed request for that member, and a refusal comes
 * back as a problem with the backend's status and localised detail.
 */
@SpringBootTest
class AdminMemberHomeLocationRelayControllerMvcTest {

  private static final UUID MEMBER = UUID.fromString("3e3be3be-0000-4000-8000-000000000007");
  private static final String PAGE = "/admin/members/" + MEMBER + "/home-location";
  private static final String CHOICE = "/api/v1/patch-changes/admin/members/{userId}/home-location";

  private static final HomeLocationOptionDto LORVILLE =
      new HomeLocationOptionDto(
          UUID.fromString("10c4710c-0000-4000-8000-000000000001"), "Lorville", "Stanton");

  private static final String CHOICE_JSON = "{\"locationId\":\"" + LORVILLE.id() + "\"}";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * A backend refusal as the backend client raises it.
   *
   * @param status the HTTP status
   * @param code the problem code
   * @param detail the backend's localised detail
   * @return the failure
   */
  private static BackendServiceException refusal(int status, String code, String detail) {
    return new BackendServiceException("refused", null, status, code, null, List.of(), detail);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theChoiceIsRelayedForTheMemberAndAnswersWhatMoved() throws Exception {
    when(backendApiClient.post(
            CHOICE,
            new ChooseHomeLocationRequest(LORVILLE.id()),
            RelocationResultDto.class,
            MEMBER))
        .thenReturn(new RelocationResultDto(LORVILLE, 12, 1));

    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHOICE_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.location.name").value("Lorville"))
        .andExpect(jsonPath("$.rows").value(12))
        .andExpect(jsonPath("$.ships").value(1));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aMemberWhoAlreadyChoseComesBackAsTheBackendsConflict() throws Exception {
    when(backendApiClient.post(eq(CHOICE), any(), eq(RelocationResultDto.class), eq(MEMBER)))
        .thenThrow(
            refusal(
                409,
                "BUSINESS_CONFLICT",
                "Die Home Location wurde für diesen Patchwechsel bereits gewählt."));

    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHOICE_JSON))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
        .andExpect(
            jsonPath("$.detail")
                .value("Die Home Location wurde für diesen Patchwechsel bereits gewählt."));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aLocationNotOnOfferComesBackAsABadRequest() throws Exception {
    when(backendApiClient.post(eq(CHOICE), any(), eq(RelocationResultDto.class), eq(MEMBER)))
        .thenThrow(refusal(400, "BAD_REQUEST", "Diese Location ist keine wählbare Home Location."));

    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHOICE_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Diese Location ist keine wählbare Home Location."));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMemberCannotChooseForAnotherMember() throws Exception {
    mockMvc
        .perform(
            post(PAGE)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHOICE_JSON))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never())
        .post(anyString(), any(), eq(RelocationResultDto.class), any(Object[].class));
  }
}
