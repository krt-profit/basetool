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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderAssigneeDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ReassignResponsibleOrgUnitRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.support.CapabilityStubs;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Exercises invalid responsibility recovery through the rendered HTTP detail route. */
@SpringBootTest
class JobOrderResponsibilityRecoveryMvcTest {

  @MockitoBean private BackendApiClient backendApiClient;
  @MockitoBean private LiveSyncLocalBus liveSyncLocalBus;
  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;
  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest
  @ValueSource(strings = {"OPEN", "COMPLETED", "REJECTED"})
  @WithMockUser(roles = {"KRT_MEMBER", "ADMIN"})
  void invalidResponsibilityPreservesReadsAndOffersOnlyValidTransfer(String status)
      throws Exception {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UserDto user =
        new UserDto(
            userId, "reader", "Reader", "Reader", null, null, null, Set.of(), Set.of(), null, false,
            false, true, null, List.of(), 0L, null, null);
    JobOrderDto order =
        new JobOrderDto(
            id,
            51,
            null,
            null,
            "Reader",
            "Preserved order history",
            1,
            status,
            "MATERIAL",
            true,
            List.of(),
            List.of(),
            List.of(),
            List.of(new JobOrderAssigneeDto(user, "Preserved own note", 1L)),
            List.of(),
            List.of(),
            Instant.parse("2026-11-01T10:00:00Z"),
            4L,
            false,
            false,
            true,
            true,
            true,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/{id}"), eq(JobOrderDto.class), eq(id)))
        .thenReturn(order);
    List<OrgUnitMembershipOptionDto> options =
        List.of(
            new OrgUnitMembershipOptionDto(
                UUID.randomUUID(), "Current Profit Bereich", "P", "BEREICH"),
            new OrgUnitMembershipOptionDto(
                UUID.randomUUID(), "Current Profit Staffel", "PS", "SQUADRON"),
            new OrgUnitMembershipOptionDto(
                UUID.randomUUID(), "Current Profit SK", "PSK", "SPECIAL_COMMAND"));
    when(backendApiClient.get(
            eq("/api/v1/org-units/profit-responsible"),
            org.mockito.ArgumentMatchers
                .<ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>>any()))
        .thenReturn(options);

    String html =
        mockMvc
            .perform(
                get("/orders/{id}", id)
                    .with(
                        oidcLogin()
                            .idToken(token -> token.subject(userId.toString()))
                            .authorities(
                                new org.springframework.security.core.authority
                                    .SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains(
            "Preserved order history",
            "Preserved own note",
            "data-testid=\"responsibility-invalid\"");
    var transfer =
        Pattern.compile("id=\"responsibility-transfer-form\"[^>]*>(.*?)</form>", Pattern.DOTALL)
            .matcher(html);
    assertThat(transfer.find()).isTrue();
    assertThat(transfer.group(1))
        .contains("Current Profit Bereich", "Current Profit Staffel", "Current Profit SK");
    assertThat(html)
        .doesNotContain(
            "id=\"status-select\"",
            "id=\"delete-order-form\"",
            "id=\"edit-order-form\"",
            "id=\"handover-form\"",
            "id=\"item-handover-form\"",
            "id=\"production-form\"",
            "data-trigger=\"oa-add-me\"",
            "data-trigger=\"oa-edit-note\"",
            "data-trigger=\"oa-remove-assignee\"");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @WithMockUser(roles = "KRT_MEMBER")
  void requesterEditControlsFollowExplicitBackendCapability(boolean mayEdit) throws Exception {
    UUID id = UUID.randomUUID();
    JobOrderDto order =
        new JobOrderDto(
            id,
            51,
            null,
            null,
            "Requester",
            null,
            1,
            "OPEN",
            "MATERIAL",
            true,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.parse("2026-11-01T10:00:00Z"),
            4L,
            false,
            false,
            false,
            true,
            false,
            mayEdit);
    when(backendApiClient.get(eq("/api/v1/orders/{id}"), eq(JobOrderDto.class), eq(id)))
        .thenReturn(order);

    String html =
        mockMvc
            .perform(get("/orders/{id}", id).with(oidcLogin()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    if (mayEdit) {
      assertThat(html)
          .contains("id=\"edit-order-form\"", "data-requester-view=\"true\"", "/requested-update")
          .doesNotContain("id=\"status-select\"");
    } else {
      assertThat(html).doesNotContain("id=\"edit-order-form\"", "data-modal-id=\"edit-modal\"");
    }
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void recoveryTransferDoesNotRequireTheOrdinaryEditCapability() throws Exception {
    UUID id = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    JobOrderDto recovered =
        new JobOrderDto(
            id,
            51,
            null,
            null,
            "Reader",
            null,
            1,
            "COMPLETED",
            "MATERIAL",
            true,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.parse("2026-11-01T10:00:00Z"),
            5L,
            false,
            false,
            false,
            true,
            true,
            false);
    when(backendApiClient.patch(
            eq("/api/v1/orders/{id}/responsible-org-unit"),
            eq(new ReassignResponsibleOrgUnitRequest(target)),
            eq(JobOrderDto.class),
            eq(id)))
        .thenReturn(recovered);

    mockMvc
        .perform(
            patch("/orders/{id}/responsible-org-unit", id)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"responsibleOrgUnitId\":\"" + target + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.displayId").value(51))
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.responsibilityInvalid").value(false))
        .andExpect(jsonPath("$.originalProfitBoundaryUnknown").value(true));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void requestingBereichRemainsSelectableInMaterialEditor() throws Exception {
    CapabilityStubs.logistician(backendApiClient);
    UUID id = UUID.randomUUID();
    UUID requesterId = UUID.randomUUID();
    JobOrderDto order =
        new JobOrderDto(
            id,
            51,
            null,
            new SquadronReferenceDto(requesterId, "Requesting Bereich", "RB"),
            "Reader",
            null,
            1,
            "OPEN",
            "MATERIAL",
            true,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.parse("2026-11-01T10:00:00Z"),
            4L,
            true,
            true,
            false,
            false,
            true,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/{id}"), eq(JobOrderDto.class), eq(id)))
        .thenReturn(order);
    when(backendApiClient.getCached(
            eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS),
            org.mockito.ArgumentMatchers
                .<ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>>any()))
        .thenReturn(
            List.of(
                new OrgUnitMembershipOptionDto(requesterId, "Requesting Bereich", "RB", "BEREICH"),
                new OrgUnitMembershipOptionDto(
                    UUID.randomUUID(), "Other SK", "OSK", "SPECIAL_COMMAND")));

    String html =
        mockMvc
            .perform(get("/orders/{id}", id))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var select =
        Pattern.compile(
                "<select[^>]*id=\"requestingOrgUnitId\"[^>]*>(.*?)</select>", Pattern.DOTALL)
            .matcher(html);
    assertThat(select.find()).isTrue();
    assertThat(select.group(1)).contains("Requesting Bereich", "Other SK");
  }
}
