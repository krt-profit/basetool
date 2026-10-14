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

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccountRef;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccounts;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The purchasing feature switch, settings and risk-class table (REQ-PUR-001, REQ-PUR-002,
 * REQ-PUR-011). The switch lives in the system settings and is off until an admin turns it on; the
 * rest sits in the single settings row and the three risk-class rows.
 */
@Service
@RequiredArgsConstructor
public class PurchasingSettingsService {

  /** Store key of the feature switch; an absent value means off. */
  static final String ENABLED_KEY = "purchasing.enabled";

  private final SystemSettings systemSettings;
  private final PurchasingSettingRepository settingRepository;
  private final PurchasingRiskClassRepository riskClassRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final QualityTierRepository qualityTierRepository;
  private final FundingAccounts fundingAccounts;
  private final AuditRecorder auditRecorder;

  /**
   * Whether the purchasing surface is switched on.
   *
   * @return {@code true} only when the switch holds {@code true}
   */
  @Transactional(readOnly = true)
  public boolean isEnabled() {
    return systemSettings
        .getSettingValue(ENABLED_KEY)
        .map(value -> value.strip().equals("true"))
        .orElse(false);
  }

  /**
   * Refuses a request while the purchasing surface is switched off.
   *
   * @throws PurchasingProblemException with {@code PURCHASING_DISABLED} (404) while it is off
   */
  @Transactional(readOnly = true)
  public void requireEnabled() {
    if (!isEnabled()) {
      throw new PurchasingProblemException(
          PurchasingProblemCode.PURCHASING_DISABLED, "The purchasing surface is switched off");
    }
  }

  /**
   * Loads the single settings row.
   *
   * @return the settings
   */
  @NotNull
  @Transactional(readOnly = true)
  public PurchasingSetting current() {
    return Entities.require(
        settingRepository.findById(PurchasingSetting.SINGLETON_ID), "Purchasing settings missing");
  }

  /**
   * Returns the ids of the governing Bereiche.
   *
   * @return the Bereiche, sorted by id; empty while none is set
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<UUID> governingAreaIds() {
    return current().getGoverningAreaIds().stream().sorted().toList();
  }

  /**
   * Reads the settings as the admin page shows them.
   *
   * @return the switch and the settings row
   */
  @NotNull
  @Transactional(readOnly = true)
  public PurchasingSettingsDto read() {
    return toDto(current());
  }

  /**
   * Stores the switch and the settings and audits the change (REQ-PUR-002).
   *
   * @param request the new values with the echoed version
   * @return the stored settings
   * @throws BadRequestException when a governing unit is no Bereich or a tier is unknown
   * @throws PurchasingProblemException with {@code PURCHASING_FUNDING_ACCOUNT_MISSING} when the
   *     account is not an active {@code CARTEL} or {@code SPECIAL} account
   */
  @NotNull
  @Transactional
  public PurchasingSettingsDto update(@NotNull UpdatePurchasingSettingsRequest request) {
    PurchasingSetting setting = current();
    OptimisticLock.checkRequired(
        setting.getVersion(), request.version(), PurchasingSetting.class, setting.getId());
    Set<UUID> areas = request.governingAreaIds() == null ? Set.of() : request.governingAreaIds();
    for (UUID areaId : areas) {
      OrgUnit area = Entities.require(orgUnitRepository.findById(areaId), "Org unit not found");
      if (area.getKind() != OrgUnitKind.BEREICH) {
        throw new BadRequestException("A governing unit must be a Bereich");
      }
    }
    if (request.fundingAccountId() != null
        && fundingAccounts.findActive(request.fundingAccountId()).isEmpty()) {
      throw new PurchasingProblemException(
          PurchasingProblemCode.PURCHASING_FUNDING_ACCOUNT_MISSING,
          "The funding account must be an active KRT or special account");
    }
    Set<UUID> tiers =
        request.allowedQualityTierIds() == null ? Set.of() : request.allowedQualityTierIds();
    if (!tiers.isEmpty() && qualityTierRepository.findAllById(tiers).size() != tiers.size()) {
      throw new BadRequestException("A quality tier is unknown");
    }
    final boolean wasEnabled = isEnabled();
    setting.getGoverningAreaIds().clear();
    setting.getGoverningAreaIds().addAll(areas);
    setting.setFundingAccountId(request.fundingAccountId());
    setting.setDefaultMonthlyLimit(request.defaultMonthlyLimit());
    setting.setBudgetCap(request.budgetCap());
    setting.setBudgetPeriod(request.budgetPeriod());
    setting.setSettlementDueDays(request.settlementDueDays());
    setting.getAllowedQualityTierIds().clear();
    setting.getAllowedQualityTierIds().addAll(tiers);
    PurchasingSetting saved = settingRepository.saveAndFlush(setting);
    systemSettings.putSettingValue(ENABLED_KEY, Boolean.toString(request.enabled()));
    auditRecorder.record(
        AuditEventType.PURCHASING_SETTINGS_CHANGED,
        saved.getId(),
        "Purchasing settings",
        null,
        AuditDetails.of("enabled", request.enabled())
            .with("wasEnabled", wasEnabled)
            .with("governingAreas", areas.size())
            .with("fundingAccount", saved.getFundingAccountId())
            .with("defaultMonthlyLimit", saved.getDefaultMonthlyLimit())
            .with("budgetCap", saved.getBudgetCap())
            .with("budgetPeriod", saved.getBudgetPeriod().name())
            .with("settlementDueDays", saved.getSettlementDueDays())
            .with("tiers", tiers.size()));
    return toDto(saved);
  }

  /**
   * Lists the risk-class table from the lowest to the highest class.
   *
   * @return the three classes
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<PurchasingRiskClass> classesInOrder() {
    return riskClassRepository.findAllByOrderByCodeAsc();
  }

  /**
   * Reads the risk-class table as the admin page shows it.
   *
   * @return the three classes
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<RiskClassDto> riskClasses() {
    return classesInOrder().stream().map(PurchasingSettingsService::toDto).toList();
  }

  /**
   * Stores changes of the risk-class table and audits them (REQ-PUR-011).
   *
   * @param request the classes to change, each with its echoed version
   * @return the whole table afterwards
   */
  @NotNull
  @Transactional
  public List<RiskClassDto> updateRiskClasses(@NotNull UpdateRiskClassesRequest request) {
    Map<String, PurchasingRiskClass> byCode = new HashMap<>();
    for (PurchasingRiskClass riskClass : riskClassRepository.findAll()) {
      byCode.put(riskClass.getCode().name(), riskClass);
    }
    AuditDetails details = AuditDetails.of("classes", request.classes().size());
    for (UpdateRiskClassesRequest.UpdateRiskClassEntry entry : request.classes()) {
      PurchasingRiskClass riskClass = byCode.get(entry.code().name());
      if (riskClass == null) {
        throw new BadRequestException("Unknown risk class");
      }
      OptimisticLock.checkRequired(
          riskClass.getVersion(), entry.version(), PurchasingRiskClass.class, riskClass.getId());
      riskClass.setMaxTotal(entry.maxTotal());
      riskClass.setMaxAdvancePercent(entry.maxAdvancePercent());
      riskClass.setRequiresKnownPartner(entry.requiresKnownPartner());
      riskClass.setEscortSuggested(entry.escortSuggested());
      details = details.with(entry.code().name() + "MaxTotal", entry.maxTotal());
    }
    riskClassRepository.flush();
    auditRecorder.record(AuditEventType.RISK_CLASSES_CHANGED, null, "Risk classes", null, details);
    return riskClasses();
  }

  /**
   * Lists the bank accounts an admin may choose as the funding account (REQ-PUR-002).
   *
   * @return the active {@code CARTEL} and {@code SPECIAL} accounts by account number
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<FundingAccountOptionDto> fundingAccountOptions() {
    return fundingAccounts.listCandidates().stream()
        .map(ref -> new FundingAccountOptionDto(ref.id(), ref.accountNo(), ref.name(), ref.type()))
        .toList();
  }

  private @NotNull PurchasingSettingsDto toDto(@NotNull PurchasingSetting setting) {
    List<PurchasingRefDto> areas =
        orgUnitRepository.findAllById(setting.getGoverningAreaIds()).stream()
            .map(area -> new PurchasingRefDto(area.getId(), area.getName()))
            .sorted(Comparator.comparing(PurchasingRefDto::name))
            .toList();
    Optional<FundingAccountRef> account =
        setting.getFundingAccountId() == null
            ? Optional.empty()
            : fundingAccounts.findActive(setting.getFundingAccountId());
    return new PurchasingSettingsDto(
        isEnabled(),
        areas,
        setting.getFundingAccountId(),
        account.map(FundingAccountRef::accountNo).orElse(null),
        account.map(FundingAccountRef::name).orElse(null),
        setting.getDefaultMonthlyLimit(),
        setting.getBudgetCap(),
        setting.getBudgetPeriod(),
        setting.getSettlementDueDays(),
        setting.getAllowedQualityTierIds().stream().sorted().toList(),
        setting.isPaused(),
        setting.getPauseReason(),
        setting.getVersion());
  }

  private static @NotNull RiskClassDto toDto(@NotNull PurchasingRiskClass riskClass) {
    return new RiskClassDto(
        riskClass.getCode(),
        riskClass.getMaxTotal(),
        riskClass.getMaxAdvancePercent(),
        riskClass.isRequiresKnownPartner(),
        riskClass.isEscortSuggested(),
        riskClass.getVersion());
  }
}
