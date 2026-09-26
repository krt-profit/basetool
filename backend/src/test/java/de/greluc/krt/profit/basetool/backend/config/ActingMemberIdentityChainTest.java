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

package de.greluc.krt.profit.basetool.backend.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService;
import de.greluc.krt.profit.basetool.backend.service.CustomJwtGrantedAuthoritiesConverter;
import de.greluc.krt.profit.basetool.backend.service.RefineryImportService;
import de.greluc.krt.profit.basetool.backend.service.TermsAcceptanceService;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests that the acting member reaches the handler and that both person gates (approval and
 * consent) judge the member, not the gateway (ADR-0129).
 *
 * <p>Re-arms the consent gate via {@code app.security.terms.armed-in-test}. {@link
 * RefineryImportService} is mocked, and the assertions target the {@code callerId} passed down.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(
    properties = {
      "app.security.ingest-gateway.client-ids=test-ingest-gateway",
      "app.security.terms.armed-in-test=true"
    })
class ActingMemberIdentityChainTest {

  private static final String INGEST_PATH = "/api/v1/refinery-orders/import-extract";
  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";

  /** Schema-valid and deliberately unmatchable: this test is about who, not about what. */
  private static final String EXTRACT =
      """
      {
        "schemaVersion": 1,
        "tool": "basetool-sc-extractor",
        "toolVersion": "1.0.0",
        "model": "test",
        "generatedAt": "2026-06-05T20:00:00Z",
        "clientLanguage": "en",
        "orders": [
          {
            "panelType": "SETUP",
            "quoted": true,
            "layoutConfidence": 0.92,
            "sourceImages": [
              {
                "name": "panel.png",
                "width": 1920,
                "height": 1080,
                "cropMode": "vlm",
                "capturedAt": "2026-06-05T19:59:00Z"
              }
            ],
            "goods": [
              {
                "rowIndex": 0,
                "rawMaterialName": "Quantainium",
                "quality": 618,
                "inputQuantity": 957,
                "outputQuantity": 448,
                "refine": true,
                "confidence": 0.9
              }
            ]
          }
        ]
      }
      """;

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private TermsAcceptanceService termsAcceptanceService;

  @MockitoBean private RefineryImportService refineryImportService;
  @MockitoBean private BlueprintImportService blueprintImportService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();

    User member = new User();
    member.setId(MEMBER);
    member.setUsername("acting-member");
    member.setApprovalStatus(ApprovalStatus.ACTIVE);
    member.setInKeycloak(true);
    member.setRoles(
        new java.util.HashSet<>(
            java.util.Set.of(
                roleRepository
                    .findByCode(Roles.KRT_MEMBER)
                    .orElseThrow(() -> new IllegalStateException("KRT_MEMBER role not seeded")))));
    userRepository.saveAndFlush(member);

    when(refineryImportService.buildDraft(any(), any())).thenReturn(null);
    when(blueprintImportService.previewImport(any(), any())).thenReturn(null);
  }

  /**
   * The member's identity, not the gateway's, reaches the handler as the draft's {@code callerId}.
   */
  @Test
  void buildsTheDraftForTheActingMemberNotTheGateway() throws Exception {
    termsAcceptanceService.acceptCurrentTerms(MEMBER);

    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(EXTRACT))
        .andExpect(status().isOk());

    verify(refineryImportService).buildDraft(any(), eq(MEMBER));
  }

  /**
   * The acting-member path refuses a role-less member with {@code NO_ROLE}, like the bearer path
   * (REQ-SEC-053).
   */
  @Test
  void refusesAnActingMemberWhoHoldsNoRole() throws Exception {
    User roleLess = userRepository.findById(MEMBER).orElseThrow();
    roleLess.setRoles(new java.util.HashSet<>());
    userRepository.saveAndFlush(roleLess);
    termsAcceptanceService.acceptCurrentTerms(MEMBER);

    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(EXTRACT))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NO_ROLE"));

    verify(refineryImportService, never()).buildDraft(any(), any());
  }

  /**
   * The blueprint-preview endpoint, the second bound endpoint, also receives the member's identity.
   */
  @Test
  void carriesTheActingMemberToTheBlueprintPreviewEndpointToo() throws Exception {
    termsAcceptanceService.acceptCurrentTerms(MEMBER);

    mockMvc
        .perform(
            multipart("/api/v1/personal-blueprints/import/preview")
                .file(
                    new MockMultipartFile(
                        "file", "bp.json", "application/json", "{}".getBytes(UTF_8)))
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString()))
        .andExpect(status().isOk());

    verify(blueprintImportService).previewImport(eq(MEMBER), any());
  }

  /**
   * The gateway cannot record consent for itself, even when an {@code app_user} row exists for its
   * subject.
   */
  @Test
  void refusesToRecordConsentForTheGatewayItself() throws Exception {
    User strayGatewayRow = new User();
    strayGatewayRow.setId(UUID.fromString(GATEWAY));
    strayGatewayRow.setUsername("service-account-test-ingest-gateway");
    strayGatewayRow.setApprovalStatus(ApprovalStatus.PENDING);
    userRepository.saveAndFlush(strayGatewayRow);

    mockMvc
        .perform(
            post("/api/v1/terms/acceptance")
                .with(
                    jwt()
                        .jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway"))
                        .authorities(
                            new SimpleGrantedAuthority(
                                CustomJwtGrantedAuthoritiesConverter.GATEWAY_AUTHORITY))))
        .andExpect(status().isForbidden());

    assertThat(termsAcceptanceService.hasAcceptedCurrentTerms(UUID.fromString(GATEWAY))).isFalse();
  }

  /**
   * A member still records consent through the same endpoint.
   *
   * <p>The complement: a guard that refused everyone would also close the escalation, and would
   * lock every member out of the tool permanently.
   */
  @Test
  void stillLetsAMemberRecordConsent() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/terms/acceptance")
                .with(
                    jwt()
                        .jwt(token -> token.subject(MEMBER.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());

    assertThat(termsAcceptanceService.hasAcceptedCurrentTerms(MEMBER)).isTrue();
  }

  /**
   * The consent gate judges the acting member and refuses with {@code TERMS_NOT_ACCEPTED} when they
   * have not accepted; asserted on the code because every gate in the chain answers 403.
   */
  @Test
  void refusesAnActingMemberWhoHasNotAcceptedTheTerms() throws Exception {
    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(EXTRACT))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TERMS_NOT_ACCEPTED"));

    verify(refineryImportService, org.mockito.Mockito.never()).buildDraft(any(), any());
  }

  /** The approval gate refuses an acting member whose registration is still pending. */
  @Test
  void refusesAnActingMemberWhoseRegistrationIsStillPending() throws Exception {
    User member = userRepository.findById(MEMBER).orElseThrow();
    member.setApprovalStatus(ApprovalStatus.PENDING);
    userRepository.saveAndFlush(member);
    termsAcceptanceService.acceptCurrentTerms(MEMBER);

    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(EXTRACT))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
  }

  /** A member disabled in Keycloak is refused with the same response as a deleted one. */
  @Test
  void refusesAMemberWhoseKeycloakAccountIsDisabled() throws Exception {
    User member = userRepository.findById(MEMBER).orElseThrow();
    member.setEnabledInKeycloak(false);
    userRepository.saveAndFlush(member);
    termsAcceptanceService.acceptCurrentTerms(MEMBER);

    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(EXTRACT))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ActingMemberFilter.CODE_ACTING_MEMBER_REFUSED));

    assertThat(userRepository.findById(MEMBER)).isPresent();
  }

  /**
   * A member missing from the last Keycloak roster sync is refused, with a response
   * indistinguishable from the unknown-member case.
   */
  @Test
  void refusesAMemberTheIdentityProviderNoLongerHas() throws Exception {
    User member = userRepository.findById(MEMBER).orElseThrow();
    member.setInKeycloak(false);
    userRepository.saveAndFlush(member);
    termsAcceptanceService.acceptCurrentTerms(MEMBER);

    mockMvc
        .perform(
            post(INGEST_PATH)
                .with(
                    jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(EXTRACT))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(ActingMemberFilter.CODE_ACTING_MEMBER_REFUSED));

    assertThat(userRepository.findById(MEMBER)).isPresent();
  }
}
