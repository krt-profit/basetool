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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.NotificationRuleMapper;
import de.greluc.krt.profit.basetool.backend.model.NotificationRule;
import de.greluc.krt.profit.basetool.backend.model.NotificationRuleSelector;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleDto;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleSelectorWriteRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationRuleWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRuleRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin CRUD service for {@link NotificationRule}s.
 *
 * <p>Updates replace the selector collection wholesale (clear + re-add, relying on orphan removal)
 * and use an explicit optimistic-lock check mirrored from {@code SystemSettingService}; {@code
 * saveAndFlush} returns the bumped version so the admin form can write it straight back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class NotificationRuleService {

  private final NotificationRuleRepository notificationRuleRepository;
  private final NotificationRuleMapper notificationRuleMapper;

  /**
   * Lists every rule (with selectors), newest first.
   *
   * @return the rule DTOs
   */
  public List<NotificationRuleDto> list() {
    return notificationRuleRepository.findAllWithSelectors().stream()
        .sorted(
            Comparator.comparing(
                NotificationRule::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .map(notificationRuleMapper::toDto)
        .toList();
  }

  /**
   * Returns one rule by id.
   *
   * @param id rule id
   * @return the rule DTO
   * @throws NotFoundException when the id is unknown
   */
  public NotificationRuleDto get(@NotNull UUID id) {
    return notificationRuleMapper.toDto(load(id));
  }

  /**
   * Creates a new rule with its selectors.
   *
   * @param request the create payload
   * @return the persisted rule DTO
   */
  @Transactional
  public NotificationRuleDto create(@NotNull NotificationRuleWriteRequest request) {
    NotificationRule rule = new NotificationRule();
    applyScalars(rule, request);
    applySelectors(rule, request);
    NotificationRule saved = notificationRuleRepository.saveAndFlush(rule);
    log.info("Created notification rule id={} eventType={}", saved.getId(), saved.getEventType());
    return notificationRuleMapper.toDto(saved);
  }

  /**
   * Updates a rule, fully replacing its selectors, with an optimistic-lock check.
   *
   * @param id rule id
   * @param request the update payload (carries the expected version)
   * @return the persisted rule DTO
   * @throws NotFoundException when the id is unknown
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  public NotificationRuleDto update(
      @NotNull UUID id, @NotNull NotificationRuleWriteRequest request) {
    NotificationRule rule = load(id);
    OptimisticLock.check(rule.getVersion(), request.version(), NotificationRule.class, id);
    applyScalars(rule, request);
    rule.clearSelectors();
    applySelectors(rule, request);
    NotificationRule saved = notificationRuleRepository.saveAndFlush(rule);
    log.info("Updated notification rule id={}", saved.getId());
    return notificationRuleMapper.toDto(saved);
  }

  /**
   * Deletes a rule and its selectors (cascade).
   *
   * @param id rule id
   * @throws NotFoundException when the id is unknown
   */
  @Transactional
  public void delete(@NotNull UUID id) {
    NotificationRule rule = load(id);
    notificationRuleRepository.delete(rule);
    log.info("Deleted notification rule id={}", id);
  }

  private void applyScalars(
      @NotNull NotificationRule rule, @NotNull NotificationRuleWriteRequest request) {
    rule.setEventType(request.eventType());
    rule.setNotificationType(request.notificationType());
    rule.setDescription(trimToNull(request.description()));
    rule.setEnabled(request.enabled());
    rule.setExcludeActor(request.excludeActor());
  }

  private void applySelectors(
      @NotNull NotificationRule rule, @NotNull NotificationRuleWriteRequest request) {
    for (NotificationRuleSelectorWriteRequest selectorRequest : request.selectors()) {
      validateSelector(selectorRequest);
      rule.addSelector(
          NotificationRuleSelector.builder()
              .kind(selectorRequest.kind())
              // The write DTO still spells the property userSub: renaming it is a breaking
              // change to the frozen contract and ships with its own deprecation window
              // (#1640, REQ-API-009). Same value, two names, for exactly as long as that takes.
              .userId(selectorRequest.userSub())
              .roleCode(trimToNull(selectorRequest.roleCode()))
              .orgRelativeRole(selectorRequest.orgRelativeRole())
              .contextRole(selectorRequest.contextRole())
              .build());
    }
  }

  private void validateSelector(@NotNull NotificationRuleSelectorWriteRequest selector) {
    switch (selector.kind()) {
      case SPECIFIC_USER -> {
        if (selector.userSub() == null) {
          throw new IllegalArgumentException("SPECIFIC_USER selector requires a user id");
        }
      }
      case ROLE -> {
        if (trimToNull(selector.roleCode()) == null) {
          throw new IllegalArgumentException("ROLE selector requires roleCode");
        }
      }
      case ORG_RELATIVE_ROLE -> {
        if (selector.orgRelativeRole() == null || selector.contextRole() == null) {
          throw new IllegalArgumentException(
              "ORG_RELATIVE_ROLE selector requires orgRelativeRole and contextRole");
        }
      }
      default ->
          throw new IllegalArgumentException("Unsupported selector kind: " + selector.kind());
    }
  }

  @NotNull
  private NotificationRule load(@NotNull UUID id) {
    return notificationRuleRepository
        .findByIdWithSelectors(id)
        .orElseThrow(() -> new NotFoundException("Notification rule not found: " + id));
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
