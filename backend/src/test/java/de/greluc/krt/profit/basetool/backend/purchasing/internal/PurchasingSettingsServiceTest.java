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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccountRef;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccounts;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.purchasing.api.BudgetPeriod;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PauseReason;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** The purchasing switch, settings and risk-class table (REQ-PUR-001, REQ-PUR-002, REQ-PUR-011). */
@ExtendWith(MockitoExtension.class)
class PurchasingSettingsServiceTest {

  private static final String ENABLED_KEY = "purchasing.enabled";

  @Mock private SystemSettings systemSettings;
  @Mock private PurchasingSettingRepository settingRepository;
  @Mock private PurchasingRiskClassRepository riskClassRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private QualityTierRepository qualityTierRepository;
  @Mock private FundingAccounts fundingAccounts;
  @Mock private AuditRecorder auditRecorder;

  private PurchasingSettingsService service;

  @BeforeEach
  void setUp() {
    service =
        new PurchasingSettingsService(
            systemSettings,
            settingRepository,
            riskClassRepository,
            orgUnitRepository,
            qualityTierRepository,
            fundingAccounts,
            auditRecorder);
  }

  private static PurchasingSetting setting(long version) {
    PurchasingSetting setting = new PurchasingSetting();
    setting.setId(PurchasingSetting.SINGLETON_ID);
    setting.setVersion(version);
    setting.setDefaultMonthlyLimit(1_000L);
    return setting;
  }

  private static PurchasingRiskClass riskClass(RiskClassCode code, long version) {
    PurchasingRiskClass riskClass = new PurchasingRiskClass();
    riskClass.setId(UUID.randomUUID());
    riskClass.setCode(code);
    riskClass.setVersion(version);
    riskClass.setMaxTotal(100L);
    riskClass.setMaxAdvancePercent(10);
    return riskClass;
  }

  private static UpdatePurchasingSettingsRequest request(
      boolean enabled, Set<UUID> areas, UUID account, Set<UUID> tiers, long version) {
    return new UpdatePurchasingSettingsRequest(
        enabled, areas, account, 5_000L, 20_000L, BudgetPeriod.QUARTER, 14, tiers, version);
  }

  @Test
  void theSurfaceIsOffUntilTheSwitchHoldsTrue() {
    when(systemSettings.getSettingValue(ENABLED_KEY)).thenReturn(Optional.empty());
    assertThat(service.isEnabled()).isFalse();

    when(systemSettings.getSettingValue(ENABLED_KEY)).thenReturn(Optional.of(" true "));
    assertThat(service.isEnabled()).isTrue();

    when(systemSettings.getSettingValue(ENABLED_KEY)).thenReturn(Optional.of("yes"));
    assertThat(service.isEnabled()).isFalse();
  }

  @Test
  void aSwitchedOffSurfaceAnswersWithTheDisabledProblem() {
    when(systemSettings.getSettingValue(ENABLED_KEY)).thenReturn(Optional.of("false"));

    assertThatThrownBy(() -> service.requireEnabled())
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_DISABLED));
  }

  @Test
  void aMissingSettingsRowIsNotFound() {
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.current()).isInstanceOf(NotFoundException.class);
  }

  @Test
  void theGoverningAreaIdsAreEmptyUntilOneIsSetAndSortedAfterwards() {
    PurchasingSetting setting = setting(0L);
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting));
    assertThat(service.governingAreaIds()).isEmpty();

    UUID low = new UUID(0L, 1L);
    UUID high = new UUID(0L, 2L);
    setting.getGoverningAreaIds().addAll(Set.of(high, low));
    assertThat(service.governingAreaIds()).containsExactly(low, high);
  }

  @Test
  void readResolvesTheAreaNameTheAccountAndTheSortedTiers() {
    Bereich area = new Bereich();
    area.setId(UUID.randomUUID());
    area.setName("Bereich Profit");
    UUID accountId = UUID.randomUUID();
    PurchasingSetting setting = setting(4L);
    setting.getGoverningAreaIds().add(area.getId());
    setting.setFundingAccountId(accountId);
    setting.setBudgetCap(9_000L);
    setting.setPaused(true);
    setting.setPauseReason(PauseReason.BUDGET);
    UUID tierLow = new UUID(0L, 1L);
    UUID tierHigh = new UUID(0L, 2L);
    setting.getAllowedQualityTierIds().addAll(Set.of(tierHigh, tierLow));
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting));
    when(systemSettings.getSettingValue(ENABLED_KEY)).thenReturn(Optional.of("true"));
    when(orgUnitRepository.findAllById(Set.of(area.getId()))).thenReturn(List.of(area));
    when(fundingAccounts.findActive(accountId))
        .thenReturn(Optional.of(new FundingAccountRef(accountId, "KRT-1", "KRT", "CARTEL")));

    PurchasingSettingsDto dto = service.read();

    assertThat(dto.enabled()).isTrue();
    assertThat(dto.governingAreas())
        .containsExactly(new PurchasingRefDto(area.getId(), "Bereich Profit"));
    assertThat(dto.fundingAccountNo()).isEqualTo("KRT-1");
    assertThat(dto.fundingAccountName()).isEqualTo("KRT");
    assertThat(dto.budgetCap()).isEqualTo(9_000L);
    assertThat(dto.defaultMonthlyLimit()).isEqualTo(1_000L);
    assertThat(dto.allowedQualityTierIds()).containsExactly(tierLow, tierHigh);
    assertThat(dto.paused()).isTrue();
    assertThat(dto.pauseReason()).isEqualTo(PauseReason.BUDGET);
    assertThat(dto.version()).isEqualTo(4L);
  }

  @Test
  void readLeavesNamesEmptyWhenTheAccountIsNoLongerUsable() {
    PurchasingSetting setting = setting(1L);
    setting.setFundingAccountId(UUID.randomUUID());
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting));
    when(systemSettings.getSettingValue(ENABLED_KEY)).thenReturn(Optional.empty());
    when(fundingAccounts.findActive(setting.getFundingAccountId())).thenReturn(Optional.empty());

    PurchasingSettingsDto dto = service.read();

    assertThat(dto.enabled()).isFalse();
    assertThat(dto.governingAreas()).isEmpty();
    assertThat(dto.fundingAccountId()).isEqualTo(setting.getFundingAccountId());
    assertThat(dto.fundingAccountNo()).isNull();
    assertThat(dto.fundingAccountName()).isNull();
  }

  @Test
  void anUpdateStoresTheSettingsTheSwitchAndAuditsTheChange() {
    Bereich area = new Bereich();
    area.setId(UUID.randomUUID());
    area.setName("Bereich Profit");
    UUID accountId = UUID.randomUUID();
    Set<UUID> tiers = Set.of(UUID.randomUUID(), UUID.randomUUID());
    PurchasingSetting setting = setting(3L);
    setting.getAllowedQualityTierIds().add(UUID.randomUUID());
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting));
    when(orgUnitRepository.findById(area.getId())).thenReturn(Optional.of(area));
    when(orgUnitRepository.findAllById(Set.of(area.getId()))).thenReturn(List.of(area));
    when(fundingAccounts.findActive(accountId))
        .thenReturn(Optional.of(new FundingAccountRef(accountId, "KRT-1", "KRT", "CARTEL")));
    when(qualityTierRepository.findAllById(tiers))
        .thenReturn(List.of(new QualityTier(), new QualityTier()));
    when(systemSettings.getSettingValue(ENABLED_KEY)).thenReturn(Optional.of("false"));
    when(settingRepository.saveAndFlush(setting)).thenReturn(setting);

    PurchasingSettingsDto dto =
        service.update(request(true, Set.of(area.getId()), accountId, tiers, 3L));

    assertThat(setting.getGoverningAreaIds()).containsExactly(area.getId());
    assertThat(setting.getFundingAccountId()).isEqualTo(accountId);
    assertThat(setting.getDefaultMonthlyLimit()).isEqualTo(5_000L);
    assertThat(setting.getBudgetCap()).isEqualTo(20_000L);
    assertThat(setting.getBudgetPeriod()).isEqualTo(BudgetPeriod.QUARTER);
    assertThat(setting.getSettlementDueDays()).isEqualTo(14);
    assertThat(setting.getAllowedQualityTierIds()).containsExactlyInAnyOrderElementsOf(tiers);
    assertThat(dto.governingAreas())
        .containsExactly(new PurchasingRefDto(area.getId(), "Bereich Profit"));
    verify(systemSettings).putSettingValue(ENABLED_KEY, "true");
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PURCHASING_SETTINGS_CHANGED),
            eq(PurchasingSetting.SINGLETON_ID),
            any(),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .contains("enabled=true", "wasEnabled=false", "budgetPeriod=QUARTER", "tiers=2");
  }

  @Test
  void anUpdateWithoutOptionalValuesClearsThemAndLooksNothingUp() {
    PurchasingSetting setting = setting(0L);
    setting.getGoverningAreaIds().add(UUID.randomUUID());
    setting.setFundingAccountId(UUID.randomUUID());
    setting.getAllowedQualityTierIds().add(UUID.randomUUID());
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting));
    when(systemSettings.getSettingValue(ENABLED_KEY)).thenReturn(Optional.empty());
    when(settingRepository.saveAndFlush(setting)).thenReturn(setting);

    PurchasingSettingsDto dto = service.update(request(false, null, null, null, 0L));

    assertThat(setting.getGoverningAreaIds()).isEmpty();
    assertThat(setting.getFundingAccountId()).isNull();
    assertThat(setting.getAllowedQualityTierIds()).isEmpty();
    assertThat(dto.allowedQualityTierIds()).isEmpty();
    verify(systemSettings).putSettingValue(ENABLED_KEY, "false");
    verify(orgUnitRepository, never()).findById(any());
    verify(qualityTierRepository, never()).findAllById(any());
  }

  @Test
  void anUpdateWithAStaleVersionIsRefusedBeforeAnythingIsStored() {
    PurchasingSetting setting = setting(3L);
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting));

    assertThatThrownBy(() -> service.update(request(true, null, null, null, 2L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(settingRepository, never()).saveAndFlush(any());
    verify(systemSettings, never()).putSettingValue(any(), any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void theGoverningUnitMustBeABereich() {
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting(0L)));
    when(orgUnitRepository.findById(squadron.getId())).thenReturn(Optional.of(squadron));

    assertThatThrownBy(
            () -> service.update(request(true, Set.of(squadron.getId()), null, null, 0L)))
        .isInstanceOf(BadRequestException.class);

    verify(settingRepository, never()).saveAndFlush(any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void anUnusableFundingAccountIsRefusedWithItsProblemCode() {
    UUID accountId = UUID.randomUUID();
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting(0L)));
    when(fundingAccounts.findActive(accountId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(request(true, null, accountId, null, 0L)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_FUNDING_ACCOUNT_MISSING));

    verify(settingRepository, never()).saveAndFlush(any());
  }

  @Test
  void anUnknownQualityTierIsABadRequest() {
    Set<UUID> tiers = new HashSet<>(List.of(UUID.randomUUID(), UUID.randomUUID()));
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting(0L)));
    when(qualityTierRepository.findAllById(tiers)).thenReturn(List.of(new QualityTier()));

    assertThatThrownBy(() -> service.update(request(true, null, null, tiers, 0L)))
        .isInstanceOf(BadRequestException.class);

    verify(settingRepository, never()).saveAndFlush(any());
    verify(systemSettings, never()).putSettingValue(any(), any());
  }

  @Test
  void theRiskClassTableIsReadInCodeOrder() {
    PurchasingRiskClass a = riskClass(RiskClassCode.A, 1L);
    PurchasingRiskClass b = riskClass(RiskClassCode.B, 2L);
    b.setRequiresKnownPartner(true);
    b.setEscortSuggested(true);
    when(riskClassRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(a, b));

    List<RiskClassDto> dtos = service.riskClasses();

    assertThat(dtos)
        .extracting(RiskClassDto::code)
        .containsExactly(RiskClassCode.A, RiskClassCode.B);
    assertThat(dtos.get(1).requiresKnownPartner()).isTrue();
    assertThat(dtos.get(1).escortSuggested()).isTrue();
    assertThat(dtos.get(1).version()).isEqualTo(2L);
  }

  @Test
  void updatingRiskClassesChangesOnlyTheNamedOnesAndAuditsThem() {
    PurchasingRiskClass a = riskClass(RiskClassCode.A, 1L);
    PurchasingRiskClass b = riskClass(RiskClassCode.B, 2L);
    PurchasingRiskClass c = riskClass(RiskClassCode.C, 3L);
    when(riskClassRepository.findAll()).thenReturn(List.of(a, b, c));
    when(riskClassRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(a, b, c));
    UpdateRiskClassesRequest request =
        new UpdateRiskClassesRequest(
            List.of(
                new UpdateRiskClassesRequest.UpdateRiskClassEntry(
                    RiskClassCode.A, 500L, 25, false, false, 1L),
                new UpdateRiskClassesRequest.UpdateRiskClassEntry(
                    RiskClassCode.B, 900L, 50, true, true, 2L)));

    List<RiskClassDto> result = service.updateRiskClasses(request);

    assertThat(a.getMaxTotal()).isEqualTo(500L);
    assertThat(a.getMaxAdvancePercent()).isEqualTo(25);
    assertThat(b.getMaxTotal()).isEqualTo(900L);
    assertThat(b.isRequiresKnownPartner()).isTrue();
    assertThat(b.isEscortSuggested()).isTrue();
    assertThat(c.getMaxTotal()).isEqualTo(100L);
    assertThat(result).hasSize(3);
    verify(riskClassRepository).flush();
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.RISK_CLASSES_CHANGED),
            isNull(),
            eq("Risk classes"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .contains("classes=2", "AMaxTotal=500", "BMaxTotal=900");
  }

  @Test
  void aStaleRiskClassVersionIsRefusedWithoutAudit() {
    PurchasingRiskClass a = riskClass(RiskClassCode.A, 5L);
    when(riskClassRepository.findAll()).thenReturn(List.of(a));
    UpdateRiskClassesRequest request =
        new UpdateRiskClassesRequest(
            List.of(
                new UpdateRiskClassesRequest.UpdateRiskClassEntry(
                    RiskClassCode.A, 500L, 25, false, false, 4L)));

    assertThatThrownBy(() -> service.updateRiskClasses(request))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(riskClassRepository, never()).flush();
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void aClassMissingFromTheTableIsABadRequest() {
    when(riskClassRepository.findAll()).thenReturn(List.of(riskClass(RiskClassCode.A, 1L)));
    UpdateRiskClassesRequest request =
        new UpdateRiskClassesRequest(
            List.of(
                new UpdateRiskClassesRequest.UpdateRiskClassEntry(
                    RiskClassCode.C, 500L, 25, false, false, 1L)));

    assertThatThrownBy(() -> service.updateRiskClasses(request))
        .isInstanceOf(BadRequestException.class);

    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }
}
