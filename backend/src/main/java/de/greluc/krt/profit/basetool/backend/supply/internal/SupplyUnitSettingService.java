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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and sets what the org units do in the supply module (REQ-SUP-002): which units raise needs
 * and which accept donations. No unit is hardcoded; a unit without a row does neither.
 */
@Service
@RequiredArgsConstructor
public class SupplyUnitSettingService {

  private final SupplyUnitSettingRepository settingRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final SupplySettingsService settingsService;
  private final SupplyAccessPolicy accessPolicy;
  private final AuditRecorder auditRecorder;

  /**
   * Lists the units that raise needs or accept donations, for the pickers of every member.
   *
   * @return the configured units, ordered by name
   */
  @Transactional(readOnly = true)
  public @NotNull List<SupplyUnitDto> listConfigured() {
    settingsService.requireEnabled();
    return list(true);
  }

  /**
   * Lists every active org unit with its setting, for the admin page.
   *
   * @return all units, ordered by name
   */
  @Transactional(readOnly = true)
  public @NotNull List<SupplyUnitDto> listAll() {
    return list(false);
  }

  private @NotNull List<SupplyUnitDto> list(boolean onlyConfigured) {
    Map<UUID, SupplyUnitSetting> settings =
        settingRepository.findAll().stream()
            .collect(Collectors.toMap(SupplyUnitSetting::getId, Function.identity()));
    SupplyManagedUnits managed = accessPolicy.managedUnits();
    return orgUnitRepository.findAll().stream()
        .filter(OrgUnit::isActive)
        .map(unit -> toDto(unit, settings.get(unit.getId()), managed))
        .filter(dto -> !onlyConfigured || dto.mayRaiseNeeds() || dto.acceptsDonations())
        .sorted(Comparator.comparing(SupplyUnitDto::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * Sets what a unit does in the supply module and audits the change (REQ-SUP-002).
   *
   * @param orgUnitId the unit
   * @param request the flags and the echoed version
   * @return the unit with its new setting and the caller's manage flag of REQ-SUP-003
   */
  @Transactional
  public @NotNull SupplyUnitDto update(
      @NotNull UUID orgUnitId, @NotNull UpdateSupplyUnitRequest request) {
    final OrgUnit unit =
        Entities.require(orgUnitRepository.findById(orgUnitId), "Org unit not found");
    SupplyUnitSetting setting = settingRepository.findById(orgUnitId).orElse(null);
    final boolean wasRaising = setting != null && setting.isMayRaiseNeeds();
    final boolean wasAccepting = setting != null && setting.isAcceptsDonations();
    if (setting == null) {
      setting = new SupplyUnitSetting();
      setting.setId(orgUnitId);
    } else {
      OptimisticLock.checkRequired(
          setting.getVersion(),
          request.version() == null ? -1L : request.version(),
          SupplyUnitSetting.class,
          orgUnitId);
    }
    setting.setMayRaiseNeeds(request.mayRaiseNeeds());
    setting.setAcceptsDonations(request.acceptsDonations());
    SupplyUnitSetting saved = settingRepository.saveAndFlush(setting);
    auditRecorder.record(
        AuditEventType.SUPPLY_UNIT_SETTING_CHANGED,
        orgUnitId,
        unit.getName(),
        null,
        AuditDetails.of("mayRaiseNeeds", saved.isMayRaiseNeeds())
            .with("acceptsDonations", saved.isAcceptsDonations())
            .with("wasMayRaiseNeeds", wasRaising)
            .with("wasAcceptsDonations", wasAccepting));
    return toDto(unit, saved, accessPolicy.managedUnits());
  }

  /**
   * Maps a unit with its setting.
   *
   * @param unit the unit
   * @param setting the unit's setting, or {@code null} while it has none
   * @param managed the caller's managed units, which decide the unit's manage flag
   * @return the unit as the pickers and the admin page show it
   */
  private static @NotNull SupplyUnitDto toDto(
      @NotNull OrgUnit unit,
      @Nullable SupplyUnitSetting setting,
      @NotNull SupplyManagedUnits managed) {
    return new SupplyUnitDto(
        unit.getId(),
        unit.getName(),
        unit.getShorthand(),
        unit.getKind(),
        setting != null && setting.isMayRaiseNeeds(),
        setting != null && setting.isAcceptsDonations(),
        managed.contains(unit.getId()),
        setting == null ? null : setting.getVersion());
  }
}
