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

package de.greluc.krt.profit.basetool.backend;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class MissionDataLeakTest {

  @Autowired private WebApplicationContext context;

  @Autowired private MissionRepository missionRepository;

  @Autowired private UserRepository userRepository;

  private MockMvc mockMvc;
  private Mission publicMission;
  private User testUser;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    testUser = new User();
    testUser.setId(UUID.randomUUID());
    testUser.setUsername("leaked_user");
    testUser.setEmail("secret@leaked.org");
    userRepository.save(testUser);

    publicMission = new Mission();
    publicMission.setName("Public Mission");
    publicMission.setStatus("PLANNED");
    publicMission.setIsInternal(false);
    publicMission.setDescription("secret briefing");

    MissionParticipant participant = new MissionParticipant();
    participant.setMission(publicMission);
    participant.setUser(testUser);
    publicMission.getParticipants().add(participant);

    missionRepository.save(publicMission);
  }

  @Test
  void testMissionDetail_Anonymous_SeesRosterWithoutPii() throws Exception {
    // Outsiders DO see the participant roster on a non-internal mission now, but PII (email / real
    // name) is always stripped — only the public callsign tuple survives.
    String body =
        mockMvc
            .perform(get("/api/v1/missions/" + publicMission.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.participants[0].user.username").value("leaked_user"))
            .andExpect(jsonPath("$.participants[0].user.email").value(nullValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("secret@leaked.org"), "outsider must never receive a participant email");
  }

  // A role-less GUEST is treated exactly like an anonymous visitor on the mission surface
  // (isMemberOrAbove() == false → same outsider redaction). That equivalence is proven without the
  // fragile full-stack synthetic-JWT path here: AuthHelperServiceTest pins GUEST →
  // isMemberOrAbove() == false, MissionControllerLifecycleTest pins isMemberOrAbove() == false →
  // the outsider redaction, and MissionFinanceEntryControllerSecurityTest pins the GUEST → 403
  // finance gate over the full security wiring.

  @Test
  void testMissionDetail_Anonymous_HidesDescriptionAndInternalEconomy() throws Exception {
    // On top of the PII stripping, an outsider sees neither the free-text description nor the
    // internal economy (inventory / refinery) nor the owner; the mission name stays visible.
    String body =
        mockMvc
            .perform(get("/api/v1/missions/" + publicMission.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Public Mission"))
            .andExpect(jsonPath("$.description").value(nullValue()))
            .andExpect(jsonPath("$.owner").value(nullValue()))
            // #1138: the mission economy is no longer embedded in the DTO — the fields are absent
            // from the payload entirely (served member-gated at their own endpoints), so a guest
            // can never see them here.
            .andExpect(jsonPath("$.refineryOrders").doesNotExist())
            .andExpect(jsonPath("$.inventoryEntries").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.junit.jupiter.api.Assertions.assertFalse(
        body.contains("secret briefing"), "outsider must not see the mission description");
  }
}
