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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PauseReason;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.events.PurchasingNotices;
import java.time.Instant;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

/**
 * The kill switch of purchasing for the organisation and for single raising units (REQ-PUR-019).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingPauseServiceTest {

  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingSettingRepository settingRepository;
  @Mock private PurchasingUnitPauseRepository unitPauseRepository;
  @Mock private PurchasingDelegateService delegateService;
  @Mock private PurchasingAccessPolicy accessPolicy;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private AuditRecorder auditRecorder;
  @Mock private ApplicationEventPublisher eventPublisher;

  private PurchasingPauseService service;

  @BeforeEach
  void setUp() {
    service =
        new PurchasingPauseService(
            settingsService,
            settingRepository,
            unitPauseRepository,
            delegateService,
            accessPolicy,
            orgUnitRepository,
            authHelperService,
            auditRecorder,
            eventPublisher);
  }

  private static Squadron squadron(String name) {
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setName(name);
    return squadron;
  }

  private static PurchasingUnitPause unitPause(UUID unitId, PauseReason reason) {
    PurchasingUnitPause pause = new PurchasingUnitPause();
    pause.setId(UUID.randomUUID());
    pause.setOrgUnitId(unitId);
    pause.setReason(reason);
    pause.setPausedAt(Instant.parse("2026-10-01T10:00:00Z"));
    return pause;
  }

  private NoticeEvent publishedNotice() {
    ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(notice.capture());
    return notice.getValue();
  }

  @Test
  void theStateListsTheGlobalPauseAndTheNamedPausedUnits() {
    PurchasingSetting setting = new PurchasingSetting();
    setting.setPaused(true);
    setting.setPauseReason(PauseReason.BUDGET);
    Instant since = Instant.parse("2026-10-02T08:00:00Z");
    setting.setPausedAt(since);
    when(settingsService.current()).thenReturn(setting);
    Squadron known = squadron("Staffel Alpha");
    UUID unknownUnit = UUID.randomUUID();
    when(unitPauseRepository.findAll())
        .thenReturn(
            List.of(
                unitPause(known.getId(), PauseReason.INCIDENT),
                unitPause(unknownUnit, PauseReason.OTHER)));
    when(orgUnitRepository.findById(known.getId())).thenReturn(Optional.of(known));
    when(orgUnitRepository.findById(unknownUnit)).thenReturn(Optional.empty());

    PauseStateDto state = service.state();

    assertThat(state.globalPaused()).isTrue();
    assertThat(state.globalReason()).isEqualTo(PauseReason.BUDGET);
    assertThat(state.globalPausedAt()).isEqualTo(since);
    assertThat(state.units()).hasSize(2);
    assertThat(state.units().get(0).orgUnitName()).isEqualTo("Staffel Alpha");
    assertThat(state.units().get(0).reason()).isEqualTo(PauseReason.INCIDENT);
    assertThat(state.units().get(1).orgUnitName()).isEqualTo("—");
  }

  @Test
  void aGlobalPauseRefusesEveryUnitWithoutLookingAtTheUnitPauses() {
    PurchasingSetting setting = new PurchasingSetting();
    setting.setPaused(true);
    when(settingsService.current()).thenReturn(setting);

    assertThatThrownBy(() -> service.requireNotPaused(UUID.randomUUID()))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e -> assertThat(e.getProblemCode()).isEqualTo(PurchasingProblemCode.PURCHASING_PAUSED));

    verifyNoInteractions(unitPauseRepository);
  }

  @Test
  void aUnitPauseRefusesThatUnitOnly() {
    when(settingsService.current()).thenReturn(new PurchasingSetting());
    UUID paused = UUID.randomUUID();
    UUID running = UUID.randomUUID();
    when(unitPauseRepository.findByOrgUnitId(paused))
        .thenReturn(Optional.of(unitPause(paused, PauseReason.OTHER)));
    when(unitPauseRepository.findByOrgUnitId(running)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.requireNotPaused(paused))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e -> assertThat(e.getProblemCode()).isEqualTo(PurchasingProblemCode.PURCHASING_PAUSED));
    assertThatCode(() -> service.requireNotPaused(running)).doesNotThrowAnyException();
  }

  @Test
  void pausingTheWholeOrganisationStoresTheReasonAuditsItAndTellsTheDelegates() {
    when(accessPolicy.canPause(null)).thenReturn(true);
    UUID actor = UUID.randomUUID();
    UUID delegate = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actor));
    when(delegateService.activeDelegateIds()).thenReturn(Set.of(delegate));
    PurchasingSetting setting = new PurchasingSetting();
    when(settingsService.current()).thenReturn(setting);

    PauseStateDto state = service.pause(new PausePurchasingRequest(null, PauseReason.INCIDENT));

    assertThat(setting.isPaused()).isTrue();
    assertThat(setting.getPauseReason()).isEqualTo(PauseReason.INCIDENT);
    assertThat(setting.getPausedBy()).isEqualTo(actor);
    assertThat(setting.getPausedAt()).isNotNull();
    assertThat(state.globalPaused()).isTrue();
    verify(settingRepository).saveAndFlush(setting);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PURCHASING_PAUSED),
            eq(PurchasingNotices.GLOBAL_PAUSE_ID),
            eq("Purchasing"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).isEqualTo("scope=GLOBAL reason=INCIDENT");
    NoticeEvent notice = publishedNotice();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_PAUSED);
    assertThat(notice.entityId()).isEqualTo(PurchasingNotices.GLOBAL_PAUSE_ID);
    assertThat(notice.actorSub()).isEqualTo(actor);
    assertThat(notice.contextRecipientUserIds()).containsExactly(delegate);
    assertThat(notice.contextOrgUnits()).isEmpty();
    assertThat(notice.renderParams())
        .containsEntry("scopeCode", PurchasingNotices.SCOPE_GLOBAL)
        .containsEntry("reasonCode", "INCIDENT");
  }

  @Test
  void pausingAUnitCreatesItsPauseAndNamesTheUnitInTheNotice() {
    Squadron unit = squadron("Staffel Alpha");
    when(accessPolicy.canPause(unit.getId())).thenReturn(true);
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(unitPauseRepository.findByOrgUnitId(unit.getId())).thenReturn(Optional.empty());
    when(settingsService.current()).thenReturn(new PurchasingSetting());

    service.pause(new PausePurchasingRequest(unit.getId(), PauseReason.TRUST_CONCERN));

    ArgumentCaptor<PurchasingUnitPause> stored = ArgumentCaptor.forClass(PurchasingUnitPause.class);
    verify(unitPauseRepository).saveAndFlush(stored.capture());
    assertThat(stored.getValue().getOrgUnitId()).isEqualTo(unit.getId());
    assertThat(stored.getValue().getReason()).isEqualTo(PauseReason.TRUST_CONCERN);
    assertThat(stored.getValue().getPausedAt()).isNotNull();
    verify(settingRepository, never()).saveAndFlush(any());
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PURCHASING_PAUSED),
            eq(unit.getId()),
            eq("Staffel Alpha"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .isEqualTo("scope=UNIT unit=" + unit.getId() + " reason=TRUST_CONCERN");
    NoticeEvent notice = publishedNotice();
    assertThat(notice.entityId()).isEqualTo(unit.getId());
    assertThat(notice.contextOrgUnits())
        .containsEntry(
            NotificationContextRole.RESPONSIBLE,
            new OrgUnitRef(unit.getId(), OrgUnitKind.SQUADRON));
    assertThat(notice.renderParams())
        .containsEntry("scopeCode", PurchasingNotices.SCOPE_UNIT)
        .containsEntry("unit", "Staffel Alpha");
  }

  @Test
  void pausingAnAlreadyPausedUnitUpdatesTheExistingRow() {
    Squadron unit = squadron("Staffel Alpha");
    PurchasingUnitPause existing = unitPause(unit.getId(), PauseReason.OTHER);
    when(accessPolicy.canPause(unit.getId())).thenReturn(true);
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(unitPauseRepository.findByOrgUnitId(unit.getId())).thenReturn(Optional.of(existing));
    when(settingsService.current()).thenReturn(new PurchasingSetting());

    service.pause(new PausePurchasingRequest(unit.getId(), PauseReason.BUDGET));

    assertThat(existing.getReason()).isEqualTo(PauseReason.BUDGET);
    verify(unitPauseRepository).saveAndFlush(existing);
  }

  @Test
  void pausingAnUnknownUnitIsNotFound() {
    UUID unit = UUID.randomUUID();
    when(accessPolicy.canPause(unit)).thenReturn(true);
    when(orgUnitRepository.findById(unit)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.pause(new PausePurchasingRequest(unit, PauseReason.OTHER)))
        .isInstanceOf(NotFoundException.class);

    verify(unitPauseRepository, never()).saveAndFlush(any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void pausingIsRefusedForACallerWhoMayNotPauseThatScope() {
    when(accessPolicy.canPause(null)).thenReturn(false);

    assertThatThrownBy(() -> service.pause(new PausePurchasingRequest(null, PauseReason.OTHER)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(settingRepository, unitPauseRepository, delegateService, eventPublisher);
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void resumingTheWholeOrganisationClearsThePauseAndSupersedesTheNotices() {
    when(accessPolicy.canPause(null)).thenReturn(true);
    when(delegateService.activeDelegateIds()).thenReturn(Set.of());
    PurchasingSetting setting = new PurchasingSetting();
    setting.setPaused(true);
    setting.setPauseReason(PauseReason.BUDGET);
    setting.setPausedBy(UUID.randomUUID());
    setting.setPausedAt(Instant.now());
    when(settingsService.current()).thenReturn(setting);

    PauseStateDto state = service.resume(new ResumePurchasingRequest(null));

    assertThat(setting.isPaused()).isFalse();
    assertThat(setting.getPauseReason()).isNull();
    assertThat(setting.getPausedBy()).isNull();
    assertThat(setting.getPausedAt()).isNull();
    assertThat(state.globalPaused()).isFalse();
    verify(settingRepository).saveAndFlush(setting);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PURCHASING_RESUMED),
            eq(PurchasingNotices.GLOBAL_PAUSE_ID),
            eq("Purchasing"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).isEqualTo("scope=GLOBAL");
    NoticeEvent notice = publishedNotice();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_RESUMED);
    assertThat(notice.resolvesNotificationTypes())
        .containsExactly(NotificationType.PURCHASING_PAUSED);
  }

  @Test
  void resumingAUnitRemovesItsPauseRow() {
    Squadron unit = squadron("Staffel Alpha");
    PurchasingUnitPause existing = unitPause(unit.getId(), PauseReason.OTHER);
    when(accessPolicy.canPause(unit.getId())).thenReturn(true);
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(unitPauseRepository.findByOrgUnitId(unit.getId())).thenReturn(Optional.of(existing));
    when(settingsService.current()).thenReturn(new PurchasingSetting());

    service.resume(new ResumePurchasingRequest(unit.getId()));

    verify(unitPauseRepository).delete(existing);
    verify(unitPauseRepository).flush();
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PURCHASING_RESUMED),
            eq(unit.getId()),
            eq("Staffel Alpha"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).isEqualTo("scope=UNIT unit=" + unit.getId());
    NoticeEvent notice = publishedNotice();
    assertThat(notice.contextOrgUnits())
        .containsEntry(
            NotificationContextRole.RESPONSIBLE,
            new OrgUnitRef(unit.getId(), OrgUnitKind.SQUADRON));
    assertThat(notice.resolvesNotificationTypes())
        .containsExactly(NotificationType.PURCHASING_PAUSED);
  }

  @Test
  void resumingAUnitThatIsNotPausedDeletesNothing() {
    Squadron unit = squadron("Staffel Alpha");
    when(accessPolicy.canPause(unit.getId())).thenReturn(true);
    when(orgUnitRepository.findById(unit.getId())).thenReturn(Optional.of(unit));
    when(unitPauseRepository.findByOrgUnitId(unit.getId())).thenReturn(Optional.empty());
    when(settingsService.current()).thenReturn(new PurchasingSetting());

    service.resume(new ResumePurchasingRequest(unit.getId()));

    verify(unitPauseRepository, never()).delete(any());
  }

  @Test
  void resumingIsRefusedForACallerWhoMayNotPauseThatScope() {
    UUID unit = UUID.randomUUID();
    when(accessPolicy.canPause(unit)).thenReturn(false);

    assertThatThrownBy(() -> service.resume(new ResumePurchasingRequest(unit)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(settingRepository, unitPauseRepository, eventPublisher);
  }

  @Test
  void theAuditCodeOfAReasonIsItsName() {
    assertThat(PurchasingPauseService.code(PauseReason.TRUST_CONCERN)).isEqualTo("TRUST_CONCERN");
  }
}
