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
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.Permissions;
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
 * Manages the {@code role} table that holds the local copy of every Keycloak realm role plus the
 * project-specific permission set attached to each role.
 *
 * <p>The role names are populated by {@link
 * de.greluc.krt.profit.basetool.backend.config.DataInitializer} at boot (matched by {@code code},
 * not by {@code name} — see CLAUDE.md). This service only handles the editable subset: description
 * and permission set. Cache is the {@code roles} cache, evicted on every write so a refreshed
 * permission set takes effect immediately for the next authentication.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RoleService {

  /**
   * The closed permission vocabulary ({@link Permissions}) the audit payload and the log line are
   * allowed to name. The endpoint accepts an arbitrary {@code Set<String>} body, so a permission
   * string is client-supplied text; rendering the difference through this fixed set keeps free text
   * out of the {@code details} payload (REQ-AUDIT-001) and out of the logger (log forging). Values
   * outside it are still applied — that behaviour is unchanged — but are only ever counted.
   */
  private static final Set<String> KNOWN_PERMISSIONS =
      Set.of(
          Permissions.HANGAR_READ,
          Permissions.HANGAR_WRITE,
          Permissions.MISSION_READ,
          Permissions.MISSION_WRITE,
          Permissions.MISSION_MANAGE,
          Permissions.USER_MANAGE,
          Permissions.ROLE_MANAGE);

  /** Rendered in place of an empty added/removed list so every detail keeps a non-empty value. */
  private static final String NONE = "-";

  private final RoleRepository roleRepository;
  private final AuditService auditService;
  private final AuthHelperService authHelperService;

  /**
   * Paged role list.
   *
   * @param pageable page request
   * @return cached page result
   */
  @Cacheable(cacheNames = CacheConfig.ROLES_CACHE)
  public Page<Role> getAllRoles(@NotNull Pageable pageable) {
    return roleRepository.findAll(pageable);
  }

  /**
   * Replaces the permission set for the named role. Used by the role-management page; the
   * JWT-to-authorities converter re-reads permissions on every authentication so the change
   * propagates without a server restart.
   *
   * <p>"Rollen" is an audited area (REQ-AUDIT-001), and this is the one mutation in it that
   * rewrites what a role may DO rather than who holds it. {@code role_permissions} keeps current
   * state only, so the previous grant and the acting admin are unrecoverable from the table itself
   * — hence a {@link AuditEventType#ROLE_PERMISSIONS_CHANGED} row carrying the symmetric difference
   * (added / removed, over the closed {@link Permissions} vocabulary), plus one INFO line with the
   * same difference and the actor's {@code sub}. INFO, not WARN: an admin editing a role is the
   * intended use of the screen, not an anomaly. The role's free-text description and the list of
   * affected users are deliberately absent from both.
   *
   * <p>Concurrency: the role row carries a JPA {@code @Version}, so two admins committing edits to
   * the same role still collide into a 409 at flush. The write API takes no client-echoed version
   * (the request body is a bare permission set), so no {@code support.OptimisticLock} check applies
   * here; the snapshot below is read before the mutation so the audited difference is the one this
   * transaction actually applied.
   *
   * @param roleName role display name (looked up case-sensitively via repository's findByName)
   * @param permissions new permission set
   * @return the persisted role
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no role matches
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.ROLES_CACHE, allEntries = true)
  public Role updatePermissions(@NotNull String roleName, @NotNull Set<String> permissions) {
    Role role =
        roleRepository
            .findByName(roleName)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "Role not found"));
    // Snapshot BEFORE the setter: setPermissions replaces the ElementCollection wholesale, after
    // which the previous grant is gone from memory as well as from the row.
    Set<String> previous = new HashSet<>(role.getPermissions());
    role.setPermissions(permissions);
    Role saved = roleRepository.save(role);

    List<String> added = difference(permissions, previous);
    List<String> removed = difference(previous, permissions);
    auditService.record(
        AuditEventType.ROLE_PERMISSIONS_CHANGED,
        null,
        role.getCode(),
        null,
        AuditDetails.of("added", render(added)).with("removed", render(removed)));
    log.info(
        "Role permissions changed for role code {} by actor {}: added={} removed={}",
        role.getCode(),
        authHelperService.currentUserId().orElse(null),
        render(added),
        render(removed));
    return saved;
  }

  /**
   * The members of {@code from} that {@code to} does not contain, restricted to the closed {@link
   * #KNOWN_PERMISSIONS} vocabulary and sorted so the rendered difference is stable across calls.
   *
   * <p>The vocabulary filter runs before any lookup into {@code to}, which keeps a stray {@code
   * null} element in the request body (the endpoint takes a raw {@code Set<String>}) from turning
   * the audit composition into a 500 on an otherwise valid admin edit.
   *
   * @param from the side whose exclusive members are wanted
   * @param to the side subtracted from it
   * @return the sorted, vocabulary-filtered difference; possibly empty
   */
  @NotNull
  private static List<String> difference(@NotNull Set<String> from, @NotNull Set<String> to) {
    Set<String> diff = new TreeSet<>();
    for (String candidate : from) {
      if (candidate != null && KNOWN_PERMISSIONS.contains(candidate) && !to.contains(candidate)) {
        diff.add(candidate);
      }
    }
    return List.copyOf(diff);
  }

  /**
   * Renders one side of the difference as a comma-separated list for the audit payload and the log
   * line, collapsing the empty case to {@link #NONE} so the {@code key=value} detail never ends up
   * with an empty value.
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
   * @return the persisted role
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no role matches
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.ROLES_CACHE, allEntries = true)
  public Role updateRoleDescription(@NotNull String roleName, @NotNull String description) {
    Role role =
        roleRepository
            .findByName(roleName)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "Role not found"));
    role.setDescription(description);
    return roleRepository.save(role);
  }
}
