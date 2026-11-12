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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.joborder.model.CreateAuecJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Verifies creation enters the created order's common workspace through every form transport. */
class JobOrderCreationWorkspaceMvcTest extends JobOrderPageMvcTestBase {

  private static final UUID CREATED_ID = UUID.fromString("00000000-0000-0000-0000-000000000053");
  private static final CreateAuecJobOrderDto AUEC_FORM =
      new CreateAuecJobOrderDto(
          UUID.fromString("00000000-0000-0000-0000-000000000051"),
          UUID.fromString("00000000-0000-0000-0000-000000000052"),
          "Creator",
          "Submitted comment",
          "9007199254740993");

  @Autowired private WebApplicationContext context;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  @WithMockUser
  void successfulCreationUsesReturnedIdentityForItsCentralDetail(boolean item, boolean ajax)
      throws Exception {
    JobOrderDto created = createdOrder(item ? "ITEM" : "MATERIAL");
    when(backendApiClient.post(
            eq(item ? "/api/v1/orders/items" : "/api/v1/orders"), any(), eq(JobOrderDto.class)))
        .thenReturn(created);
    MockHttpServletRequestBuilder request =
        post(item ? "/orders/items" : "/orders/create")
            .with(csrf())
            .param("responsibleOrgUnitId", UUID.randomUUID().toString())
            .param("requestingOrgUnitId", UUID.randomUUID().toString())
            .param("handle", "Creator")
            .param("source", "inventory");
    if (item) {
      request
          .param("items[0].gameItemId", UUID.randomUUID().toString())
          .param("items[0].blueprintId", UUID.randomUUID().toString())
          .param("items[0].amount", "2");
    } else {
      request
          .param("materials[0].materialId", UUID.randomUUID().toString())
          .param("materials[0].amount", "2");
    }
    if (ajax) {
      mvc.perform(request.header("X-Requested-With", "XMLHttpRequest"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.targetUrl").value("/orders/" + CREATED_ID));
    } else {
      mvc.perform(request)
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/orders/" + CREATED_ID));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @WithMockUser
  void successfulAuecCreationUsesExactTargetAndServerIdentity(boolean ajax) throws Exception {
    when(backendApiClient.post("/api/v1/orders/auec", AUEC_FORM, JobOrderDto.class))
        .thenReturn(createdOrder("AUEC"));

    var response = mvc.perform(auecRequest(ajax, AUEC_FORM));
    if (ajax) {
      response
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.targetUrl").value("/orders/" + CREATED_ID))
          .andExpect(jsonPath("$.id").doesNotExist());
    } else {
      response
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/orders/" + CREATED_ID))
          .andExpect(flash().attribute("successToast", "success.joborder.create"));
    }
    verify(liveSyncLocalBus).publish("orders", List.of("queue"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @WithMockUser
  void refusedAuecCreationReturnsFormOrBackendProblem(boolean ajax) throws Exception {
    when(backendApiClient.post("/api/v1/orders/auec", AUEC_FORM, JobOrderDto.class))
        .thenThrow(
            new BackendServiceException(
                "Denied", null, 403, "ACCESS_DENIED", null, List.of(), "Writer required."));

    var response = mvc.perform(auecRequest(ajax, AUEC_FORM));
    if (ajax) {
      response
          .andExpect(status().isForbidden())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
          .andExpect(jsonPath("$.detail").value("Writer required."));
    } else {
      response
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/orders/create"))
          .andExpect(flash().attribute("errorToast", "orders.money.create.error"))
          .andExpect(flash().attribute("startInAuecMode", true))
          .andExpect(flash().attribute("auecTarget", AUEC_FORM.target()));
    }
    verifyNoInteractions(liveSyncLocalBus);
  }

  @Test
  @WithMockUser
  void invalidNativeAuecCreationKeepsTheFormWithoutWriting() throws Exception {
    var invalid =
        new CreateAuecJobOrderDto(
            AUEC_FORM.responsibleOrgUnitId(),
            AUEC_FORM.requestingOrgUnitId(),
            "",
            AUEC_FORM.comment(),
            AUEC_FORM.target());

    mvc.perform(auecRequest(false, invalid))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/orders/create"))
        .andExpect(flash().attribute("errorToast", "orders.money.create.error"))
        .andExpect(flash().attribute("startInAuecMode", true))
        .andExpect(flash().attribute("auecTarget", AUEC_FORM.target()));
    verify(backendApiClient, never()).post(eq("/api/v1/orders/auec"), any(), eq(JobOrderDto.class));
    verifyNoInteractions(liveSyncLocalBus);
  }

  @Test
  @WithMockUser
  void nativeAuecCreationPropagatesWrappedSessionLoss() throws Exception {
    var expired = new ReauthenticationRequiredException("OAuth2 session expired", null);
    when(backendApiClient.post("/api/v1/orders/auec", AUEC_FORM, JobOrderDto.class))
        .thenThrow(new BackendServiceException("Backend authentication failed", expired, 401));

    mvc.perform(auecRequest(false, AUEC_FORM))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(ReauthenticationRequiredException.REAUTH_PATH))
        .andExpect(flash().attributeCount(0));
    verifyNoInteractions(liveSyncLocalBus);
  }

  private static RequestBuilder auecRequest(boolean ajax, CreateAuecJobOrderDto form) {
    Map<String, String> fields =
        Map.of(
            "responsibleOrgUnitId", form.responsibleOrgUnitId().toString(),
            "requestingOrgUnitId", form.requestingOrgUnitId().toString(),
            "handle", form.handle(),
            "comment", form.comment(),
            "target", form.target());
    if (ajax) {
      MockMultipartHttpServletRequestBuilder request = multipart("/orders/auec");
      fields.forEach((name, value) ->
          request.part(new MockPart(name, value.getBytes(StandardCharsets.UTF_8))));
      return request.with(csrf()).header("X-Requested-With", "XMLHttpRequest");
    }
    MockHttpServletRequestBuilder request =
        post("/orders/auec").contentType(MediaType.APPLICATION_FORM_URLENCODED).with(csrf());
    fields.forEach(request::param);
    return request;
  }

  private static JobOrderDto createdOrder(String type) {
    return new JobOrderDto(
        CREATED_ID,
        53,
        null,
        null,
        "Creator",
        null,
        null,
        "OPEN",
        type,
        false,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.parse("2026-11-09T10:00:00Z"),
        0L,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        0L,
        0L);
  }
}
