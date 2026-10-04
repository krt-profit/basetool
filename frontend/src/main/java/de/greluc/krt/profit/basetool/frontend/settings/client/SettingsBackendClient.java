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

package de.greluc.krt.profit.basetool.frontend.settings.client;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SystemSettingUpdateDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the admin system-settings page: the four versioned settings and the
 * squadron and Spezialkommando lists behind its toggles (REQ-ADMIN-001), over {@link
 * BackendApiClient} (plan §5.9, ADR-0032). Clearing the catalogue cache after a write stays with
 * the caller.
 */
@Service
@RequiredArgsConstructor
public class SettingsBackendClient {

  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> SQUADRON_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<SpecialCommandDto>>
      SPECIAL_COMMAND_PAGE = new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /** The system settings the page edits, each stored under its own key and version. */
  public enum SystemSetting {
    /** {@code job_order.age_yellow_days}: the yellow ageing threshold of a job order, in days. */
    JOB_ORDER_AGE_YELLOW_DAYS,
    /** {@code job_order.age_red_days}: the red ageing threshold of a job order, in days. */
    JOB_ORDER_AGE_RED_DAYS,
    /** {@code refinery.rounding.mode}: how refinery yields are rounded. */
    REFINERY_ROUNDING_MODE,
    /** {@code operation.transfer_fee_rate}: the in-game transfer fee, as a fraction. */
    OPERATION_TRANSFER_FEE_RATE
  }

  /**
   * Reads one setting with its version.
   *
   * @param setting the setting
   * @return the stored value and version, or {@code null} when the backend sent no body
   */
  @Nullable
  public SystemSettingDto read(@NotNull SystemSetting setting) {
    return backendApiClient.get(uri(setting), SystemSettingDto.class);
  }

  /**
   * Writes one setting, carrying the optimistic-lock version in the update.
   *
   * @param setting the setting
   * @param update the new value and the version it replaces
   * @return the stored value and its new version
   */
  @Nullable
  public SystemSettingDto write(
      @NotNull SystemSetting setting, @NotNull SystemSettingUpdateDto update) {
    return backendApiClient.put(uri(setting), update, SystemSettingDto.class);
  }

  /**
   * Reads one page of the active squadrons for the promotion toggle.
   *
   * @param page the zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SquadronDto> squadronPage(int page) {
    return backendApiClient.get(
        "/api/v1/squadrons?size=1000&sort=name,asc&page={page}", SQUADRON_PAGE, page);
  }

  /**
   * Reads one page of the active Spezialkommandos for the profit toggle.
   *
   * @param page the zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SpecialCommandDto> specialCommandPage(int page) {
    return backendApiClient.get(
        "/api/v1/special-commands?size=1000&sort=name,asc&page={page}", SPECIAL_COMMAND_PAGE, page);
  }

  /**
   * Names the backend resource of a setting.
   *
   * @param setting the setting
   * @return its path below {@code /api/v1/settings}
   */
  @NotNull
  private static String uri(@NotNull SystemSetting setting) {
    return switch (setting) {
      case JOB_ORDER_AGE_YELLOW_DAYS -> "/api/v1/settings/job_order.age_yellow_days";
      case JOB_ORDER_AGE_RED_DAYS -> "/api/v1/settings/job_order.age_red_days";
      case REFINERY_ROUNDING_MODE -> "/api/v1/settings/refinery.rounding.mode";
      case OPERATION_TRANSFER_FEE_RATE -> "/api/v1/settings/operation.transfer_fee_rate";
    };
  }
}
