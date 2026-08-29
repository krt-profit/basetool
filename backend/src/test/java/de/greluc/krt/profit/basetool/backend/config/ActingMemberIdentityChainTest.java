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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService;
import de.greluc.krt.profit.basetool.backend.service.CustomJwtGrantedAuthoritiesConverter;
import de.greluc.krt.profit.basetool.backend.service.RefineryImportService;
import de.greluc.krt.profit.basetool.backend.service.TermsAcceptanceService;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
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
 * The acting member <strong>reaches the handler</strong>, and both person-gates judge
 * <strong>them</strong> (ADR-0129).
 *
 * <p>Separate from {@link ActingMemberFilterChainTest}, which covers the refusals. That class
 * asserts only that things are refused — and a suite of refusals cannot notice that the success
 * path never worked. Two defects hid in exactly that blind spot, and they are why this class
 * exists:
 *
 * <ul>
 *   <li>{@code CurrentUserArgumentResolver} demanded a {@code JwtAuthenticationToken} and threw for
 *       the acting member, so every gateway call was refused during argument resolution, one layer
 *       past the gate it used to fail at. Fail-closed, invisible in a test that expects a refusal.
 *   <li>{@code TermsAcceptanceAccessFilter} made the same type check and failed <em>open</em>: "no
 *       user" means "let through" there, so the consent boundary (REQ-SEC-028) silently stopped
 *       applying to the one path it had just been extended to cover.
 * </ul>
 *
 * <p>The second needs the consent gate to be live, which the {@code test} profile stands down for
 * the whole suite. This class re-arms it for itself via {@code app.security.terms.armed-in-test} —
 * without that, the gate is a no-op stub and the fail-open regression is untestable by
 * construction.
 *
 * <p>{@link RefineryImportService} is mocked so the assertions are about identity rather than
 * master data: the extract below is schema-valid (it has to survive {@code @Valid}) but matches
 * nothing, and what is asserted is the {@code callerId} the controller passed down.
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

    // An approved, live member — the state both gates are supposed to let through. Seeded rather
    // than mocked because the liveness check and both gates read the real row.
    User member = new User();
    member.setId(MEMBER);
    member.setUsername("acting-member");
    member.setApprovalStatus(ApprovalStatus.ACTIVE);
    member.setInKeycloak(true);
    userRepository.saveAndFlush(member);

    when(refineryImportService.buildDraft(any(), any())).thenReturn(null);
    when(blueprintImportService.previewImport(any(), any())).thenReturn(null);
  }

  /**
   * The happy path: the member's identity survives to the handler.
   *
   * <p>The assertion that matters is the captured {@code callerId}, not the status. A 200 alone
   * would also be produced if the controller had defaulted to the gateway's own subject — which is
   * precisely the bug the first cut of ADR-0129 shipped, where attribution was right at two call
   * sites and wrong everywhere else. So this pins <em>whose</em> draft was built.
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
   * The second bound endpoint carries the identity too — and it binds {@code @CurrentUserSub}.
   *
   * <p>ADR-0129 bounds the header to two endpoints, and the test above covers only the
   * {@code @CurrentUserId} one. They take different branches of the argument resolver, so proving
   * one proves the shared lookup but not the String-typed binding a reviewer would have to take on
   * trust. The subject reaching the service is the member's, not the gateway's.
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
   * The gateway cannot record consent for itself, even where an {@code app_user} row exists for it.
   *
   * <p>Closes the one escalation the machine identity still carried. {@code /api/v1/terms/**} is
   * exempt from the consent gate — it must be, or nobody could ever accept — and the gateway is an
   * authenticated caller, so with a row for its own {@code sub} it could consent, clear the gate,
   * and reach every {@code isAuthenticated()}-only read behind {@code
   * anyRequest().authenticated()}. Production carries exactly such a row: the gateway's first call
   * ran the registration flow on itself before the machine-identity carve-out existed.
   *
   * <p>Asserted here rather than left to the row's manual removal, because that removal is a
   * per-environment step that can be forgotten — and a cleanup migration would risk aborting a
   * deploy on one of the many non-cascading foreign keys into {@code app_user}.
   */
  @Test
  void refusesToRecordConsentForTheGatewayItself() throws Exception {
    // The stray row, reproduced. Without it the insert would fail on the app_user foreign key and
    // the test would go red for a reason that has nothing to do with the guard - which is exactly
    // the false confidence to avoid, because production HAS this row.
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
   * The consent gate judges the acting member, and refuses when they have not accepted.
   *
   * <p>The regression test for the fail-open defect. Before the identity seam this returned
   * <strong>200</strong>: the gate looked for a {@code JwtAuthenticationToken}, found the acting
   * member's token-less authentication, concluded "no user" and waved the request through.
   *
   * <p>Asserted on the {@code code}, not on the status. Every filter in this chain answers 403, so
   * a status assertion passes even when the wrong gate fired — and here the distinction is the
   * whole point: {@code TERMS_NOT_ACCEPTED} proves the consent gate ran <em>and</em> that it
   * evaluated the member, because the gateway's own subject has no acceptance row either and would
   * have produced the same status from a different filter.
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

  /**
   * The approval gate judges the acting member too.
   *
   * <p>{@code PendingApprovalAccessFilter} reads {@code getAuthorities()} generically, so it
   * survived the identity swap where the consent gate did not. That asymmetry is exactly why it is
   * pinned here: the two gates sit next to each other and only one of them was ever exercised on
   * this path, which is what made the fail-open look fine.
   */
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

  /**
   * A member <em>deactivated</em> in Keycloak is refused, not only one that was deleted.
   *
   * <p>The gap V230 closed. The roster sync always fetched {@code enabled} from the Admin API and
   * dropped it, so deactivating a member refused them nothing here: with a token that is bounded by
   * expiry, but the gateway can <em>name</em> a subject, and a name does not expire. This is the
   * difference between revocation taking minutes and taking forever.
   *
   * <p>The refusal is byte-identical to the deleted case — only the log line separates them, which
   * is deliberate: the endpoint must not become an oracle for which subjects exist, let alone for
   * which of them are still active.
   */
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

    // Still present and still ACTIVE locally: the refusal follows the identity provider's verdict,
    // not anything this application changed about the row.
    assertThat(userRepository.findById(MEMBER)).isPresent();
  }

  /**
   * A member the last roster sync no longer found in Keycloak is refused.
   *
   * <p>The liveness bound, end to end. A named subject never expires the way a token does, so
   * without this an offboarded member's still-running extractor would keep writing indefinitely.
   * The refusal is the filter's own, and its body must stay indistinguishable from the
   * unknown-member case so the endpoint cannot be used to enumerate subjects.
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
