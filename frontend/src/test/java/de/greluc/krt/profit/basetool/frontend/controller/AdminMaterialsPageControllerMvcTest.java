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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Regression for the Thymeleaf 3.1 JS-inline truncation bug on {@code /admin/materials}.
 *
 * <p>Pre-fix, the template called {@code /*[[${materials.![name]}]]*&#47;} inside a {@code
 * th:inline="javascript"} script. That inline expression truncated the rest of the script body, so
 * the {@code data-krt-confirm} form delete-confirmation handler binding, the {@code
 * 'admin-materials-update'} delegated event registration, and the row-update {@code fetch} flow
 * never executed. The fix moves the names into a sibling {@code <datalist id="materialNames-data">}
 * read at runtime, while keeping {@code th:inline="javascript"} alive on the script tag so the
 * remaining single-primitive translation lookups (toast keys) still resolve.
 *
 * <p>This test pins both halves: (a) the {@code 'admin-materials-update'} delegated binding string
 * (which lives at the very tail of the script) is in the rendered HTML, and (b) the response ends
 * with the closing {@code </html>} tag — the pre-fix bug truncated the body before that.
 */
@SpringBootTest
class AdminMaterialsPageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Asserts the {@code 'admin-materials-update'} delegated-event registration (defined AFTER the
   * datalist in the script) appears in the rendered HTML — proof that the Thymeleaf truncation does
   * not strike again.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listMaterials_ShouldRenderUpdateBinding_AfterDatalist() throws Exception {
    MaterialDto material =
        new MaterialDto(
            UUID.randomUUID(),
            "Aluminum",
            "RAW",
            "SCU",
            "Aluminum description",
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    PageResponse<MaterialDto> materialsPage =
        new PageResponse<>(List.of(material), 0, 1000, 1, 1, Collections.emptyList());

    when(backendApiClient.get(
            eq("/api/v1/materials?size=1000&sort=name,asc&includeHidden=true"), anyTypeRef()))
        .thenReturn(materialsPage);
    when(backendApiClient.get(eq("/api/v1/material-categories"), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/admin/materials"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/materials"))
        .andExpect(content().string(containsString("id=\"materialNames-data\"")))
        .andExpect(content().string(containsString("value=\"Aluminum\"")))
        .andExpect(content().string(containsString("'admin-materials-update'")))
        .andExpect(content().string(containsString("</html>")));
  }

  // covers #582 — the category-create twin (X-Requested-With + JSON body) relays to the backend and
  // returns the created MaterialCategoryDto so the page appends it without reloading.
  @Test
  @WithMockUser(roles = "ADMIN")
  void createCategoryAjax_withHeader_returns200WithCategory() throws Exception {
    when(backendApiClient.post(
            contains("/material-categories"), any(), eq(MaterialCategoryDto.class)))
        .thenReturn(new MaterialCategoryDto(UUID.randomUUID(), "X", 0L));

    mockMvc
        .perform(
            post("/admin/materials/categories")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\"}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("X")));
  }

  // covers #582 — a blank category name is rejected with 400 before any backend call.
  @Test
  @WithMockUser(roles = "ADMIN")
  void createCategoryAjax_withHeaderBlankName_returns400() throws Exception {
    mockMvc
        .perform(
            post("/admin/materials/categories")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  // covers #582 — the category-delete twin (X-Requested-With) returns 200 so the page removes the
  // category row in place rather than reloading.
  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteCategoryAjax_withHeader_returns200() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(eq("/api/v1/material-categories/" + id), eq(Void.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/admin/materials/categories/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());
  }

  // covers #582 — header routing: the same create URL WITHOUT the header still hits the classic
  // form
  // handler and redirects (no-JS fallback preserved).
  @Test
  @WithMockUser(roles = "ADMIN")
  void createCategory_withoutHeader_redirects() throws Exception {
    when(backendApiClient.post(
            contains("/material-categories"), any(), eq(MaterialCategoryDto.class)))
        .thenReturn(new MaterialCategoryDto(UUID.randomUUID(), "X", 0L));

    mockMvc
        .perform(post("/admin/materials/categories").with(csrf()).param("name", "X"))
        .andExpect(status().is3xxRedirection());
  }
}
