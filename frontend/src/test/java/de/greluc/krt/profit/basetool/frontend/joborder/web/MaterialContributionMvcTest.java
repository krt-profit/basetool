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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialContributionChange;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialProvisionCreditDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialProvisionEvidence;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Exercises the contribution history fragment through its public browser route. */
@SpringBootTest
class MaterialContributionMvcTest {
  private static final UUID ORDER = UUID.fromString("caa631f2-c5ed-41b3-8ad9-8b458e967d93");
  @Autowired private WebApplicationContext context;
  @MockitoBean private BackendApiClient backend;
  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void readableEmptyHistoryHasNoCorrectionControls() throws Exception {
    doReturn(List.of())
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/history"),
            org.mockito.ArgumentMatchers.<ParameterizedTypeReference<List<Object>>>any(),
            eq(ORDER));
    String html =
        mvc.perform(
                get("/orders/{id}/claims/history", ORDER)
                    .header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains("data-contribution-history", "data-contribution-empty")
        .doesNotContain("data-contribution-correction");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void endedHistoryKeepsCreditAndReductionDistinctWithoutWriterControls() throws Exception {
    var contribution =
        new MaterialContributionHistoryDto(
            ORDER,
            7L,
            ORDER,
            false,
            new SquadronReferenceDto(ORDER, "Staffel A", "A"),
            ORDER,
            "Torite",
            "SCU",
            "BASE",
            0,
            0d,
            3d,
            Instant.parse("2026-11-01T10:00:00Z"),
            List.of(
                new MaterialContributionChange(
                    MaterialContributionChange.Reason.CAPACITY_REDUCED,
                    4d,
                    1d,
                    0d,
                    Instant.parse("2026-11-01T09:00:00Z"),
                    null)),
            List.of(
                new MaterialProvisionCreditDto(
                    ORDER,
                    2L,
                    3d,
                    3d,
                    Instant.parse("2026-11-01T08:00:00Z"),
                    false,
                    MaterialProvisionEvidence.OBSERVED)));
    doReturn(List.of(contribution))
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/history"),
            org.mockito.ArgumentMatchers
                .<ParameterizedTypeReference<List<MaterialContributionHistoryDto>>>any(),
            eq(ORDER));
    String html =
        mvc.perform(
                get("/orders/{id}/claims/history", ORDER)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains(
            "Torite",
            "Staffel A",
            "data-contribution-ended",
            "Capacity reduced",
            "data-open-amount=\"0.0\"",
            "data-credited-amount=\"3.0\"")
        .doesNotContain("data-contribution-correction");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void deniedConcreteOrderReadReturnsNoHistory() throws Exception {
    org.mockito.Mockito.doThrow(new BackendServiceException("denied", null, 403))
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/history"),
            org.mockito.ArgumentMatchers
                .<ParameterizedTypeReference<List<MaterialContributionHistoryDto>>>any(),
            eq(ORDER));
    String body =
        mvc.perform(
                get("/orders/{id}/claims/history", ORDER)
                    .header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("data-contribution-history", "data-contribution-correction");
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void staleCorrectionReturnsConflictWithoutDiscardingTheSubmittedFacts() throws Exception {
    org.mockito.Mockito.doThrow(new BackendServiceException("stale", null, 409))
        .when(backend)
        .post(
            eq("/api/v1/orders/{id}/claims/provisions/{creditId}/correction"),
            any(),
            eq(MaterialContributionHistoryDto[].class),
            eq(ORDER),
            eq(ORDER));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/orders/{id}/claims/provisions/{creditId}/correction", ORDER, ORDER)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType("application/json")
                .content(
                    "{\"kind\":\"ATTRIBUTION\",\"version\":2,\"claimVersion\":7,\"amount\":0,\"targetClaimId\":null,\"targetClaimVersion\":null,\"stockVersions\":[],\"targetStockRowId\":null}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void historicalEditorUsesAuthorizedEndedTargetsWithTheirObservedVersions() throws Exception {
    UUID target = UUID.fromString("a1a631f2-c5ed-41b3-8ad9-8b458e967d93");
    var contribution =
        new MaterialContributionHistoryDto(
            ORDER,
            7L,
            ORDER,
            false,
            new SquadronReferenceDto(ORDER, "Staffel A", "A"),
            ORDER,
            "Torite",
            "SCU",
            "BASE",
            0,
            0d,
            3d,
            Instant.parse("2026-11-01T10:00:00Z"),
            List.of(),
            List.of(
                new MaterialProvisionCreditDto(
                    ORDER,
                    2L,
                    3d,
                    3d,
                    Instant.parse("2026-11-01T08:00:00Z"),
                    true,
                    MaterialProvisionEvidence.OBSERVED)));
    doReturn(List.of(contribution))
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/history"),
            org.mockito.ArgumentMatchers
                .<ParameterizedTypeReference<List<MaterialContributionHistoryDto>>>any(),
            eq(ORDER));
    var allowed =
        new de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialAttributionTargetDto(
            target, 12L, new SquadronReferenceDto(target, "Ended Staffel B", "B"), true, 0d, 3d);
    doReturn(List.of(allowed))
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/provisions/{creditId}/attribution-options"),
            org.mockito.ArgumentMatchers.<ParameterizedTypeReference<List<Object>>>any(),
            eq(ORDER),
            eq(ORDER));
    String html =
        mvc.perform(
                get("/orders/{id}/claims/provisions/{creditId}/editor", ORDER, ORDER)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains(
            "data-contribution-correction",
            "Ended Staffel B",
            "data-version=\"12\"",
            "data-claim-version=\"7\"",
            "data-credit-version=\"2\"",
            "data-ended=\"true\"");
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void freshProvisionChooserOffersOnlyWritableMatchingOpenPromises() throws Exception {
    UUID foreign = UUID.fromString("b1a631f2-c5ed-41b3-8ad9-8b458e967d93");
    var material =
        new de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto(
            ORDER, "Torite", null, "SCU", null, null, null, null, null, null, null, true, null,
            true, 0L);
    var own =
        new de.greluc.krt.profit.basetool.frontend.joborder.model.ClaimDto(
            ORDER,
            new SquadronReferenceDto(ORDER, "Permitted Staffel", "A"),
            3d,
            null,
            Instant.now(),
            6L);
    var other =
        new de.greluc.krt.profit.basetool.frontend.joborder.model.ClaimDto(
            foreign,
            new SquadronReferenceDto(foreign, "Foreign Staffel", "B"),
            7d,
            null,
            Instant.now(),
            9L);
    var bucket =
        new de.greluc.krt.profit.basetool.frontend.joborder.model.ClaimBucketDto(
            material, "BASE", null, 10d, 10d, 0d, List.of(own, other));
    doReturn(List.of(bucket))
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims"),
            org.mockito.ArgumentMatchers.<ParameterizedTypeReference<List<Object>>>any(),
            eq(ORDER));
    doReturn(
            List.of(
                new de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto(
                    ORDER, "Permitted Staffel", "A", "SQUADRON")))
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/claiming-options"),
            org.mockito.ArgumentMatchers.<ParameterizedTypeReference<List<Object>>>any(),
            eq(ORDER));
    String html =
        mvc.perform(
                get("/orders/{id}/claims/provision-options", ORDER)
                    .param("materialId", ORDER.toString())
                    .param("quality", "100")
                    .header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains("Permitted Staffel", "data-provision-version=\"6\"")
        .doesNotContain("Foreign Staffel", foreign.toString());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void historicalReaderCannotOpenAWriterCorrectionEditor() throws Exception {
    var contribution =
        new MaterialContributionHistoryDto(
            ORDER,
            7L,
            ORDER,
            false,
            new SquadronReferenceDto(ORDER, "Staffel A", "A"),
            ORDER,
            "Torite",
            "SCU",
            "BASE",
            0,
            0d,
            3d,
            Instant.parse("2026-11-01T10:00:00Z"),
            List.of(),
            List.of(
                new MaterialProvisionCreditDto(
                    ORDER,
                    2L,
                    3d,
                    3d,
                    Instant.parse("2026-11-01T08:00:00Z"),
                    false,
                    MaterialProvisionEvidence.OBSERVED)));
    doReturn(List.of(contribution))
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/history"),
            org.mockito.ArgumentMatchers
                .<ParameterizedTypeReference<List<MaterialContributionHistoryDto>>>any(),
            eq(ORDER));
    String body =
        mvc.perform(
                get("/orders/{id}/claims/provisions/{creditId}/editor", ORDER, ORDER)
                    .header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("data-contribution-correction");
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void physicalCorrectionChoicesIdentifyThePermittedStockAndItsQuality() throws Exception {
    var row =
        new de.greluc.krt.profit.basetool.frontend.inventory.model
            .InventoryQuantityCorrectionOptionDto(
            ORDER, 9L, "Permitted location", "Permitted holder", "Permitted pool", false, 6d, 750);
    doReturn(List.of(row))
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/provisions/{creditId}/correction-options"),
            org.mockito.ArgumentMatchers.<ParameterizedTypeReference<List<Object>>>any(),
            eq(ORDER),
            eq(ORDER));
    String html =
        mvc.perform(
                get("/orders/{id}/claims/provisions/{creditId}/quantity-options", ORDER, ORDER)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains(
            "Permitted location",
            "Permitted holder",
            "Permitted pool",
            "Minimum quality: 750",
            "data-version=\"9\"");
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void creditedSubsetKeepsUnknownPhysicalFactsHonestAndOffersOnlyAttributionCorrection()
      throws Exception {
    var contribution =
        new MaterialContributionHistoryDto(
            ORDER,
            7L,
            ORDER,
            false,
            new SquadronReferenceDto(ORDER, "Staffel A", "A"),
            ORDER,
            "Torite",
            "SCU",
            "BASE",
            0,
            0d,
            3d,
            Instant.parse("2026-11-01T10:00:00Z"),
            List.of(),
            List.of(
                new MaterialProvisionCreditDto(
                    ORDER,
                    2L,
                    3d,
                    3d,
                    Instant.parse("2026-11-01T08:00:00Z"),
                    true,
                    MaterialProvisionEvidence.CREDITED_SUBSET)));
    doReturn(List.of(contribution))
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/history"),
            org.mockito.ArgumentMatchers
                .<ParameterizedTypeReference<List<MaterialContributionHistoryDto>>>any(),
            eq(ORDER));
    doReturn(List.of())
        .when(backend)
        .get(
            eq("/api/v1/orders/{id}/claims/provisions/{creditId}/attribution-options"),
            org.mockito.ArgumentMatchers.<ParameterizedTypeReference<List<Object>>>any(),
            eq(ORDER),
            eq(ORDER));
    String history =
        mvc.perform(
                get("/orders/{id}/claims/history", ORDER)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(history)
        .contains(
            "Known credited portion", "original total quantity and physical quality are unknown")
        .doesNotContain("Original physical quantity");
    String editor =
        mvc.perform(
                get("/orders/{id}/claims/provisions/{creditId}/editor", ORDER, ORDER)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .param("lang", "en"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(editor)
        .contains("data-contribution-correction", "value=\"ATTRIBUTION\"")
        .doesNotContain("value=\"QUANTITY\"", "data-correction-stock");
  }
}
