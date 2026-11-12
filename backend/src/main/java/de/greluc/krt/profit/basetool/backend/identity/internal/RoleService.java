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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UnknownRolePermissionException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.Permissions;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.CacheConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the {@code role} table: the application roles held in {@code user_roles} (REQ-SEC-088)
 * with their permission sets. Only description and permissions are editable; writes evict the
 * {@code roles} cache.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RoleService {

  /** Rendered in place of an empty added/removed list so every detail keeps a non-empty value. */
  private static final String NONE = "-";

  private final RoleRepository roleRepository;
  private final AuditRecorder auditRecorder;
  private final AuthHelperService authHelperService;

  /**
   * Returns a cached page of immutable role read models with batch-loaded permission sets
   * (REQ-DATA-003).
   *
   * @param pageable page request
   * @return cached page of detached role records
   */
  @Cacheable(cacheNames = CacheConfig.ROLES_CACHE)
  public Page<RoleDto> getAllRoles(@NotNull Pageable pageable) {
    return roleRepository.findAll(pageable).map(RoleMapper::snapshot);
  }

  /**
   * Replaces the permission set of the named role; takes effect on the next authentication.
   *
   * <p>Records a {@link AuditEventType#ROLE_PERMISSIONS_CHANGED} event and an INFO line with the
   * added and removed permissions from the {@link Permissions} vocabulary and a count of unknown
   * permissions removed from storage (REQ-AUDIT-001).
   *
   * @param roleName role display name, matched case-sensitively
   * @param permissions new permission set
   * @param version the expected permission-section version
   * @return the persisted role
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no role matches
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.ROLES_CACHE, allEntries = true)
  public Role updatePermissions(
      @NotNull String roleName, @NotNull Set<String> permissions, @NotNull Long version) {
    if (!permissions.stream().allMatch(Permissions::isKnown)) {
      throw new UnknownRolePermissionException();
    }
    Role role = Entities.require(roleRepository.lockByName(roleName), "Role not found");
    OptimisticLock.check(role.getPermissionsVersion(), version, Role.class, role.getId());
    role.setPermissionsVersion(role.getPermissionsVersion() + 1L);
    Set<String> previous = new HashSet<>(role.getPermissions());
    role.setPermissions(permissions);
    Role saved = roleRepository.saveAndFlush(role);

    PermissionDifference added = difference(permissions, previous);
    PermissionDifference removed = difference(previous, permissions);
    auditRecorder.record(
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
   * Permissions} and a count of unknown ones.
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
        if (Permissions.isKnown(candidate)) {
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
   * Updates the descriptive text for a role.
   *
   * @param roleName role display name
   * @param description new description
   * @param version the expected description-section version
   * @return the persisted role
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no role matches
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.ROLES_CACHE, allEntries = true)
  public Role updateRoleDescription(
      @NotNull String roleName, @NotNull String description, @NotNull Long version) {
    Role role = Entities.require(roleRepository.lockByName(roleName), "Role not found");
    OptimisticLock.check(role.getDescriptionVersion(), version, Role.class, role.getId());
    role.setDescriptionVersion(role.getDescriptionVersion() + 1L);
    role.setDescription(description);
    Role saved = roleRepository.saveAndFlush(role);
    auditRecorder.record(
        AuditEventType.ROLE_DESCRIPTION_CHANGED,
        null,
        role.getCode(),
        null,
        AuditDetails.of("sectionVersion", role.getDescriptionVersion()));
    return saved;
  }

  /**
   * One side of a permission difference, with unknown values represented only by their count.
   *
   * @param named the sorted in-vocabulary members
   * @param unknownCount how many changed members fell outside the vocabulary
   */
  private record PermissionDifference(@NotNull List<String> named, int unknownCount) {}
}
