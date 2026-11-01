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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportJobDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsAcceptanceStatusDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the bodies of the admin dashboard's widgets through MockMvc (REQ-UI-034): the rows and
 * the count of each queue, the row limit, the empty states, the sync status against its range, and
 * the refusal of every non-administrator.
 */
@SpringBootTest
class DashboardAdminWidgetMvcTest {

  private static final String BASE = "/dashboard/widgets/";
  private static final String JOBS_URI = "/api/v1/catalog/admin/import/p4k/jobs";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static RequestPostProcessor admin() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private String render(String path) throws Exception {
    return mockMvc
        .perform(
            get(BASE + path)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(admin())
                .locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static AdminDeletionRequestDto deletion(String handle) {
    return new AdminDeletionRequestDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        handle,
        "PENDING",
        false,
        Instant.parse("2026-10-01T10:00:00Z"),
        null,
        null,
        0L);
  }

  /** The deletion requests list the oldest first up to the rows and count the whole queue. */
  @Test
  void deletionRequestsShowTheRowsAndCountTheQueue() throws Exception {
    when(backendApiClient.get(eq("/api/v1/users/admin/deletion-requests"), anyTypeRef()))
        .thenReturn(IntStream.range(0, 7).mapToObj(i -> deletion("Pilot" + i)).toList());

    String html = render("deletion-requests?rows=3");

    assertThat(html)
        .contains("data-widget-content")
        .contains("Pilot0")
        .contains("Pilot2")
        .doesNotContain("Pilot3")
        .containsPattern("data-testid=\"admin-widget-count\"[^>]*>7<")
        .contains("href=\"/admin/deletion-requests\"");
  }

  /** An empty queue shows its sentence and a zero count. */
  @Test
  void anEmptyQueueShowsItsSentence() throws Exception {
    when(backendApiClient.get(eq("/api/v1/users/admin/registrations"), anyTypeRef()))
        .thenReturn(List.of());

    String html = render("discord-registrations");

    assertThat(html)
        .contains("Keine offenen Registrierungen.")
        .containsPattern("data-testid=\"admin-widget-count\"[^>]*>0<")
        .contains("href=\"/admin/discord-registrations\"");
  }

  /** The registrations list their callsigns. */
  @Test
  void registrationsListTheCallsigns() throws Exception {
    when(backendApiClient.get(eq("/api/v1/users/admin/registrations"), anyTypeRef()))
        .thenReturn(
            List.of(
                new PendingRegistrationDto(
                    UUID.randomUUID(),
                    "NeuerPilot",
                    null,
                    Instant.parse("2026-10-02T10:00:00Z"),
                    null,
                    false,
                    0L)));

    assertThat(render("discord-registrations")).contains("NeuerPilot");
  }

  /** The open terms count is the total of the page, not of the rows shown. */
  @Test
  void openTermsCountThePageTotal() throws Exception {
    when(backendApiClient.get(startsWith("/api/v1/terms/admin"), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(new TermsAcceptanceStatusDto(UUID.randomUUID(), "u1", "Anna", null)),
                0,
                5,
                42,
                9,
                List.of()));

    String html = render("open-terms");

    assertThat(html)
        .contains("Anna")
        .containsPattern("data-testid=\"admin-widget-count\"[^>]*>42<")
        .contains("href=\"/admin/terms\"");
  }

  /** The sync status marks a run inside the range and one outside it, and shows the import. */
  @Test
  void syncStatusMarksRunsAgainstTheRange() throws Exception {
    Instant recent = Instant.now().minus(2, ChronoUnit.HOURS);
    Instant old = Instant.now().minus(20, ChronoUnit.DAYS);
    when(backendApiClient.get(startsWith("/api/v1/catalog/admin/sync-reports"), anyTypeRef()))
        .thenAnswer(call ->
            new PageResponse<>(
                List.of(report(call.getArgument(0, String.class).contains("UEX") ? recent : old)),
                0,
                100,
                1,
                1,
                List.of()));
    when(backendApiClient.get(eq(JOBS_URI), anyTypeRef()))
        .thenReturn(List.of(job("FAILED", recent)));

    String html = render("sync-status?days=7");

    assertThat(html)
        .contains("data-testid=\"admin-sync-uex\"")
        .contains("data-testid=\"admin-sync-scwiki\"")
        .contains("data-testid=\"admin-sync-p4k\"")
        .contains("Fehlgeschlagen")
        .contains("vor mehr als 7 Tagen")
        .contains("href=\"/admin/sync-reports\"");
  }

  /** A sync that never ran says so. */
  @Test
  void aSyncThatNeverRanSaysSo() throws Exception {
    when(backendApiClient.get(startsWith("/api/v1/catalog/admin/sync-reports"), anyTypeRef()))
        .thenReturn(new PageResponse<SyncReportDto>(List.of(), 0, 100, 0, 0, List.of()));
    when(backendApiClient.get(eq(JOBS_URI), anyTypeRef())).thenReturn(List.of());

    assertThat(render("sync-status")).contains("Noch kein Lauf");
  }

  /** Every admin widget is for administrators only. */
  @Test
  void everyAdminWidgetRefusesAMember() throws Exception {
    for (String path :
        List.of(
            "admin-hub",
            "deletion-requests",
            "discord-registrations",
            "open-terms",
            "sync-status")) {
      mockMvc
          .perform(
              get(BASE + path)
                  .header("X-Requested-With", "XMLHttpRequest")
                  .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
          .andExpect(status().isForbidden());
    }
  }

  private static SyncReportDto report(Instant ranAt) {
    return new SyncReportDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        ranAt,
        "UEX",
        "ADDED",
        "Material",
        null,
        null,
        null,
        "");
  }

  private static P4kImportJobDto job(String status, Instant finished) {
    return new P4kImportJobDto(
        UUID.randomUUID().toString(),
        "APPLY",
        status,
        false,
        null,
        null,
        null,
        null,
        null,
        finished.minus(5, ChronoUnit.MINUTES).toString(),
        null,
        finished.toString());
  }
}
