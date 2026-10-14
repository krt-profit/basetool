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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PauseReason;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.events.PurchasingNotices;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The kill switch of purchasing (REQ-PUR-019): a pause for the whole organisation or for the needs
 * of one raising unit, set and lifted by the unit's lead, the governing leadership, the OL or an
 * admin. While paused no deal is submitted and no tranche requested; deliveries, acceptance and
 * settlement go on, so no money is stranded.
 */
@Service
@RequiredArgsConstructor
public class PurchasingPauseService {

  private final PurchasingSettingsService settingsService;
  private final PurchasingSettingRepository settingRepository;
  private final PurchasingUnitPauseRepository unitPauseRepository;
  private final PurchasingDelegateService delegateService;
  private final PurchasingAccessPolicy accessPolicy;
  private final OrgUnitRepository orgUnitRepository;
  private final AuthHelperService authHelperService;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Reads where purchasing is paused.
   *
   * @return the global pause and the paused units
   */
  @NotNull
  @Transactional(readOnly = true)
  public PauseStateDto state() {
    PurchasingSetting setting = settingsService.current();
    List<PauseStateDto.UnitPause> units =
        unitPauseRepository.findAll().stream()
            .map(
                pause ->
                    new PauseStateDto.UnitPause(
                        pause.getOrgUnitId(),
                        orgUnitRepository
                            .findById(pause.getOrgUnitId())
                            .map(OrgUnit::getName)
                            .orElse("—"),
                        pause.getReason(),
                        pause.getPausedAt()))
            .toList();
    return new PauseStateDto(
        setting.isPaused(), setting.getPauseReason(), setting.getPausedAt(), units);
  }

  /**
   * Refuses the action while purchasing is paused for the whole organisation or for a unit
   * (REQ-PUR-019).
   *
   * @param raisingOrgUnitId the raising unit of the need concerned
   * @throws PurchasingProblemException with {@code PURCHASING_PAUSED} while paused
   */
  @Transactional(readOnly = true)
  public void requireNotPaused(@NotNull UUID raisingOrgUnitId) {
    if (settingsService.current().isPaused()
        || unitPauseRepository.findByOrgUnitId(raisingOrgUnitId).isPresent()) {
      throw new PurchasingProblemException(
          PurchasingProblemCode.PURCHASING_PAUSED, "Purchasing is paused");
    }
  }

  /**
   * Pauses purchasing (REQ-PUR-019).
   *
   * @param request the scope and the reason
   * @return where purchasing is paused afterwards
   * @throws AccessDeniedException when the caller may not pause that scope
   */
  @NotNull
  @Transactional
  public PauseStateDto pause(@NotNull PausePurchasingRequest request) {
    settingsService.requireEnabled();
    requireMayPause(request.orgUnitId());
    UUID actor = authHelperService.currentUserId().orElse(null);
    Set<UUID> delegates = delegateService.activeDelegateIds();
    if (request.orgUnitId() == null) {
      PurchasingSetting setting = settingsService.current();
      setting.setPaused(true);
      setting.setPauseReason(request.reason());
      setting.setPausedBy(actor);
      setting.setPausedAt(Instant.now());
      settingRepository.saveAndFlush(setting);
      auditRecorder.record(
          AuditEventType.PURCHASING_PAUSED,
          PurchasingNotices.GLOBAL_PAUSE_ID,
          "Purchasing",
          null,
          AuditDetails.of("scope", PurchasingNotices.SCOPE_GLOBAL)
              .with("reason", request.reason().name()));
      eventPublisher.publishEvent(
          PurchasingNotices.paused(
              PurchasingNotices.GLOBAL_PAUSE_ID,
              null,
              null,
              null,
              request.reason().name(),
              delegates,
              actor));
      return state();
    }
    OrgUnit unit =
        Entities.require(orgUnitRepository.findById(request.orgUnitId()), "Org unit not found");
    PurchasingUnitPause pause =
        unitPauseRepository.findByOrgUnitId(unit.getId()).orElseGet(PurchasingUnitPause::new);
    pause.setOrgUnitId(unit.getId());
    pause.setReason(request.reason());
    pause.setPausedBy(actor);
    pause.setPausedAt(Instant.now());
    unitPauseRepository.saveAndFlush(pause);
    auditRecorder.record(
        AuditEventType.PURCHASING_PAUSED,
        unit.getId(),
        unit.getName(),
        null,
        AuditDetails.of("scope", PurchasingNotices.SCOPE_UNIT)
            .with("unit", unit.getId())
            .with("reason", request.reason().name()));
    eventPublisher.publishEvent(
        PurchasingNotices.paused(
            unit.getId(),
            unit.getId(),
            unit.getKind(),
            unit.getName(),
            request.reason().name(),
            delegates,
            actor));
    return state();
  }

  /**
   * Resumes purchasing (REQ-PUR-019).
   *
   * @param request the scope to resume
   * @return where purchasing is paused afterwards
   * @throws AccessDeniedException when the caller may not resume that scope
   */
  @NotNull
  @Transactional
  public PauseStateDto resume(@NotNull ResumePurchasingRequest request) {
    settingsService.requireEnabled();
    requireMayPause(request.orgUnitId());
    UUID actor = authHelperService.currentUserId().orElse(null);
    Set<UUID> delegates = delegateService.activeDelegateIds();
    if (request.orgUnitId() == null) {
      PurchasingSetting setting = settingsService.current();
      setting.setPaused(false);
      setting.setPauseReason(null);
      setting.setPausedBy(null);
      setting.setPausedAt(null);
      settingRepository.saveAndFlush(setting);
      auditRecorder.record(
          AuditEventType.PURCHASING_RESUMED,
          PurchasingNotices.GLOBAL_PAUSE_ID,
          "Purchasing",
          null,
          AuditDetails.of("scope", PurchasingNotices.SCOPE_GLOBAL));
      eventPublisher.publishEvent(
          PurchasingNotices.resumed(
              PurchasingNotices.GLOBAL_PAUSE_ID, null, null, null, delegates, actor));
      return state();
    }
    OrgUnit unit =
        Entities.require(orgUnitRepository.findById(request.orgUnitId()), "Org unit not found");
    unitPauseRepository
        .findByOrgUnitId(unit.getId())
        .ifPresent(
            pause -> {
              unitPauseRepository.delete(pause);
              unitPauseRepository.flush();
            });
    auditRecorder.record(
        AuditEventType.PURCHASING_RESUMED,
        unit.getId(),
        unit.getName(),
        null,
        AuditDetails.of("scope", PurchasingNotices.SCOPE_UNIT).with("unit", unit.getId()));
    eventPublisher.publishEvent(
        PurchasingNotices.resumed(
            unit.getId(), unit.getId(), unit.getKind(), unit.getName(), delegates, actor));
    return state();
  }

  private void requireMayPause(@Nullable UUID orgUnitId) {
    if (!accessPolicy.canPause(orgUnitId)) {
      throw new AccessDeniedException("The caller may not pause or resume purchasing here");
    }
  }

  /**
   * Names the reason a pause carries in the audit row.
   *
   * @param reason the pause reason
   * @return the reason's code
   */
  static String code(@NotNull PauseReason reason) {
    return reason.name();
  }
}
