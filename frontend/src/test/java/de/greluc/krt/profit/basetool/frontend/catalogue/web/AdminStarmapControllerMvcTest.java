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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapLinkRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapObjectAdminDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapPlaceLinkDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapVisibilityRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The admin starmap page over MVC (REQ-P4K-034…038): the page shell for an admin, the object and
 * link lists relayed with their filters, the visibility override, the link change and the storage
 * place create relayed to the backend, and a stale write passed through as {@code 409}.
 */
@SpringBootTest
class AdminStarmapControllerMvcTest {

  private static final String BASE = "/api/v1/catalog/admin/starmap";
  private static final String PAGING = "?page={page}&size={size}";
  private static final UUID OBJECT = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
  private static final UUID ROW = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
  private static final UUID PLACE = UUID.fromString("00000000-0000-4000-8000-0000000000c3");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static <T> PageResponse<T> page(List<T> rows, int page) {
    return new PageResponse<>(rows, page, 25, rows.size(), 1, List.of());
  }

  /**
   * A visible moon of Stanton without a storage place.
   *
   * @param override the visibility override
   * @param version the object version
   * @param storagePlaceId the storage place, or {@code null}
   * @return the object
   */
  private static @NotNull StarmapObjectAdminDto daymar(
      @Nullable String override, long version, @Nullable UUID storagePlaceId) {
    return new StarmapObjectAdminDto(
        OBJECT,
        version,
        UUID.fromString("00000000-0000-4000-8000-0000000000c4"),
        "Stanton2b_Daymar",
        "Moon",
        "Stanton",
        null,
        "Daymar",
        "Daymar",
        false,
        true,
        override,
        !"HIDDEN".equals(override),
        false,
        storagePlaceId);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePage_carriesBothListsBothDialogsAndTheScript() throws Exception {
    String html =
        mockMvc
            .perform(get("/admin/starmap").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains(
            "id=\"starmap-objects\"",
            "id=\"starmap-objects-pager\"",
            "id=\"starmap-links\"",
            "id=\"starmap-links-kind\"",
            "id=\"starmap-links-unlinked\"",
            "id=\"starmap-storage-modal\"",
            "id=\"starmap-link-modal\"",
            "/js/catalogue/starmap.js",
            "window.krtStarmapI18n",
            "UEX-Verknüpfungen",
            "</html>")
        .containsPattern("<option value=\"CITY\" selected=\"selected\">Städte</option>")
        .contains("<option value=\"TERMINAL\">Terminals</option>");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMember_isRefused() throws Exception {
    mockMvc.perform(get("/admin/starmap")).andExpect(status().isForbidden());
    mockMvc.perform(get("/admin/starmap/objects")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theObjectList_relaysOnlyTheFiltersGiven() throws Exception {
    when(backendApiClient.get(eq(BASE + "/objects" + PAGING), anyTypeRef(), eq(0), eq(25)))
        .thenReturn(page(List.of(daymar(null, 1L, null)), 0));
    when(backendApiClient.get(
            eq(BASE + "/objects" + PAGING + "&starSystem={starSystem}&type={type}&query={query}"),
            anyTypeRef(),
            eq(2),
            eq(25),
            eq("Stanton"),
            eq("Moon"),
            eq("Day")))
        .thenReturn(page(List.of(daymar(null, 1L, null)), 2));

    mockMvc
        .perform(get("/admin/starmap/objects").param("starSystem", " ").param("query", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].className").value("Stanton2b_Daymar"))
        .andExpect(jsonPath("$.content[0].visible").value(true));
    mockMvc
        .perform(
            get("/admin/starmap/objects")
                .param("starSystem", "Stanton")
                .param("type", "Moon")
                .param("query", " Day ")
                .param("page", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(2));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aVisibilityOverride_returnsTheObject_andAStaleOneIsAConflict() throws Exception {
    String visibility = BASE + "/objects/{id}/visibility";
    when(backendApiClient.put(
            visibility,
            new StarmapVisibilityRequest("HIDDEN", 3L),
            StarmapObjectAdminDto.class,
            OBJECT))
        .thenReturn(daymar("HIDDEN", 4L, null));
    when(backendApiClient.put(
            visibility,
            new StarmapVisibilityRequest("HIDDEN", 2L),
            StarmapObjectAdminDto.class,
            OBJECT))
        .thenThrow(new BackendServiceException("stale", null, 409));

    mockMvc
        .perform(
            post("/admin/starmap/objects/" + OBJECT + "/visibility")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"override\":\"HIDDEN\",\"version\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.visibilityOverride").value("HIDDEN"))
        .andExpect(jsonPath("$.visible").value(false))
        .andExpect(jsonPath("$.version").value(4));
    mockMvc
        .perform(
            post("/admin/starmap/objects/" + OBJECT + "/visibility")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"override\":\"HIDDEN\",\"version\":2}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aStoragePlace_isCreatedOnItsObject_andADuplicateIsAConflict() throws Exception {
    UUID created = UUID.randomUUID();
    LocationDto expected =
        new LocationDto(null, "Lager Daymar", null, false, true, null, OBJECT, null, null);
    when(backendApiClient.post("/api/v1/locations", expected, LocationDto.class))
        .thenReturn(
            new LocationDto(
                created, "Lager Daymar", null, false, true, 0L, OBJECT, "Daymar", "Daymar"));
    LocationDto duplicate =
        new LocationDto(null, "Zweitlager", null, false, false, null, OBJECT, null, null);
    when(backendApiClient.post("/api/v1/locations", duplicate, LocationDto.class))
        .thenThrow(new BackendServiceException("duplicate", null, 409));

    mockMvc
        .perform(
            post("/admin/starmap/objects/" + OBJECT + "/storage-place")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"  Lager Daymar \",\"description\":\" \",\"hidden\":false,"
                        + "\"homeLocation\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(created.toString()))
        .andExpect(jsonPath("$.starmapObjectId").value(OBJECT.toString()));
    mockMvc
        .perform(
            post("/admin/starmap/objects/" + OBJECT + "/storage-place")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Zweitlager\",\"hidden\":false,\"homeLocation\":false}"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aStoragePlaceWithoutAName_isRefusedBeforeTheBackend() throws Exception {
    mockMvc
        .perform(
            post("/admin/starmap/objects/" + OBJECT + "/storage-place")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \",\"hidden\":false,\"homeLocation\":false}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePlaceList_relaysTheKindTheSearchAndTheLinkFilter() throws Exception {
    StarmapPlaceLinkDto lorville =
        new StarmapPlaceLinkDto("CITY", PLACE, "Lorville", "Stanton", null, null, null, false);
    when(backendApiClient.get(
            eq(BASE + "/places/{kind}" + PAGING + "&unlinkedOnly={unlinkedOnly}&query={query}"),
            anyTypeRef(),
            eq("CITY"),
            eq(1),
            eq(25),
            eq(true),
            eq("Lor")))
        .thenReturn(page(List.of(lorville), 1));

    mockMvc
        .perform(
            get("/admin/starmap/places/CITY")
                .param("query", "Lor")
                .param("unlinkedOnly", "true")
                .param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Lorville"))
        .andExpect(jsonPath("$.content[0].manual").value(false));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aLinkChange_returnsTheRow_andAChangedLinkIsAConflict() throws Exception {
    String link = BASE + "/places/{kind}/{id}/link";
    when(backendApiClient.put(
            link,
            new StarmapLinkRequest("SET", OBJECT, null, false),
            StarmapPlaceLinkDto.class,
            "CITY",
            ROW))
        .thenReturn(
            new StarmapPlaceLinkDto(
                "CITY", ROW, "Lorville", "Stanton", OBJECT, null, "Stanton1_Lorville", true));
    when(backendApiClient.put(
            link,
            new StarmapLinkRequest("RELEASE", null, OBJECT, true),
            StarmapPlaceLinkDto.class,
            "CITY",
            ROW))
        .thenThrow(new BackendServiceException("changed", null, 409));

    mockMvc
        .perform(
            post("/admin/starmap/places/CITY/" + ROW + "/link")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"action\":\"SET\",\"starmapObjectId\":\""
                        + OBJECT
                        + "\",\"expectedStarmapObjectId\":null,\"expectedManual\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.manual").value(true))
        .andExpect(jsonPath("$.starmapObjectClassName").value("Stanton1_Lorville"));
    mockMvc
        .perform(
            post("/admin/starmap/places/CITY/" + ROW + "/link")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"action\":\"RELEASE\",\"expectedStarmapObjectId\":\""
                        + OBJECT
                        + "\",\"expectedManual\":true}"))
        .andExpect(status().isConflict());
  }
}
