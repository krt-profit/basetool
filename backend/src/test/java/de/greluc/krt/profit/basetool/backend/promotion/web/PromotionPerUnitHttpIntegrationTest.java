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

package de.greluc.krt.profit.basetool.backend.promotion.web;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.promotion.internal.PromotionCategory;
import de.greluc.krt.profit.basetool.backend.promotion.internal.PromotionCategoryRepository;
import de.greluc.krt.profit.basetool.backend.promotion.internal.PromotionTopic;
import de.greluc.krt.profit.basetool.backend.promotion.internal.PromotionTopicRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Walks the promotion endpoints over HTTP across a Staffel with promotion enabled and one with it
 * disabled: officer reads, writes and the capability flags count only the enabled unit, a pin on
 * the disabled unit leaves nothing, and the admin is not narrowed (REQ-PROMO-004, REQ-SEC-015).
 */
@SpringBootTest
class PromotionPerUnitHttpIntegrationTest {

  private static final String TOPICS = "/api/v1/promotion/topics";
  private static final String MEMBERS = "/api/v1/promotion/evaluations/members";
  private static final String EVALUATE = "/api/v1/promotion/evaluations/user/";
  private static final String CAPABILITIES = "/api/v1/me/capabilities";
  private static final String PIN = "X-Active-Org-Unit-Id";

  @Autowired private WebApplicationContext context;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;
  @Autowired private PromotionTopicRepository topicRepository;
  @Autowired private PromotionCategoryRepository categoryRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private MockMvc mockMvc;
  private String tag;
  private UUID enabledUnitId;
  private UUID disabledUnitId;
  private UUID categoryInEnabled;
  private UUID categoryInDisabled;
  private UUID bothUnitsOfficerId;
  private UUID disabledOnlyOfficerId;
  private UUID disabledOnlyMemberId;
  private UUID enabledMemberId;
  private UUID adminId;

  @BeforeEach
  void seed() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    transactionTemplate.executeWithoutResult(_ -> {
      tag = UUID.randomUUID().toString().substring(0, 8);
      Squadron enabled = newSquadron("Promo-A-" + tag, "PA" + tag, true);
      Squadron disabled = newSquadron("Promo-B-" + tag, "PB" + tag, false);
      enabledUnitId = enabled.getId();
      disabledUnitId = disabled.getId();
      categoryInEnabled = newCategory(enabled, "topic-a-" + tag).getId();
      categoryInDisabled = newCategory(disabled, "topic-b-" + tag).getId();
      User bothUnitsOfficer = newUser("promo-both-" + tag);
      bothUnitsOfficerId = bothUnitsOfficer.getId();
      join(bothUnitsOfficer, enabled, MembershipRole.STAFFELLEITER);
      join(bothUnitsOfficer, disabled, MembershipRole.STAFFELLEITER);
      User disabledOnlyOfficer = newUser("promo-off-" + tag);
      disabledOnlyOfficerId = disabledOnlyOfficer.getId();
      join(disabledOnlyOfficer, disabled, MembershipRole.ENSIGN);
      User disabledOnlyMember = newUser("promo-plain-" + tag);
      disabledOnlyMemberId = disabledOnlyMember.getId();
      join(disabledOnlyMember, disabled, MembershipRole.MEMBER);
      User enabledMember = newUser("promo-member-" + tag);
      enabledMemberId = enabledMember.getId();
      join(enabledMember, enabled, MembershipRole.MEMBER);
      adminId = newUser("promo-admin-" + tag).getId();
    });
  }

  @Test
  void anOfficerOfBothUnitsReadsTheManagementTopicsOfTheEnabledUnitOnly() throws Exception {
    as(get(TOPICS).param("view", "MANAGEMENT").param("size", "200"), bothUnitsOfficerId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].name", hasItem("topic-a-" + tag)))
        .andExpect(jsonPath("$.content[*].name", not(hasItem("topic-b-" + tag))));
  }

  @Test
  void aPinOnTheDisabledUnitLeavesNothingToManage() throws Exception {
    as(
            get(TOPICS)
                .param("view", "MANAGEMENT")
                .param("size", "200")
                .header(PIN, disabledUnitId.toString()),
            bothUnitsOfficerId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", empty()));
  }

  @Test
  void aPinOnTheEnabledUnitKeepsItsTopics() throws Exception {
    as(
            get(TOPICS)
                .param("view", "MANAGEMENT")
                .param("size", "200")
                .header(PIN, enabledUnitId.toString()),
            bothUnitsOfficerId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].name", hasItem("topic-a-" + tag)));
  }

  @Test
  void anOfficerOnlyInTheDisabledUnitManagesNothing() throws Exception {
    as(get(TOPICS).param("view", "MANAGEMENT").param("size", "200"), disabledOnlyOfficerId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", empty()));
    as(get(MEMBERS).param("size", "200"), disabledOnlyOfficerId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", empty()));
  }

  @Test
  void theEvaluatableMembersAreThoseOfTheEnabledUnitOnly() throws Exception {
    as(get(MEMBERS).param("size", "200"), bothUnitsOfficerId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].username", hasItem("promo-member-" + tag)))
        .andExpect(jsonPath("$.content[*].username", not(hasItem("promo-plain-" + tag))));
  }

  @Test
  void anEvaluationInTheEnabledUnitIsSavedAndOneInTheDisabledUnitRefused() throws Exception {
    String body = "{\"assignedLevel\":\"LEVEL_B\"}";
    asJson(put(EVALUATE + enabledMemberId + "/category/" + categoryInEnabled).content(body))
        .andExpect(status().isOk());
    asJson(put(EVALUATE + enabledMemberId + "/category/" + categoryInDisabled).content(body))
        .andExpect(status().isForbidden());
  }

  @Test
  void anEvaluationOfAMemberOfOnlyTheDisabledUnitIsRefused() throws Exception {
    asJson(
            put(EVALUATE + disabledOnlyMemberId + "/category/" + categoryInEnabled)
                .content("{\"assignedLevel\":\"LEVEL_B\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void theCapabilitiesFollowTheEnabledUnitsInReach() throws Exception {
    as(get(CAPABILITIES), bothUnitsOfficerId)
        .andExpect(jsonPath("$.canManagePromotions").value(true))
        .andExpect(jsonPath("$.canManagePromotionsAnywhere").value(true))
        .andExpect(jsonPath("$.canUsePromotionAnywhere").value(true));
    as(get(CAPABILITIES).header(PIN, disabledUnitId.toString()), bothUnitsOfficerId)
        .andExpect(jsonPath("$.canManagePromotions").value(false))
        .andExpect(jsonPath("$.canOpenLeitung").value(true))
        .andExpect(jsonPath("$.canManagePromotionsAnywhere").value(true))
        .andExpect(jsonPath("$.canUsePromotionAnywhere").value(true));
  }

  @Test
  void withoutAnEnabledUnitInReachThePromotionCapabilitiesAreOffForGood() throws Exception {
    as(get(CAPABILITIES), disabledOnlyOfficerId)
        .andExpect(jsonPath("$.canActAsOfficerAnywhere").value(true))
        .andExpect(jsonPath("$.canOpenLeitung").value(true))
        .andExpect(jsonPath("$.canManagePromotions").value(false))
        .andExpect(jsonPath("$.canManagePromotionsAnywhere").value(false))
        .andExpect(jsonPath("$.canUsePromotionAnywhere").value(false));
    as(get(CAPABILITIES), disabledOnlyMemberId)
        .andExpect(jsonPath("$.canManagePromotionsAnywhere").value(false))
        .andExpect(jsonPath("$.canUsePromotionAnywhere").value(false));
    as(get(CAPABILITIES), enabledMemberId)
        .andExpect(jsonPath("$.canManagePromotionsAnywhere").value(false))
        .andExpect(jsonPath("$.canUsePromotionAnywhere").value(true));
  }

  @Test
  void theAdminIsNotNarrowed() throws Exception {
    mockMvc
        .perform(
            get(TOPICS)
                .param("view", "MANAGEMENT")
                .param("size", "200")
                .with(
                    jwt()
                        .jwt(token -> token.subject(adminId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].name", hasItem("topic-a-" + tag)))
        .andExpect(jsonPath("$.content[*].name", hasItem("topic-b-" + tag)));
  }

  private ResultActions as(MockHttpServletRequestBuilder request, UUID userId) throws Exception {
    return mockMvc.perform(
        request
            .with(
                jwt()
                    .jwt(token -> token.subject(userId.toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
            .accept(MediaType.APPLICATION_JSON));
  }

  private ResultActions asJson(MockHttpServletRequestBuilder request) throws Exception {
    return as(request.contentType(MediaType.APPLICATION_JSON), bothUnitsOfficerId);
  }

  private Squadron newSquadron(String name, String shorthand, boolean promotionEnabled) {
    Squadron squadron = new Squadron();
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    squadron.setPromotionEnabled(promotionEnabled);
    return squadronRepository.save(squadron);
  }

  private PromotionCategory newCategory(Squadron owner, String topicName) {
    PromotionTopic topic =
        topicRepository.save(
            PromotionTopic.builder().name(topicName).sortOrder(1).owningSquadron(owner).build());
    return categoryRepository.save(
        PromotionCategory.builder()
            .topic(topic)
            .name("category-" + topicName)
            .sortOrder(1)
            .build());
  }

  private User newUser(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }

  private void join(User user, Squadron unit, MembershipRole role) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setKind(OrgUnitKind.SQUADRON);
    membership.setRole(role);
    membership.setJoinedAt(Instant.now());
    membershipRepository.save(membership);
  }
}
