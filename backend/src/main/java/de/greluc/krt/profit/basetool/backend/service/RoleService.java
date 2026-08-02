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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
   * The closed permission vocabulary the audit payload and the log line are allowed to name,
   * derived by reflection from the {@code public static final String} constants {@link Permissions}
   * declares. Deriving it rather than copying it is the point: the earlier hand-maintained list
   * silently excluded every permission constant added after it was written, so a grant or revoke of
   * such a permission produced an audit row reading {@code added=- removed=-}.
   *
   * <p>The endpoint accepts an arbitrary {@code Set<String>} body, so a permission string is
   * client-supplied text; rendering the difference through this fixed set keeps free text out of
   * the {@code details} payload (REQ-AUDIT-001) and out of the logger (log forging). Values outside
   * the vocabulary are still applied — that behaviour is unchanged — and are reported as the {@code
   * unknownAdded} / {@code unknownRemoved} counts, never by value.
   */
  private static final Set<String> KNOWN_PERMISSIONS = readPermissionVocabulary();

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
   * (added / removed, over the closed {@link Permissions} vocabulary) plus the {@code unknownAdded}
   * / {@code unknownRemoved} tallies of the changed members that vocabulary cannot name, and one
   * INFO line with the same four values and the actor's {@code sub}. INFO, not WARN: an admin
   * editing a role is the intended use of the screen, not an anomaly. The role's free-text
   * description and the list of affected users are deliberately absent from both.
   *
   * <p>The tallies exist so a change is never invisible: a permission outside the vocabulary is
   * persisted like any other, and without a count the audit row for such an edit would read {@code
   * added=- removed=-} as though nothing had happened. A non-zero {@code unknown*} value therefore
   * means "this edit moved something this build cannot name" — the value itself stays out of both
   * sinks because it is client-supplied free text.
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
   * Splits the members of {@code from} that {@code to} does not contain into the ones the closed
   * {@link #KNOWN_PERMISSIONS} vocabulary can name — sorted, so the rendered difference is stable
   * across calls — and a plain count of the ones it cannot.
   *
   * <p>Membership in {@code to} is tested first for every non-{@code null} candidate, so a
   * permission outside the vocabulary that is present on both sides stays uncounted: an unchanged
   * leftover must not read as a change on every subsequent save.
   *
   * <p>The {@code null} check runs before any lookup into {@code to}, which keeps a stray {@code
   * null} element in the request body (the endpoint takes a raw {@code Set<String>}) from turning
   * the audit composition into a 500 on an otherwise valid admin edit — an immutable {@code Set}
   * throws on {@code contains(null)}. Such an element counts as out-of-vocabulary even in the
   * practically unreachable case where {@code to} carries one too.
   *
   * @param from the side whose exclusive members are wanted
   * @param to the side subtracted from it
   * @return the sorted in-vocabulary difference plus the number of out-of-vocabulary members; both
   *     parts possibly empty / zero
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
   * Reads the audited permission vocabulary out of {@link Permissions} by reflection over its
   * declared {@code public static final String} constants, so the vocabulary <em>is</em> the
   * constant holder instead of a copy that can fall behind it.
   *
   * <p>Synthetic fields (coverage instrumentation injects one) and any non-public, non-static or
   * non-{@code String} member are skipped. {@link Permissions} is a public final holder of public
   * constants in this module, so the read needs no {@code setAccessible} call.
   *
   * @return the immutable set of permission strings the audit payload and the log line may name
   * @throws IllegalStateException if a constant cannot be read, which would leave the audit trail
   *     permanently blind to that permission
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

  /**
   * One side of the permission difference, split by whether the closed {@link #KNOWN_PERMISSIONS}
   * vocabulary can name a member: named members reach the audit row and the log line verbatim, the
   * rest only as a count, because an unnamed member is client-supplied free text and must not enter
   * either sink (REQ-AUDIT-001, log forging).
   *
   * @param named the sorted in-vocabulary members, safe to render by value
   * @param unknownCount how many changed members fell outside the vocabulary; the sole trace such a
   *     member leaves, and non-zero only when the edit really moved one
   */
  private record PermissionDifference(@NotNull List<String> named, int unknownCount) {}
}
