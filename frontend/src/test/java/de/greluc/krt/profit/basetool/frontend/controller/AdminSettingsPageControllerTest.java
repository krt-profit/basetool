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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Unit tests for the job-order intake-SK persistence branch of {@link AdminSettingsPageController}.
 * The four base settings (age thresholds, refinery rounding, transfer fee) always PUT; the intake
 * SK is conditional because the backend setting is {@code @NotBlank} and cannot be cleared to blank
 * through this form.
 */
@ExtendWith(MockitoExtension.class)
class AdminSettingsPageControllerTest {

  private static final String INTAKE_URI = "/api/v1/settings/job_order.intake_special_command_id";

  @Mock private BackendApiClient backendApiClient;

  @InjectMocks private AdminSettingsPageController controller;

  @Test
  void updateSettings_persistsIntakeSk_whenAnSkIsSelected() {
    RedirectAttributesModelMap ra = new RedirectAttributesModelMap();
    UUID sk = UUID.randomUUID();

    controller.updateSettings("30", 0L, "90", 0L, "UP", 0L, "0.5", 0L, sk.toString(), 0L, ra);

    verify(backendApiClient).put(eq(INTAKE_URI), any(), eq(SystemSettingDto.class));
  }

  @Test
  void updateSettings_skipsIntakeSk_whenSelectionIsBlank() {
    RedirectAttributesModelMap ra = new RedirectAttributesModelMap();

    controller.updateSettings("30", 0L, "90", 0L, "UP", 0L, "0.5", 0L, "  ", 0L, ra);

    verify(backendApiClient, never()).put(eq(INTAKE_URI), any(), any());
  }

  // covers REQ-ADMIN-001 — a squadron beyond the first backend page still gets its promotion
  // toggle rendered on the settings page
  @Test
  void viewSettings_walksAllSquadronPickerPages() {
    // Given — the squadron catalog spans two backend pages; the SK picker is empty
    SquadronDto first = new SquadronDto(UUID.randomUUID(), "Alpha", "AL", "", true, true, true, 0L);
    SquadronDto second = new SquadronDto(UUID.randomUUID(), "Zulu", "ZU", "", true, true, true, 0L);
    String squadronsBase = "/api/v1/squadrons?size=1000&sort=name,asc";
    when(backendApiClient.get(eq(squadronsBase + "&page=0"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(second), 0, 1000, 2, 2, List.of()));
    when(backendApiClient.get(eq(squadronsBase + "&page=1"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(first), 1, 1000, 2, 2, List.of()));
    when(backendApiClient.get(
            eq("/api/v1/special-commands?size=1000&sort=name,asc&page=0"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));
    ConcurrentModel model = new ConcurrentModel();

    // When
    controller.viewSettings(model);

    // Then — both pages land in the toggle list, sorted, with no truncation flagged
    @SuppressWarnings("unchecked")
    List<SquadronDto> squadrons = (List<SquadronDto>) model.getAttribute("squadrons");
    assertEquals(2, squadrons.size(), "the second backend page must not be dropped");
    assertEquals("Alpha", squadrons.get(0).name());
    assertEquals("Zulu", squadrons.get(1).name());
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
  }
}
