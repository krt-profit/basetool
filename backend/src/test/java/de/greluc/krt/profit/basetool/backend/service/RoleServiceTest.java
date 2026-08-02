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
import static org.mockito.Mockito.times;
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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
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

  /** A permission value that is deliberately absent from {@link Permissions}. */
  private static final String NOT_A_PERMISSION = "NOT_A_REAL_PERMISSION";

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

    // MISSION_READ went, ROLE_MANAGE arrived, HANGAR_READ is unchanged and therefore absent.
    assertEquals(
        "added=ROLE_MANAGE removed=MISSION_READ unknownAdded=0 unknownRemoved=0",
        capturedDetails());
  }

  @Test
  void updatePermissions_rendersAnEmptySideAsADash_andCountsUnknownValuesWithoutNamingThem() {
    // The endpoint takes a bare Set<String> body, so a permission string is client-supplied text:
    // anything outside the fixed Permissions vocabulary is applied but never named in the payload.
    // It must still leave a trace, otherwise this edit is indistinguishable from a no-op change.
    when(roleRepository.findByName("Officer")).thenReturn(Optional.of(officer));
    when(roleRepository.save(officer)).thenReturn(officer);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACTOR));

    roleService.updatePermissions(
        "Officer", Set.of(Permissions.HANGAR_READ, Permissions.MISSION_READ, NOT_A_PERMISSION));

    String details = capturedDetails();
    assertEquals("added=- removed=- unknownAdded=1 unknownRemoved=0", details);
    assertFalse(details.contains(NOT_A_PERMISSION), details);
  }

  @Test
  void updatePermissions_countsAnUnknownPermissionThatIsRevoked() {
    officer.setPermissions(new HashSet<>(Set.of(Permissions.HANGAR_READ, NOT_A_PERMISSION)));
    when(roleRepository.findByName("Officer")).thenReturn(Optional.of(officer));
    when(roleRepository.save(officer)).thenReturn(officer);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACTOR));

    roleService.updatePermissions("Officer", Set.of(Permissions.HANGAR_READ));

    String details = capturedDetails();
    assertEquals("added=- removed=- unknownAdded=0 unknownRemoved=1", details);
    assertFalse(details.contains(NOT_A_PERMISSION), details);
  }

  @Test
  void updatePermissions_doesNotCountAnUnknownPermissionThatTheEditLeavesInPlace() {
    // Only a *changed* out-of-vocabulary member may raise the tally: a leftover the admin did not
    // touch would otherwise report a phantom change on every single save of the role.
    officer.setPermissions(new HashSet<>(Set.of(Permissions.HANGAR_READ, NOT_A_PERMISSION)));
    when(roleRepository.findByName("Officer")).thenReturn(Optional.of(officer));
    when(roleRepository.save(officer)).thenReturn(officer);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACTOR));

    roleService.updatePermissions(
        "Officer", Set.of(Permissions.HANGAR_READ, Permissions.MISSION_READ, NOT_A_PERMISSION));

    assertEquals("added=MISSION_READ removed=- unknownAdded=0 unknownRemoved=0", capturedDetails());
  }

  @Test
  void updatePermissions_countsANullElementInTheRequestBodyInsteadOfFailing() {
    // The request body is a raw Set<String>, so a JSON null reaches the difference; probing an
    // immutable Set with it would throw and turn an otherwise valid admin edit into a 500.
    when(roleRepository.findByName("Officer")).thenReturn(Optional.of(officer));
    when(roleRepository.save(officer)).thenReturn(officer);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACTOR));

    roleService.updatePermissions(
        "Officer",
        new HashSet<>(Arrays.asList(Permissions.HANGAR_READ, Permissions.MISSION_READ, null)));

    assertEquals("added=- removed=- unknownAdded=1 unknownRemoved=0", capturedDetails());
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
    assertTrue(message.contains("unknownAdded=0"), message);
    assertTrue(message.contains("unknownRemoved=0"), message);
    assertFalse(message.contains("Sensitive free text"), message);
  }

  @Test
  void updatePermissions_logsAnUnknownValueAsACountAndNeverAsText() {
    when(roleRepository.findByName("Officer")).thenReturn(Optional.of(officer));
    when(roleRepository.save(officer)).thenReturn(officer);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACTOR));

    Logger logger = (Logger) LoggerFactory.getLogger(RoleService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      roleService.updatePermissions(
          "Officer",
          Set.of(Permissions.HANGAR_READ, Permissions.MISSION_READ, NOT_A_PERMISSION, "%n%nFAKE"));
    } finally {
      logger.detachAppender(appender);
    }

    assertEquals(1, appender.list.size());
    String message = appender.list.getFirst().getFormattedMessage();
    assertTrue(message.contains("unknownAdded=2"), message);
    // Log forging: a client-supplied permission string must never be interpolated into the line.
    assertFalse(message.contains(NOT_A_PERMISSION), message);
    assertFalse(message.contains("FAKE"), message);
  }

  @Test
  void updatePermissions_onAnUnknownRole_throwsAndAuditsNothing() {
    when(roleRepository.findByName("Ghost")).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> roleService.updatePermissions("Ghost", Set.of()));

    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void auditVocabularyNamesExactlyThePermissionsConstants() {
    // Parity between the audited vocabulary and the Permissions holder, in the shape used by
    // ClientErrorReportControllerTest#beaconModuleShipsExactlyTheServerSideKindAllowlist: the two
    // halves drift silently otherwise. A permission the vocabulary does not know is still applied,
    // yet its audit row would read "added=-" — a grant of rights that looks like a no-op.
    List<String> constants = declaredPermissionConstants();
    assertFalse(constants.isEmpty(), "Permissions declares no constants");
    when(roleRepository.findByName("Officer")).thenReturn(Optional.of(officer));
    when(roleRepository.save(officer)).thenReturn(officer);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ACTOR));

    for (String permission : constants) {
      officer.setPermissions(new HashSet<>());
      roleService.updatePermissions("Officer", Set.of(permission));
    }
    officer.setPermissions(new HashSet<>());
    roleService.updatePermissions("Officer", Set.of(NOT_A_PERMISSION));

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService, times(constants.size() + 1))
        .record(any(AuditEventType.class), isNull(), eq("OFFICER"), isNull(), details.capture());
    List<CharSequence> captured = details.getAllValues();
    for (int i = 0; i < constants.size(); i++) {
      assertEquals(
          "added=" + constants.get(i) + " removed=- unknownAdded=0 unknownRemoved=0",
          captured.get(i).toString(),
          "Permissions."
              + constants.get(i)
              + " is not part of the audited vocabulary: RoleService must derive it from the"
              + " Permissions constants, never from a hand-written list");
    }
    // The counterpart: a value outside the holder stays a count, so the vocabulary is closed.
    assertEquals(
        "added=- removed=- unknownAdded=1 unknownRemoved=0", captured.getLast().toString());
  }

  /**
   * The {@code details} payload of the single expected {@code auditService.record(...)} call, as a
   * string.
   *
   * @return the rendered {@code key=value} audit detail payload
   */
  private String capturedDetails() {
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.ROLE_PERMISSIONS_CHANGED),
            isNull(),
            eq("OFFICER"),
            isNull(),
            details.capture());
    return details.getValue().toString();
  }

  /**
   * The permission values {@link Permissions} declares, read independently of {@code RoleService}
   * so the parity test above compares two halves rather than one value with itself. Also asserts
   * the two properties the reflective derivation relies on: every public constant in the holder is
   * a {@code String}, and its value equals its field name — a constant of any other kind would
   * silently join the audited vocabulary.
   *
   * @return the declared permission constant values
   */
  private static List<String> declaredPermissionConstants() {
    List<String> constants = new ArrayList<>();
    for (Field field : Permissions.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (field.isSynthetic() || !Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)) {
        continue;
      }
      assertEquals(
          String.class,
          field.getType(),
          "Permissions."
              + field.getName()
              + " is not a String, but RoleService derives the audited permission vocabulary from"
              + " this holder — it must carry permission values only");
      try {
        String value = (String) field.get(null);
        assertEquals(
            field.getName(),
            value,
            "Permissions."
                + field.getName()
                + " does not carry its own name as its value; if that is intentional, revisit"
                + " whether it is a permission at all — every public String constant here becomes"
                + " an auditable permission name");
        constants.add(value);
      } catch (IllegalAccessException e) {
        throw new AssertionError("Cannot read Permissions." + field.getName(), e);
      }
    }
    return List.copyOf(constants);
  }
}
