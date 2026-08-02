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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.support.Permissions;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link RoleService#updatePermissions} — the one mutation in the audited "Rollen"
 * area that rewrites what a role may DO. {@code role_permissions} keeps current state only, so the
 * audit row and the log line are the sole record of the previous grant and the acting admin
 * (REQ-AUDIT-001).
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

  @Mock private RoleRepository roleRepository;
  @Mock private AuditService auditService;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private RoleService roleService;

  private static final UUID ACTOR = UUID.fromString("11111111-2222-3333-4444-555555555555");

  private Role officer;

  @BeforeEach
  void setUp() {
    officer = new Role();
    officer.setId(2L);
    officer.setCode("OFFICER");
    officer.setName("Officer");
    officer.setDescription("Sensitive free text that must never be logged or audited.");
    officer.setPermissions(
        new HashSet<>(Set.of(Permissions.HANGAR_READ, Permissions.MISSION_READ)));
  }

  @Test
  void updatePermissions_recordsTheSymmetricDifferenceAsAnAuditEvent() {
    when(roleRepository.findByName("Officer")).thenReturn(Optional.of(officer));
    when(roleRepository.save(officer)).thenReturn(officer);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACTOR));

    roleService.updatePermissions(
        "Officer", Set.of(Permissions.HANGAR_READ, Permissions.ROLE_MANAGE));

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.ROLE_PERMISSIONS_CHANGED),
            isNull(),
            eq("OFFICER"),
            isNull(),
            details.capture());
    // MISSION_READ went, ROLE_MANAGE arrived, HANGAR_READ is unchanged and therefore absent.
    assertEquals("added=ROLE_MANAGE removed=MISSION_READ", details.getValue().toString());
  }

  @Test
  void updatePermissions_rendersAnEmptySideAsADash_andKeepsUnknownValuesOutOfThePayload() {
    // The endpoint takes a bare Set<String> body, so a permission string is client-supplied text:
    // anything outside the fixed Permissions vocabulary is applied but never named in the payload.
    when(roleRepository.findByName("Officer")).thenReturn(Optional.of(officer));
    when(roleRepository.save(officer)).thenReturn(officer);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACTOR));

    roleService.updatePermissions(
        "Officer",
        Set.of(Permissions.HANGAR_READ, Permissions.MISSION_READ, "NOT_A_REAL_PERMISSION"));

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(any(AuditEventType.class), isNull(), eq("OFFICER"), isNull(), details.capture());
    assertEquals("added=- removed=-", details.getValue().toString());
  }

  @Test
  void updatePermissions_logsTheChangeAtInfo_withTheActorSubAndWithoutTheDescription() {
    when(roleRepository.findByName("Officer")).thenReturn(Optional.of(officer));
    when(roleRepository.save(officer)).thenReturn(officer);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACTOR));

    Logger logger = (Logger) LoggerFactory.getLogger(RoleService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      roleService.updatePermissions("Officer", Set.of(Permissions.ROLE_MANAGE));
    } finally {
      logger.detachAppender(appender);
    }

    List<ILoggingEvent> events = appender.list;
    assertEquals(1, events.size());
    ILoggingEvent event = events.getFirst();
    // INFO, not WARN: an admin editing a role on the role-management screen is the intended use of
    // the screen, not an anomaly.
    assertEquals(Level.INFO, event.getLevel());
    String message = event.getFormattedMessage();
    assertTrue(message.contains("OFFICER"), message);
    assertTrue(message.contains(ACTOR.toString()), message);
    assertTrue(message.contains("added=ROLE_MANAGE"), message);
    assertTrue(message.contains("removed=HANGAR_READ,MISSION_READ"), message);
    assertFalse(message.contains("Sensitive free text"), message);
  }

  @Test
  void updatePermissions_onAnUnknownRole_throwsAndAuditsNothing() {
    when(roleRepository.findByName("Ghost")).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> roleService.updatePermissions("Ghost", Set.of()));

    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }
}
