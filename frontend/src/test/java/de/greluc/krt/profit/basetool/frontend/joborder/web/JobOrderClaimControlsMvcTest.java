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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ClaimDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.support.CapabilityStubs;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Renders claim controls through the public detail route using backend-provided authority. */
@SpringBootTest
class JobOrderClaimControlsMvcTest {

  private static final UUID ORDER = UUID.randomUUID();
  private static final UUID OWN_SQUADRON = UUID.randomUUID();
  private static final UUID FOREIGN_SQUADRON = UUID.randomUUID();
  private static final UUID OWN_CLAIM = UUID.randomUUID();
  private static final UUID FOREIGN_CLAIM = UUID.randomUUID();
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>> CLAIM_OPTIONS =
      new ParameterizedTypeReference<>() {};

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    CapabilityStubs.report(
        backendApiClient,
        new CapabilitiesResponse(
            false, true, false, false, true, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void eligibleForeignSquadronWriterCanAddAndEditOwnClaimWithoutProcessingControls()
      throws Exception {
    stub(order("MATERIAL", "OPEN", false, false), List.of(ownOption()));
    String html = html(null);

    assertThat(html)
        .contains("data-trigger=\"claim-add\"", "data-claim-id=\"" + OWN_CLAIM + "\"")
        .doesNotContain("data-claim-id=\"" + FOREIGN_CLAIM + "\"", "id=\"status-select\"")
        .contains("value=\"" + OWN_SQUADRON + "\"")
        .doesNotContain("value=\"" + FOREIGN_SQUADRON + "\"");
  }

  @ParameterizedTest
  @CsvSource({"ITEM,", "MATERIAL,materials", "ITEM,aggregated"})
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void ownWriterClaimControlsUseTheSameAuthorityOnItemPagesAndLiveFragments(
      String type, String fragment) throws Exception {
    stub(order(type, "OPEN", false, false), List.of(ownOption()));

    assertThat(html(fragment))
        .contains("data-trigger=\"claim-add\"", "data-claim-id=\"" + OWN_CLAIM + "\"")
        .doesNotContain("data-claim-id=\"" + FOREIGN_CLAIM + "\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"MATERIAL", "ITEM"})
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void responsibleWriterMayManageEveryReturnedClaimingSquadron(String type) throws Exception {
    stub(order(type, "OPEN", false, true), List.of(ownOption(), foreignOption()));

    assertThat(html(null))
        .contains(
            "data-trigger=\"claim-add\"",
            "data-claim-id=\"" + OWN_CLAIM + "\"",
            "data-claim-id=\"" + FOREIGN_CLAIM + "\"",
            "value=\"" + OWN_SQUADRON + "\"",
            "value=\"" + FOREIGN_SQUADRON + "\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"MATERIAL", "ITEM"})
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void liveDialogOffersOnlyTheReturnedClaimingSquadrons(String type) throws Exception {
    stub(order(type, "OPEN", false, false), List.of(ownOption()));

    assertThat(html("dialogs"))
        .contains("id=\"claim-modal\"", "value=\"" + OWN_SQUADRON + "\"")
        .doesNotContain("value=\"" + FOREIGN_SQUADRON + "\"");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void ordinaryReaderHasNoClaimControlsOrClaimDialog() throws Exception {
    CapabilityStubs.report(backendApiClient, CapabilitiesResponse.NONE);
    stub(order("MATERIAL", "OPEN", false, false), List.of());

    assertNoClaimControls(html(null));
  }

  @ParameterizedTest
  @CsvSource({
    "MATERIAL,OPEN,true",
    "ITEM,OPEN,true",
    "MATERIAL,COMPLETED,false",
    "ITEM,COMPLETED,false",
    "MATERIAL,REJECTED,false",
    "ITEM,REJECTED,false"
  })
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void invalidAndTerminalOrdersHaveNoClaimControlsDespiteProcessingAuthority(
      String type, String status, boolean invalid) throws Exception {
    stub(order(type, status, invalid, true), List.of());

    assertNoClaimControls(html(null));
    assertNoClaimControls(html("MATERIAL".equals(type) ? "materials" : "aggregated"));
    assertNoClaimControls(html("dialogs"));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void unavailableClaimingAuthorityDoesNotFallBackToProcessingRights() throws Exception {
    stub(order("MATERIAL", "OPEN", false, true), List.of());
    doThrow(new BackendServiceException("unavailable", null, 503))
        .when(backendApiClient)
        .get(eq("/api/v1/orders/{id}/claims/claiming-options"), eq(CLAIM_OPTIONS), eq(ORDER));

    assertNoClaimControls(html(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"MATERIAL", "ITEM"})
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void anInvalidRenderedOrderCannotOfferClaimsFromALaterAuthoritySnapshot(String type)
      throws Exception {
    stub(order(type, "OPEN", true, true), List.of(ownOption()));

    assertNoClaimControls(html(null));
    assertNoClaimControls(html("MATERIAL".equals(type) ? "materials" : "aggregated"));
  }

  private void stub(JobOrderDto order, List<OrgUnitMembershipOptionDto> options) {
    doReturn(order).when(backendApiClient).get("/api/v1/orders/{id}", JobOrderDto.class, ORDER);
    doReturn(options)
        .when(backendApiClient)
        .get(eq("/api/v1/orders/{id}/claims/claiming-options"), eq(CLAIM_OPTIONS), eq(ORDER));
  }

  private String html(String fragment) throws Exception {
    MockHttpServletRequestBuilder request = get("/orders/" + ORDER);
    if (fragment != null) {
      request.param("fragment", fragment);
    }
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static void assertNoClaimControls(String html) {
    assertThat(html)
        .doesNotContain(
            "data-trigger=\"claim-add\"", "data-trigger=\"claim-edit\"", "id=\"claim-modal\"");
  }

  private static OrgUnitMembershipOptionDto ownOption() {
    return new OrgUnitMembershipOptionDto(OWN_SQUADRON, "Own Squadron", "OWN", "SQUADRON");
  }

  private static OrgUnitMembershipOptionDto foreignOption() {
    return new OrgUnitMembershipOptionDto(
        FOREIGN_SQUADRON, "Foreign Squadron", "OTHER", "SQUADRON");
  }

  private static JobOrderDto order(String type, String status, boolean invalid, boolean canEdit) {
    MaterialDto material =
        new MaterialDto(
            UUID.randomUUID(),
            "Iron",
            null,
            "SCU",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            null,
            true,
            0L);
    List<ClaimDto> claims =
        List.of(
            new ClaimDto(
                OWN_CLAIM,
                new SquadronReferenceDto(OWN_SQUADRON, "Own Squadron", "OWN"),
                2.0,
                null,
                Instant.now(),
                1L),
            new ClaimDto(
                FOREIGN_CLAIM,
                new SquadronReferenceDto(FOREIGN_SQUADRON, "Foreign Squadron", "OTHER"),
                3.0,
                null,
                Instant.now(),
                1L));
    return new JobOrderDto(
        ORDER,
        7,
        new SquadronReferenceDto(UUID.randomUUID(), "Responsible SK", "SK"),
        null,
        "Handle",
        null,
        1,
        status,
        type,
        true,
        "MATERIAL".equals(type)
            ? List.of(
                new JobOrderMaterialDto(
                    UUID.randomUUID(), material, null, null, 10.0, 0.0, claims, 5.0, 1L))
            : List.of(),
        List.of(),
        "ITEM".equals(type)
            ? List.of(new AggregatedMaterialDto(material, "GOOD", null, 10.0, 0.0, claims, 5.0))
            : List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.now(),
        1L,
        canEdit,
        false,
        false,
        invalid,
        false,
        false,
        false);
  }
}
