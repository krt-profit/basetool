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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.AuditEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Proves the five data-subject-rights endpoints really do write their audit row with a real {@link
 * de.greluc.krt.profit.basetool.backend.service.AuditService} wired in (REQ-SEC-058, REQ-SEC-060,
 * REQ-AUDIT-001).
 *
 * <p><b>Why this exists next to the MockMvc gate matrices.</b> {@code AuditService.record} is
 * {@code MANDATORY}-propagated: calling it with no transaction in progress is a programming error
 * and throws. Every other audited call site sits inside a business transaction and satisfies that
 * for free — but these five audit a <em>read</em>, and a read hands its transaction back before the
 * controller gets to the audit call. {@link DataExportControllerSecurityTest} and {@link
 * AdminPersonSearchControllerSecurityTest} cannot see the difference, because they declare
 * {@code @MockitoBean AuditService}: replacing the bean also removes the transactional proxy around
 * it, so the propagation is never exercised and those tests stay green against an endpoint that
 * would 500 in production.
 *
 * <p><b>The four export requests also carry the frontend's real {@code Accept} header</b>, and that
 * is a second defect this class is the only place to catch. The shared {@code webClient} bean sends
 * {@code application/cbor, application/json} under the default {@code APP_HTTP_CODEC=CBOR} ({@code
 * WebClientConfig#backendAcceptTypes}), and both download endpoints hand their bytes straight to
 * the member's browser. A {@code produces} condition on the PDF mappings answered 406 before the
 * handler ran, and the JSON export -- returning a POJO -- negotiated its way to CBOR and was served
 * as {@code datenauskunft.json}, a binary blob no JSON tool opens. Both are invisible to a test
 * that sends no {@code Accept} header, and invisible to {@code DataExportProxyControllerTest}
 * because that builds a bare {@code WebClient} without the bean's default headers. The person
 * search is deliberately left to negotiate: its response is decoded by the frontend, never handed
 * to a member as a file.
 *
 * <p><b>This class must never become {@code @Transactional}</b>, and neither must its test methods.
 * A test-managed transaction is exactly the ambient transaction whose absence is the defect, and it
 * would make the class pass against broken code. The seeding goes through an explicit {@link
 * TransactionTemplate} for the same reason.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataSubjectRightsAuditIntegrationTest {

  /** Clears {@code PersonSearchService.MIN_TERM_LENGTH}, and distinctive enough to own its hits. */
  private static final String SEARCH_TERM = "ZzzAuditProbeZzz";

  /**
   * Exactly what the frontend's shared {@code webClient} bean sends under the default {@code
   * APP_HTTP_CODEC=CBOR}. Hardcoded rather than read from the frontend module, which the backend
   * does not depend on; {@code WebClientCborNegotiationTest} pins the other end of the pair.
   */
  private static final MediaType[] FRONTEND_ACCEPT = {
    MediaType.APPLICATION_CBOR, MediaType.APPLICATION_JSON
  };

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  @MockitoBean private JwtDecoder jwtDecoder;

  private final Set<UUID> seededUsers = new HashSet<>();

  private MockMvc mockMvc;
  private UUID actor;

  /** Builds the MockMvc stack and commits the actor the audit rows will hang off. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Committed, because audit_event.actor_user_id carries an FK to app_user: an uncommitted actor
    // would fail the insert for a reason unrelated to the property under test.
    actor = user("ZzzAuditActorZzz");
  }

  /** Removes what this class committed into the container the whole suite shares. */
  @AfterEach
  void cleanUp() {
    transactionTemplate.executeWithoutResult(
        status -> {
          auditEventRepository.deleteAll(rowsOfThisActor());
          userRepository.deleteAllById(seededUsers);
          seededUsers.clear();
        });
  }

  // covers REQ-SEC-058 - the member's own JSON export is served and audited
  @Test
  void selfExportJson_isServedAndAudited() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me/export").with(member(actor)).accept(FRONTEND_ACCEPT))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

    assertThat(auditRows(AuditEventType.PERSONAL_DATA_EXPORTED))
        .as("one export row, recorded by the subject themselves")
        .hasSize(1)
        .allSatisfy(
            event -> {
              assertThat(event.getTargetUserId()).isEqualTo(actor);
              assertThat(event.getDetails()).contains("format=json").contains("bySelf=true");
            });
  }

  // covers REQ-SEC-058 - the member's own PDF export is served and audited
  @Test
  void selfExportPdf_isServedAndAudited() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me/export/pdf").with(member(actor)).accept(FRONTEND_ACCEPT))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));

    assertThat(auditRows(AuditEventType.PERSONAL_DATA_EXPORTED))
        .hasSize(1)
        .allSatisfy(
            event -> {
              assertThat(event.getDetails()).contains("format=pdf").contains("bySelf=true");
              // Not rows=-1. The renderer used to assemble the export itself and return only the
              // bytes, so both PDF endpoints recorded a sentinel where the count belongs -- in the
              // one payload that answers "how much was disclosed".
              assertThat(event.getDetails()).doesNotContain("rows=-1");
            });
  }

  // covers REQ-SEC-058 - the admin JSON export is served and audited as somebody else's
  @Test
  void adminExportJson_isServedAndAudited() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/users/{id}/export", actor)
                .with(admin(actor))
                .accept(FRONTEND_ACCEPT))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

    assertThat(auditRows(AuditEventType.PERSONAL_DATA_EXPORTED))
        .as("bySelf=false is the distinction the trail exists to make")
        .hasSize(1)
        .allSatisfy(
            event ->
                assertThat(event.getDetails()).contains("format=json").contains("bySelf=false"));
  }

  // covers REQ-SEC-058 - the admin PDF export is served and audited
  @Test
  void adminExportPdf_isServedAndAudited() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/users/{id}/export/pdf", actor)
                .with(admin(actor))
                .accept(FRONTEND_ACCEPT))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));

    assertThat(auditRows(AuditEventType.PERSONAL_DATA_EXPORTED))
        .hasSize(1)
        .allSatisfy(
            event -> {
              assertThat(event.getDetails()).contains("format=pdf").contains("bySelf=false");
              // See selfExportPdf_isServedAndAudited: the admin path carried the same sentinel.
              assertThat(event.getDetails()).doesNotContain("rows=-1");
            });
  }

  // covers REQ-SEC-060 - the person search is served and audited without recording the term
  @Test
  void personSearch_isServedAndAudited() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/person-search").param("q", SEARCH_TERM).with(admin(actor)))
        .andExpect(status().isOk());

    assertThat(auditRows(AuditEventType.PERSON_SEARCH_PERFORMED))
        .hasSize(1)
        .allSatisfy(
            event -> {
              assertThat(event.getDetails()).contains("termLength=" + SEARCH_TERM.length());
              assertThat(event.getDetails())
                  .as("the term is somebody's name and must not reach the trail")
                  .doesNotContain(SEARCH_TERM);
            });
  }

  /**
   * The rows this test's actor produced, of one event type.
   *
   * @param type the event type to keep
   * @return the matching rows, narrowed to this actor because the whole suite shares one container
   */
  private List<AuditEvent> auditRows(AuditEventType type) {
    return rowsOfThisActor().stream().filter(event -> type == event.getEventType()).toList();
  }

  /**
   * Every audit row that names this test's actor, whether as the actor or as the subject.
   *
   * @return the rows, for filtering by the assertions and for wholesale removal by the cleanup
   */
  private List<AuditEvent> rowsOfThisActor() {
    return auditEventRepository.findAll().stream()
        .filter(
            event -> actor.equals(event.getActorUserId()) || actor.equals(event.getTargetUserId()))
        .toList();
  }

  /**
   * A plain member's token whose subject is the given user.
   *
   * @param subject the user id that is also the JWT {@code sub}
   * @return the request post-processor
   */
  private static RequestPostProcessor member(UUID subject) {
    return jwt()
        .jwt(claims -> claims.subject(subject.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  /**
   * An admin's token whose subject is the given user.
   *
   * @param subject the user id that is also the JWT {@code sub}
   * @return the request post-processor
   */
  private static RequestPostProcessor admin(UUID subject) {
    return jwt()
        .jwt(claims -> claims.subject(subject.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  /**
   * Commits a member so the FK on {@code audit_event.actor_user_id} resolves.
   *
   * @param username the handle, which is also the effective name
   * @return the new user's id
   */
  private UUID user(String username) {
    UUID id = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          User user = new User();
          user.setId(id);
          user.setUsername(username);
          userRepository.save(user);
        });
    seededUsers.add(id);
    return id;
  }
}
