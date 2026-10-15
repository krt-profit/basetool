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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipRequestDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the admin member hangar (REQ-HANGAR-011): only an admin reaches the page and its
 * writes, the page lists the member's ships, and every write goes to the member's admin path.
 */
@SpringBootTest
class MemberHangarPageControllerTest {

  private static final UUID MEMBER = UUID.fromString("4e5f6a7b-8c9d-4e0f-8a1b-3c4d5e6f7a8b");
  private static final UUID SHIP = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
  private static final String BASE = "/admin/members/" + MEMBER + "/hangar";
  private static final String SHIPS_READ =
      "/api/v1/hangar/admin/users/{userId}/ships?page=0&size=50";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adminSeesTheMembersShipsUnderTheMembersName() throws Exception {
    ShipTypeDto type =
        new ShipTypeDto(
            UUID.randomUUID(),
            "Cutlass Black",
            new ManufacturerDto(UUID.randomUUID(), "Drake", "DRAK", null, null, null, false),
            "Cutlass",
            0,
            false);
    ShipDto ship = new ShipDto(SHIP, "Iron Mule", type, "LTI", null, true, null, null, 3L, 0L);
    when(backendApiClient.get(eq(SHIPS_READ), anyTypeRef(), eq(MEMBER)))
        .thenReturn(new PageResponse<>(List.of(ship), 0, 50, 1, 1, List.of()));
    when(backendApiClient.get(eq("/api/v1/users/{id}"), eq(UserDto.class), eq(MEMBER)))
        .thenReturn(member());

    mockMvc
        .perform(get(BASE))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar/member-hangar"))
        .andExpect(content().string(containsString("Pilot Alpha")))
        .andExpect(content().string(containsString("Iron Mule")))
        .andExpect(content().string(containsString("data-testid=\"member-hangar-add\"")))
        .andExpect(content().string(containsString("data-version=\"3\"")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void resultsFragmentRendersOnlyTheShipList() throws Exception {
    when(backendApiClient.get(eq(SHIPS_READ), anyTypeRef(), eq(MEMBER)))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    mockMvc
        .perform(get(BASE).param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar/member-hangar :: memberHangarResults"))
        .andExpect(content().string(not(containsString("<html"))));
  }

  @Test
  @WithMockUser
  void nonAdminIsRefusedThePageAndTheWrites() throws Exception {
    mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(BASE + "/" + SHIP + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isForbidden());

    verify(backendApiClient, never()).get(anyString(), anyTypeRef(), any());
    verify(backendApiClient, never()).delete(anyString(), eq(Void.class), any(), any());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void addPostsToTheMembersShipsWithThePickedUnit() throws Exception {
    UUID type = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    String body =
        "{\"name\":\"Kestrel\",\"shipTypeId\":\""
            + type
            + "\",\"insurance\":\"LTI\",\"fitted\":true,\"version\":7,\"owningOrgUnitId\":\""
            + unit
            + "\"}";

    mockMvc
        .perform(
            post(BASE + "/add")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());

    verify(backendApiClient)
        .post(
            eq("/api/v1/hangar/admin/users/{userId}/ships"),
            argThat(
                (ShipRequestDto r) ->
                    type.equals(r.shipTypeId())
                        && unit.equals(r.owningOrgUnitId())
                        && r.version() == null),
            eq(ShipDto.class),
            eq(MEMBER));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void addWithoutInsuranceIsRefusedBeforeTheBackend() throws Exception {
    mockMvc
        .perform(
            post(BASE + "/add")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shipTypeId\":\"" + UUID.randomUUID() + "\",\"insurance\":\" \"}"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION"));

    verify(backendApiClient, never()).post(anyString(), any(), eq(ShipDto.class), any());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void updateCarriesTheVersionAndNeverTheUnit() throws Exception {
    String body =
        "{\"name\":\"Kestrel\",\"shipTypeId\":\""
            + UUID.randomUUID()
            + "\",\"insurance\":\"0\",\"fitted\":false,\"version\":5,\"owningOrgUnitId\":\""
            + UUID.randomUUID()
            + "\"}";

    mockMvc
        .perform(
            post(BASE + "/" + SHIP + "/update")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());

    verify(backendApiClient)
        .put(
            eq("/api/v1/hangar/admin/users/{userId}/ships/{shipId}"),
            argThat(
                (ShipRequestDto r) ->
                    Long.valueOf(5L).equals(r.version()) && r.owningOrgUnitId() == null),
            eq(ShipDto.class),
            eq(MEMBER),
            eq(SHIP));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void updateConflictIsRelayedAsProblemJson() throws Exception {
    when(backendApiClient.put(
            eq("/api/v1/hangar/admin/users/{userId}/ships/{shipId}"),
            any(),
            eq(ShipDto.class),
            eq(MEMBER),
            eq(SHIP)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "OPTIMISTIC_LOCK", null, Collections.emptyList(), null));

    mockMvc
        .perform(
            post(BASE + "/" + SHIP + "/update")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"shipTypeId\":\""
                        + UUID.randomUUID()
                        + "\",\"insurance\":\"LTI\",\"fitted\":true,\"version\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteGoesToTheMembersShip() throws Exception {
    mockMvc
        .perform(
            post(BASE + "/" + SHIP + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isNoContent());

    verify(backendApiClient)
        .delete(
            eq("/api/v1/hangar/admin/users/{userId}/ships/{shipId}"),
            eq(Void.class),
            eq(MEMBER),
            eq(SHIP));
  }

  /**
   * A member record whose effective name the page head shows.
   *
   * @return the member
   */
  private static UserDto member() {
    return new UserDto(
        MEMBER,
        "pilot",
        "Pilot Alpha",
        "Pilot Alpha",
        null,
        1,
        null,
        Set.of(),
        Set.of(),
        null,
        false,
        false,
        true,
        null,
        null,
        0L,
        null,
        null);
  }
}
