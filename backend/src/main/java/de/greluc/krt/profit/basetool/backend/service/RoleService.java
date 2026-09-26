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

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.Permissions;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the {@code role} table: the local copy of every Keycloak realm role with its permission
 * set. Only description and permissions are editable; writes evict the {@code roles} cache.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RoleService {

  /**
   * The permission names audit rows and log lines may name, read from the constants of {@link
   * Permissions}. Values outside it are still applied but reported only as counts (REQ-AUDIT-001).
   */
  private static final Set<String> KNOWN_PERMISSIONS = readPermissionVocabulary();

  /** Rendered in place of an empty added/removed list so every detail keeps a non-empty value. */
  private static final String NONE = "-";

  private final RoleRepository roleRepository;
  private final AuditService auditService;
  private final AuthHelperService authHelperService;

  /**
   * Returns a cached page of roles with every role's permission set initialised, batch-loaded
   * (REQ-DATA-003).
   *
   * @param pageable page request
   * @return cached page result, permissions initialised
   */
  @Cacheable(cacheNames = CacheConfig.ROLES_CACHE)
  public Page<Role> getAllRoles(@NotNull Pageable pageable) {
    Page<Role> page = roleRepository.findAll(pageable);
    page.forEach(role -> Hibernate.initialize(role.getPermissions()));
    return page;
  }

  /**
   * Replaces the permission set of the named role; takes effect on the next authentication.
   *
   * <p>Records a {@link AuditEventType#ROLE_PERMISSIONS_CHANGED} event and an INFO line with the
   * added and removed permissions from the {@link Permissions} vocabulary plus counts of unknown
   * ones (REQ-AUDIT-001). Concurrent edits of the same role collide with a 409.
   *
   * @param roleName role display name, matched case-sensitively
   * @param permissions new permission set
   * @return the persisted role
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no role matches
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.ROLES_CACHE, allEntries = true)
  public Role updatePermissions(@NotNull String roleName, @NotNull Set<String> permissions) {
    Role role = Entities.require(roleRepository.findByName(roleName), "Role not found");
    Set<String> previous = new HashSet<>(role.getPermissions());
    role.setPermissions(permissions);
    Role saved = roleRepository.save(role);

    PermissionDifference added = difference(permissions, previous);
    PermissionDifference removed = difference(previous, permissions);
    auditService.record(
        AuditEventType.ROLE_PERMISSIONS_CHANGED,
        null,
        role.getCode(),
        null,
        AuditDetails.of("added", render(added.named()))
            .with("removed", render(removed.named()))
            .with("unknownAdded", added.unknownCount())
            .with("unknownRemoved", removed.unknownCount()));
    log.info(
        "Role permissions changed for role code {} by actor {}: added={} removed={}"
            + " unknownAdded={} unknownRemoved={}",
        role.getCode(),
        authHelperService.currentUserId().orElse(null),
        render(added.named()),
        render(removed.named()),
        added.unknownCount(),
        removed.unknownCount());
    return saved;
  }

  /**
   * Splits the members of {@code from} missing from {@code to} into sorted names known to {@link
   * #KNOWN_PERMISSIONS} and a count of unknown ones. A {@code null} element counts as unknown.
   *
   * @param from the side whose exclusive members are wanted
   * @param to the side subtracted from it
   * @return the sorted in-vocabulary difference plus the out-of-vocabulary count
   */
  @NotNull
  private static PermissionDifference difference(
      @NotNull Set<String> from, @NotNull Set<String> to) {
    Set<String> named = new TreeSet<>();
    int unknown = 0;
    for (String candidate : from) {
      if (candidate == null) {
        unknown++;
      } else if (!to.contains(candidate)) {
        if (KNOWN_PERMISSIONS.contains(candidate)) {
          named.add(candidate);
        } else {
          unknown++;
        }
      }
    }
    return new PermissionDifference(List.copyOf(named), unknown);
  }

  /**
   * Joins one side of the difference with commas for the audit payload and the log line.
   *
   * @param permissions the sorted difference to render
   * @return the joined permission names, or {@code "-"} when there are none
   */
  @NotNull
  private static String render(@NotNull List<String> permissions) {
    return permissions.isEmpty() ? NONE : String.join(",", permissions);
  }

  /**
   * Reads the {@code public static final String} constants of {@link Permissions} by reflection,
   * skipping synthetic fields.
   *
   * @return the immutable set of permission strings the audit payload and the log line may name
   * @throws IllegalStateException if a constant cannot be read
   */
  @NotNull
  private static Set<String> readPermissionVocabulary() {
    Set<String> vocabulary = new HashSet<>();
    for (Field field : Permissions.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (field.isSynthetic()
          || !Modifier.isPublic(modifiers)
          || !Modifier.isStatic(modifiers)
          || !Modifier.isFinal(modifiers)
          || field.getType() != String.class) {
        continue;
      }
      try {
        vocabulary.add((String) field.get(null));
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(
            "Cannot read permission constant " + field.getName() + " for the audit vocabulary", e);
      }
    }
    return Set.copyOf(vocabulary);
  }

  /**
   * Updates the descriptive text for a role.
   *
   * @param roleName role display name
   * @param description new description
   * @return the persisted role
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no role matches
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.ROLES_CACHE, allEntries = true)
  public Role updateRoleDescription(@NotNull String roleName, @NotNull String description) {
    Role role = Entities.require(roleRepository.findByName(roleName), "Role not found");
    role.setDescription(description);
    return roleRepository.save(role);
  }

  /**
   * One side of a permission difference: members named in {@link #KNOWN_PERMISSIONS}, and a count
   * of the rest, which never reach the audit or log (REQ-AUDIT-001).
   *
   * @param named the sorted in-vocabulary members
   * @param unknownCount how many changed members fell outside the vocabulary
   */
  private record PermissionDifference(@NotNull List<String> named, int unknownCount) {}
}
