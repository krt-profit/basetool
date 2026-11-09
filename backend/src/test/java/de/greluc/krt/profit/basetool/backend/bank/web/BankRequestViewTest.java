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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Request;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single booking request read behind the request's own page (REQ-BANK-068): its requester, the
 * bank staff who see its account and its approvers read it, with their roles on it; everyone else
 * gets the same {@code 404} as for an unknown request.
 */
@SpringBootTest
@Transactional
class BankRequestViewTest {

  private static final String REQUESTS = "/api/v1/bank/org-units/requests/";

  @Autowired private WebApplicationContext context;
  @Autowired private CustomJwtGrantedAuthoritiesConverter authorities;

  private final JsonMapper json = JsonMapper.builder().build();
  private MockMvc mockMvc;
  private BankAccessWorld world;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    world = new BankAccessSeeder(context).seed();
  }

  @Test
  void theRequesterReadsTheirOwnRequest() throws Exception {
    JsonNode view = ok(read(Actor.MEMBER, world.request(Request.APPROVE_OWN)));

    assertThat(view.path("request").path("id").asString())
        .isEqualTo(world.request(Request.APPROVE_OWN).toString());
    assertThat(view.path("own").asBoolean()).isTrue();
    assertThat(view.path("staff").asBoolean()).isFalse();
    assertThat(view.path("approver").asBoolean()).isFalse();
  }

  @Test
  void theStaffNoteReachesStaffAndApproversButNotTheRequester() throws Exception {
    UUID requestId = world.request(Request.APPROVE_OWN);
    BankBookingRequestRepository requests = context.getBean(BankBookingRequestRepository.class);
    BankBookingRequest request = requests.findById(requestId).orElseThrow();
    request.setStaffNote("internal remark");
    requests.saveAndFlush(request);

    JsonNode requester = ok(read(Actor.MEMBER, requestId));
    JsonNode approver = ok(read(Actor.STAFFELLEITER, requestId));
    JsonNode staff = ok(read(Actor.MANAGEMENT, requestId));

    assertThat(requester.path("request").path("staffNote").isNull()).isTrue();
    assertThat(approver.path("request").path("staffNote").asString()).isEqualTo("internal remark");
    assertThat(staff.path("request").path("staffNote").asString()).isEqualTo("internal remark");
  }

  @Test
  void aGrantedEmployeeReadsItAsStaffWithTheConfirmFact() throws Exception {
    JsonNode withdrawal =
        ok(read(Actor.EMPLOYEE_WITHDRAW, world.request(Request.CONFIRM_WITHDRAWAL_OWN)));
    JsonNode deposit =
        ok(read(Actor.EMPLOYEE_WITHDRAW, world.request(Request.CONFIRM_DEPOSIT_OWN)));

    assertThat(withdrawal.path("staff").asBoolean()).isTrue();
    assertThat(withdrawal.path("request").path("callerMayConfirm").asBoolean()).isTrue();
    assertThat(deposit.path("request").path("callerMayConfirm").asBoolean()).isFalse();
  }

  @Test
  void anApproverReadsTheRequestThatWaitsForThem() throws Exception {
    JsonNode view = ok(read(Actor.STAFFELLEITER, world.request(Request.APPROVE_OWN)));

    assertThat(view.path("approver").asBoolean()).isTrue();
    assertThat(view.path("staff").asBoolean()).isFalse();
    assertThat(view.path("request").path("callerMayConfirm").isNull()).isTrue();
  }

  @Test
  void anyoneElseAndAnUnknownRequestAnswerTheSameNotFound() throws Exception {
    assertThat(read(Actor.STAFFELLEITER, world.request(Request.APPROVE_SK)).getStatus())
        .isEqualTo(404);
    assertThat(read(Actor.EMPLOYEE_WITHDRAW, world.request(Request.APPROVE_SK)).getStatus())
        .isEqualTo(404);
    assertThat(read(Actor.MEMBER, UUID.randomUUID()).getStatus()).isEqualTo(404);
  }

  /**
   * Reads one request as an actor of the web.
   *
   * @param actor the actor
   * @param requestId the request
   * @return the response
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private MockHttpServletResponse read(@NotNull Actor actor, @NotNull UUID requestId)
      throws Exception {
    String subject = world.user(actor).toString();
    String username = world.username(actor);
    return mockMvc
        .perform(
            get(REQUESTS + requestId)
                .with(
                    jwt()
                        .jwt(token -> token.subject(subject).claim("preferred_username", username))
                        .authorities(authorities)))
        .andReturn()
        .getResponse();
  }

  /**
   * Asserts a success and parses it.
   *
   * @param response the response
   * @return the body
   * @throws Exception when the body is no JSON
   */
  @NotNull
  private JsonNode ok(@NotNull MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return json.readTree(response.getContentAsString());
  }
}
