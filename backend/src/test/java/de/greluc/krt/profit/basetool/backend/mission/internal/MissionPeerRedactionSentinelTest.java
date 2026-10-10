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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Makes a leak of a member's private data through a mission DTO unrepresentable by walking the
 * types instead of listing cases (PRV-10): every record reachable from {@link MissionDto} is filled
 * with sentinel values, peer redaction runs over it, and every {@link UserDto} left in the result
 * must hold nothing beyond the public callsign tuple.
 */
class MissionPeerRedactionSentinelTest {

  /** The components of a peer-visible user; everything else must be empty after redaction. */
  private static final Set<String> PEER_VISIBLE_USER_COMPONENTS =
      Set.of("id", "username", "displayName", "effectiveName", "rank", "inKeycloak", "version");

  /**
   * Components no record reachable from a mission may declare apart from {@link UserDto} itself.
   */
  private static final Set<String> PRIVATE_COMPONENT_NAMES =
      Set.of("email", "roles", "permissions", "description", "joinDate", "discordLinked");

  private final MissionPeerRedactor redactor = new MissionPeerRedactor();

  /**
   * Every user the redacted mission still carries is reduced to the public callsign tuple, whatever
   * path leads to it.
   */
  @Test
  void everyUserInARedactedMissionIsReducedToThePublicTuple() {
    MissionDto full = (MissionDto) fill(MissionDto.class, MissionDto.class, new HashSet<>());
    MissionDto asPeer = redactor.cleanupMissionForPeer(withoutManagingRights(full));

    List<String> leaks = new ArrayList<>();
    int usersSeen = collectUsers(asPeer, "mission", leaks, new LinkedHashSet<>());

    assertThat(leaks).isEmpty();
    assertThat(usersSeen)
        .as("the walk must reach the participant users and the ship owners, else it proves nothing")
        .isGreaterThanOrEqualTo(2);
  }

  /** A reader who does not manage the mission sees neither its owner nor its managers. */
  @Test
  void aPeerSeesNeitherOwnerNorManagers() {
    MissionDto full = (MissionDto) fill(MissionDto.class, MissionDto.class, new HashSet<>());

    MissionDto asPeer = redactor.cleanupMissionForPeer(withoutManagingRights(full));

    assertThat(asPeer.owner()).isNull();
    assertThat(asPeer.managers()).isNull();
  }

  /**
   * No record reachable from a mission other than {@link UserDto} can carry an e-mail address, a
   * role, a permission or another private member field, so a module-local user projection cannot
   * map one by accident.
   */
  @Test
  void noOtherTypeReachableFromAMissionCanCarryAPrivateMemberField() {
    Set<Class<?>> records = new TreeSetByName();
    reachableRecords(MissionDto.class, records);

    List<String> offenders = new ArrayList<>();
    for (Class<?> type : records) {
      if (type == UserDto.class) {
        continue;
      }
      for (RecordComponent component : type.getRecordComponents()) {
        if (PRIVATE_COMPONENT_NAMES.contains(component.getName())
            && type.getSimpleName().startsWith("User")) {
          offenders.add(type.getSimpleName() + "." + component.getName());
        }
      }
    }

    assertThat(offenders).isEmpty();
    assertThat(records).as("floor of reachable record types").hasSizeGreaterThanOrEqualTo(10);
  }

  /**
   * Returns a copy of the filled mission that the redactor treats as read by a peer.
   *
   * @param full the filled mission
   * @return the same mission without the edit and manager-edit capability
   */
  private static MissionDto withoutManagingRights(MissionDto full) {
    return new MissionDto(
        full.id(),
        full.name(),
        full.description(),
        full.calendarLink(),
        full.status(),
        full.meetingTime(),
        full.plannedStartTime(),
        full.actualStartTime(),
        full.plannedEndTime(),
        full.actualEndTime(),
        full.isInternal(),
        full.participants(),
        full.assignedUnits(),
        full.frequencies(),
        full.operation(),
        full.owner(),
        full.managers(),
        false,
        false,
        full.version(),
        full.coreVersion(),
        full.scheduleVersion(),
        full.flagsVersion(),
        full.checkedInParticipants(),
        full.registeredParticipants(),
        full.owningSquadron(),
        full.owningOrgUnitVersion(),
        full.partyLeadUser(),
        full.partyLeadGuestName(),
        full.partyLeadVersion(),
        full.steps(),
        full.stepsVersion(),
        full.objectives(),
        full.objectivesVersion(),
        full.meetingPoint(),
        full.ownershipVersion());
  }

  /**
   * Walks a value and records every private component of every {@link UserDto} it holds.
   *
   * @param value the value to walk
   * @param path the path to it, for the message
   * @param leaks the messages collected so far
   * @param seen identities already walked
   * @return how many users were reached
   */
  private static int collectUsers(Object value, String path, List<String> leaks, Set<Object> seen) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Collection<?> collection) {
      int users = 0;
      int index = 0;
      for (Object element : collection) {
        users += collectUsers(element, path + "[" + index++ + "]", leaks, seen);
      }
      return users;
    }
    if (!value.getClass().isRecord()) {
      return 0;
    }
    int users = 0;
    if (value instanceof UserDto user) {
      users = 1;
      for (RecordComponent component : UserDto.class.getRecordComponents()) {
        if (PEER_VISIBLE_USER_COMPONENTS.contains(component.getName())) {
          continue;
        }
        Object content = read(component, user);
        if (content != null && !Boolean.FALSE.equals(content)) {
          leaks.add(path + "." + component.getName() + " = " + content);
        }
      }
    }
    for (RecordComponent component : value.getClass().getRecordComponents()) {
      users += collectUsers(read(component, value), path + "." + component.getName(), leaks, seen);
    }
    return users;
  }

  /**
   * Reads one record component.
   *
   * @param component the component
   * @param record the record
   * @return its value
   */
  private static Object read(RecordComponent component, Object record) {
    try {
      return component.getAccessor().invoke(record);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Collects every record type reachable from a type through its components, collections and maps.
   *
   * @param type the type to start from
   * @param out the record types found
   */
  private static void reachableRecords(Class<?> type, Set<Class<?>> out) {
    if (!type.isRecord() || !out.add(type)) {
      return;
    }
    for (RecordComponent component : type.getRecordComponents()) {
      reachableFrom(component.getGenericType(), out);
    }
  }

  /**
   * Follows a generic type to the record types inside it.
   *
   * @param type the type
   * @param out the record types found
   */
  private static void reachableFrom(Type type, Set<Class<?>> out) {
    if (type instanceof Class<?> raw) {
      reachableRecords(raw, out);
    } else if (type instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        reachableFrom(argument, out);
      }
    }
  }

  /**
   * Builds a value of a type with a sentinel in every leaf, recursing through records.
   *
   * @param type the raw type
   * @param generic the generic type, for element types
   * @param stack the record types being built, to cut cycles
   * @return the value
   */
  private static Object fill(Type generic, Class<?> type, Set<Class<?>> stack) {
    if (type == String.class) {
      return "SENTINEL-" + UUID.randomUUID();
    }
    if (type == UUID.class) {
      return UUID.randomUUID();
    }
    if (type == Boolean.class || type == boolean.class) {
      return Boolean.TRUE;
    }
    if (type == Integer.class || type == int.class) {
      return 7;
    }
    if (type == Long.class || type == long.class) {
      return 7L;
    }
    if (type == Double.class || type == double.class) {
      return 7.5;
    }
    if (type == java.math.BigDecimal.class) {
      return java.math.BigDecimal.TEN;
    }
    if (type == LocalDate.class) {
      return LocalDate.of(2026, 1, 2);
    }
    if (type == LocalDateTime.class) {
      return LocalDateTime.of(2026, 1, 2, 3, 4);
    }
    if (type == Instant.class) {
      return Instant.parse("2026-01-02T03:04:05Z");
    }
    if (type == OffsetDateTime.class) {
      return OffsetDateTime.parse("2026-01-02T03:04:05Z");
    }
    if (type.isEnum()) {
      return type.getEnumConstants()[0];
    }
    if (Set.class.isAssignableFrom(type) || Collection.class == type) {
      Object element = fill(elementType(generic), rawOf(elementType(generic)), stack);
      Set<Object> set = new LinkedHashSet<>();
      if (element != null) {
        set.add(element);
      }
      return set;
    }
    if (List.class.isAssignableFrom(type)) {
      Object element = fill(elementType(generic), rawOf(elementType(generic)), stack);
      List<Object> list = new ArrayList<>();
      if (element != null) {
        list.add(element);
      }
      return list;
    }
    if (Map.class.isAssignableFrom(type)) {
      return Map.of();
    }
    if (type.isRecord()) {
      if (!stack.add(type)) {
        return null;
      }
      try {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
          types[i] = components[i].getType();
          args[i] = fill(components[i].getGenericType(), components[i].getType(), stack);
          if (args[i] == null && types[i].isPrimitive()) {
            args[i] = defaultOf(types[i]);
          }
        }
        return type.getDeclaredConstructor(types).newInstance(args);
      } catch (ReflectiveOperationException e) {
        throw new AssertionError("cannot build " + type.getName(), e);
      } finally {
        stack.remove(type);
      }
    }
    return null;
  }

  /**
   * Returns the type argument of a one-parameter generic type.
   *
   * @param generic the generic type
   * @return its first type argument, or {@code Object}
   */
  private static Type elementType(Type generic) {
    if (generic instanceof ParameterizedType parameterized
        && parameterized.getActualTypeArguments().length > 0) {
      return parameterized.getActualTypeArguments()[0];
    }
    return Object.class;
  }

  /**
   * Returns the raw class of a type.
   *
   * @param type the type
   * @return its raw class, or {@code Object}
   */
  private static Class<?> rawOf(Type type) {
    if (type instanceof Class<?> raw) {
      return raw;
    }
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() instanceof Class<?> raw) {
      return raw;
    }
    return Object.class;
  }

  /**
   * Returns the default value of a primitive type.
   *
   * @param primitive the primitive class
   * @return its zero value
   */
  private static Object defaultOf(Class<?> primitive) {
    if (primitive == boolean.class) {
      return false;
    }
    if (primitive == int.class) {
      return 0;
    }
    if (primitive == long.class) {
      return 0L;
    }
    if (primitive == double.class) {
      return 0.0;
    }
    return 0;
  }

  /** A sorted set of classes by name, so the floor assertion lists them stably. */
  private static final class TreeSetByName extends TreeSet<Class<?>> {

    private static final long serialVersionUID = 1L;

    TreeSetByName() {
      super(java.util.Comparator.comparing(Class::getName));
    }
  }
}
