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

package de.greluc.krt.profit.basetool.frontend.refinery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryGoodDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderPart;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderUpdateRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The edit form writes only the parts it changed and echoes both section counters
 * (REQ-REFINERY-029): the page script's change flags decide the parts, a flag it did not report
 * counts as changed, and an edit that changed nothing reaches no backend.
 */
@SpringBootTest
class RefineryOrderChangedPartsTest {

  private static final UUID ORDER_ID = UUID.randomUUID();

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void aFinanceOnlyEditNamesTheFinancesAndEchoesBothCounters() throws Exception {
    mockMvc
        .perform(
            edit()
                .param("gameDataChanged", "false")
                .param("financesChanged", "true")
                .param("missionChanged", "false"))
        .andExpect(status().is3xxRedirection());

    RefineryOrderUpdateRequest sent = sentRequest();
    assertThat(sent.parts()).containsExactly(RefineryOrderPart.FINANCES);
    assertThat(sent.gameDataVersion()).isEqualTo(4L);
    assertThat(sent.financesVersion()).isEqualTo(7L);
  }

  @Test
  void aGoodsRowKeepsTheIdOfTheStoredGoodItEditsAndAnAddedRowSendsNone() throws Exception {
    UUID heldGood = UUID.randomUUID();
    mockMvc
        .perform(
            edit()
                .param("goods[0].id", heldGood.toString())
                .param("goods[1].id", "")
                .param("goods[1].inputMaterialId", UUID.randomUUID().toString())
                .param("goods[1].outputQuantity", "10"))
        .andExpect(status().is3xxRedirection());

    assertThat(sentRequest().goods())
        .extracting(RefineryGoodDto::id)
        .containsExactly(heldGood, null);
  }

  @Test
  void aTimeAndMissionEditNamesTheGameDataAndTheMissionLink() throws Exception {
    mockMvc
        .perform(
            edit()
                .param("gameDataChanged", "true")
                .param("financesChanged", "false")
                .param("missionChanged", "true"))
        .andExpect(status().is3xxRedirection());

    assertThat(sentRequest().parts())
        .containsExactlyInAnyOrder(RefineryOrderPart.GAME_DATA, RefineryOrderPart.MISSION_LINK);
  }

  @Test
  void anEditWithoutChangeFlagsWritesEveryPart() throws Exception {
    mockMvc.perform(edit()).andExpect(status().is3xxRedirection());

    assertThat(sentRequest().parts())
        .containsExactlyInAnyOrder(
            RefineryOrderPart.GAME_DATA,
            RefineryOrderPart.FINANCES,
            RefineryOrderPart.MISSION_LINK);
  }

  @Test
  void anEditThatChangedNothingReachesNoBackend() throws Exception {
    mockMvc
        .perform(
            edit()
                .header("X-Requested-With", "XMLHttpRequest")
                .param("gameDataChanged", "false")
                .param("financesChanged", "false")
                .param("missionChanged", "false"))
        .andExpect(status().isOk());

    verify(backendApiClient, never())
        .put(eq("/api/v1/refinery-orders/{id}"), any(), eq(RefineryOrderDto.class), eq(ORDER_ID));
  }

  private MockHttpServletRequestBuilder edit() {
    return post("/refinery-orders/" + ORDER_ID)
        .param("durationHours", "1")
        .param("durationMinutes", "0")
        .param("expenses", "200")
        .param("gameDataVersion", "4")
        .param("financesVersion", "7")
        .param("locationId", UUID.randomUUID().toString())
        .param("startedAt", "2026-11-08T12:00")
        .param("goods[0].inputMaterialId", UUID.randomUUID().toString())
        .param("goods[0].inputQuantity", "100")
        .param("goods[0].outputQuantity", "90")
        .with(csrf())
        .with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
  }

  private RefineryOrderUpdateRequest sentRequest() {
    ArgumentCaptor<RefineryOrderUpdateRequest> captor =
        ArgumentCaptor.forClass(RefineryOrderUpdateRequest.class);
    verify(backendApiClient)
        .put(
            eq("/api/v1/refinery-orders/{id}"),
            captor.capture(),
            eq(RefineryOrderDto.class),
            eq(ORDER_ID));
    return captor.getValue();
  }
}
