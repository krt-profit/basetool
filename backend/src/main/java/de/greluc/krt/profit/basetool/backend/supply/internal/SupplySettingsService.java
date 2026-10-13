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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemCode;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supply module's runtime settings in the system key-value store: the feature switch
 * (REQ-SUP-001, E-12) and the governing Bereich the statistics and the redaction rules refer to.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplySettingsService {

  /** Store key of the feature switch; an absent value means off. */
  static final String ENABLED_KEY = "supply.enabled";

  /** Store key of the governing Bereich's id. */
  static final String GOVERNING_AREA_KEY = "supply.governing_area_id";

  private final SystemSettings systemSettings;
  private final OrgUnitRepository orgUnitRepository;
  private final AuditRecorder auditRecorder;
  private final AuthHelperService authHelperService;

  /**
   * Whether the supply surface is switched on.
   *
   * @return {@code true} only when the switch holds {@code true}
   */
  @Transactional(readOnly = true)
  public boolean isEnabled() {
    return systemSettings
        .getSettingValue(ENABLED_KEY)
        .map(v -> v.strip().equals("true"))
        .orElse(false);
  }

  /**
   * Refuses a request while the supply surface is switched off.
   *
   * @throws SupplyProblemException with {@code SUPPLY_DISABLED} (404) while the switch is off
   */
  @Transactional(readOnly = true)
  public void requireEnabled() {
    if (!isEnabled()) {
      throw new SupplyProblemException(
          SupplyProblemCode.SUPPLY_DISABLED, "The supply surface is switched off");
    }
  }

  /**
   * Returns the id of the governing Bereich, when one is set.
   *
   * @return the Bereich's id, or empty when none is set or the stored value is malformed
   */
  @Transactional(readOnly = true)
  public @NotNull Optional<UUID> governingAreaId() {
    return systemSettings
        .getSettingValue(GOVERNING_AREA_KEY)
        .map(String::strip)
        .filter(v -> !v.isEmpty())
        .flatMap(
            raw -> {
              try {
                return Optional.of(UUID.fromString(raw));
              } catch (IllegalArgumentException _) {
                log.warn("Setting {} is not an id, ignoring it", GOVERNING_AREA_KEY);
                return Optional.empty();
              }
            });
  }

  /**
   * Reads the settings as the admin page shows them.
   *
   * @return the switch and the governing Bereich with its name
   */
  @Transactional(readOnly = true)
  public @NotNull SupplySettingsDto read() {
    Optional<UUID> area = governingAreaId();
    String name = area.flatMap(orgUnitRepository::findById).map(OrgUnit::getName).orElse(null);
    return new SupplySettingsDto(isEnabled(), area.orElse(null), name);
  }

  /**
   * Stores the switch and the governing Bereich and audits the change.
   *
   * @param enabled whether the supply surface is on
   * @param governingAreaId the governing Bereich, or {@code null} for none
   * @return the stored settings
   * @throws BadRequestException when the id is not a Bereich
   */
  @Transactional
  public @NotNull SupplySettingsDto update(boolean enabled, @Nullable UUID governingAreaId) {
    if (governingAreaId != null) {
      OrgUnit area =
          Entities.require(orgUnitRepository.findById(governingAreaId), "Org unit not found");
      if (area.getKind() != OrgUnitKind.BEREICH) {
        throw new BadRequestException("The governing unit must be a Bereich");
      }
    }
    boolean wasEnabled = isEnabled();
    Optional<UUID> wasArea = governingAreaId();
    systemSettings.putSettingValue(ENABLED_KEY, Boolean.toString(enabled));
    systemSettings.putSettingValue(
        GOVERNING_AREA_KEY, governingAreaId == null ? "" : governingAreaId.toString());
    auditRecorder.record(
        AuditEventType.SUPPLY_SETTINGS_CHANGED,
        null,
        "Supply settings",
        null,
        AuditDetails.of("enabled", enabled)
            .with("wasEnabled", wasEnabled)
            .with("governingArea", governingAreaId)
            .with("wasGoverningArea", wasArea.orElse(null)));
    return read();
  }
}
