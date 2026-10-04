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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies over HTTP that {@code GET /api/v1/orders?toProcess=true} lists only the orders the
 * caller's processing unit is responsible for, and only narrows the scoped queue (REQ-ORDERS-040).
 */
@SpringBootTest
class JobOrderToProcessMvcTest {

  @Autowired private WebApplicationContext context;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private MockMvc mockMvc;
  private UUID squadronAId;
  private UUID orderRespA;
  private UUID orderRespB;
  private UUID orderRespSk;

  @BeforeEach
  void seed() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    transactionTemplate.executeWithoutResult(
        _ -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);
          Squadron sqA = newSquadron("Proc-A-" + tag, "PA" + tag);
          Squadron sqB = newSquadron("Proc-B-" + tag, "PB" + tag);
          SpecialCommand sk = new SpecialCommand();
          sk.setName("Proc-SK-" + tag);
          sk.setShorthand("PS" + tag);
          sk.setProfitEligible(true);
          specialCommandRepository.save(sk);
          squadronAId = sqA.getId();
          orderRespA = newOrder(sqA, sqB).getId();
          orderRespB = newOrder(sqB, sqA).getId();
          orderRespSk = newOrder(sk, sqA).getId();
        });
  }

  @Test
  void pinnedToSquadronA_withoutToProcess_listsItsQueueAndThePublicSkOrders() throws Exception {
    List<String> ids = listIds(squadronAId, false);

    assertThat(ids).contains(orderRespA.toString(), orderRespSk.toString());
    assertThat(ids).doesNotContain(orderRespB.toString());
  }

  @Test
  void pinnedToSquadronA_toProcess_listsOnlyTheOrdersSquadronAProcesses() throws Exception {
    List<String> ids = listIds(squadronAId, true);

    assertThat(ids).contains(orderRespA.toString());
    assertThat(ids).doesNotContain(orderRespB.toString(), orderRespSk.toString());
  }

  @Test
  void unpinnedAdminWithoutMemberships_toProcess_listsNoneOfTheSeededOrders() throws Exception {
    List<String> ids = listIds(null, true);

    assertThat(ids)
        .doesNotContain(orderRespA.toString(), orderRespB.toString(), orderRespSk.toString());
  }

  /**
   * Reads the newest order ids an admin sees, optionally pinned to one org unit.
   *
   * @param pinnedOrgUnitId the org unit sent as the active-unit pin, or {@code null} for none
   * @param toProcess the value of the {@code toProcess} query parameter
   * @return the ids of the listed orders
   * @throws Exception if the request fails
   */
  private @NotNull List<String> listIds(@Nullable UUID pinnedOrgUnitId, boolean toProcess)
      throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/v1/orders")
            .param("toProcess", Boolean.toString(toProcess))
            .param("size", "1000")
            .param("sort", "createdAt,desc")
            .with(
                jwt()
                    .jwt(token -> token.subject(UUID.randomUUID().toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
            .accept(MediaType.APPLICATION_JSON);
    if (pinnedOrgUnitId != null) {
      request =
          request.header(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER, pinnedOrgUnitId.toString());
    }
    String body =
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.content[*].id");
  }

  private Squadron newSquadron(String name, String shorthand) {
    Squadron s = new Squadron();
    s.setName(name);
    s.setShorthand(shorthand);
    s.setProfitEligible(true);
    return squadronRepository.save(s);
  }

  private JobOrder newOrder(OrgUnit responsible, OrgUnit requesting) {
    JobOrder o =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(requesting)
            .handle("to-process-test")
            .status(JobOrderStatus.OPEN)
            .build();
    return jobOrderRepository.save(o);
  }
}
