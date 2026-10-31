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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.events.PurchasingNotices;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appoints and revokes the Handelsbeauftragte and sets their limits (REQ-PUR-003). Only the
 * governing leadership acts; the capability is derived from the active appointment.
 */
@Service
@RequiredArgsConstructor
public class PurchasingDelegateService {

  private final PurchasingDelegateRepository delegateRepository;
  private final UserRepository userRepository;
  private final PurchasingSettingsService settingsService;
  private final PurchasingAccessPolicy accessPolicy;
  private final AuthHelperService authHelperService;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Lists every appointment, newest first.
   *
   * @return the appointments, for the governing leadership
   * @throws AccessDeniedException when the caller does not govern purchasing
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<DelegateDto> list() {
    settingsService.requireEnabled();
    requireGoverning();
    return toDtos(delegateRepository.findAllByOrderByAppointedAtDesc());
  }

  /**
   * Appoints a member (REQ-PUR-003).
   *
   * @param request the member, the optional end date and the optional limit
   * @return the appointment
   * @throws PurchasingProblemException with {@code PURCHASING_DELEGATE_ACTIVE} when the member
   *     already holds an active appointment
   * @throws AccessDeniedException when the caller does not govern purchasing
   */
  @NotNull
  @Transactional
  public DelegateDto appoint(@NotNull AppointDelegateRequest request) {
    settingsService.requireEnabled();
    requireGoverning();
    User user = Entities.require(userRepository.findById(request.userId()), "User not found");
    Instant now = Instant.now();
    if (request.validUntil() != null && !request.validUntil().isAfter(now)) {
      throw new BadRequestException("The end of the appointment lies in the past");
    }
    Optional<PurchasingDelegate> open =
        delegateRepository.findFirstByUserIdAndRevokedAtIsNull(user.getId());
    if (open.isPresent()) {
      PurchasingDelegate previous = open.get();
      if (previous.isActiveAt(now)) {
        throw new PurchasingProblemException(
            PurchasingProblemCode.PURCHASING_DELEGATE_ACTIVE,
            "The member already holds an active appointment");
      }
      previous.setRevokedAt(now);
      previous.setRevokedBy(authHelperService.currentUserId().orElse(null));
      delegateRepository.saveAndFlush(previous);
    }
    UUID actor = authHelperService.currentUserId().orElse(null);
    PurchasingDelegate delegate = new PurchasingDelegate();
    delegate.setUserId(user.getId());
    delegate.setAppointedBy(actor);
    delegate.setAppointedAt(now);
    delegate.setValidUntil(request.validUntil());
    delegate.setMonthlyLimit(request.monthlyLimit());
    PurchasingDelegate saved = delegateRepository.saveAndFlush(delegate);
    auditRecorder.record(
        AuditEventType.DELEGATE_APPOINTED,
        saved.getId(),
        "Handelsbeauftragte",
        user.getId(),
        AuditDetails.of("limit", request.monthlyLimit())
            .with(
                "validUntil",
                request.validUntil() == null ? null : request.validUntil().toString()));
    eventPublisher.publishEvent(
        PurchasingNotices.delegateAppointed(saved.getId(), user.getId(), actor));
    return toDtos(List.of(saved)).getFirst();
  }

  /**
   * Revokes an appointment; the member keeps the read access to and the settlement duty for their
   * running deals but can no longer create deals (REQ-PUR-003).
   *
   * @param id the appointment
   * @param version the echoed version
   * @return the revoked appointment
   */
  @NotNull
  @Transactional
  public DelegateDto revoke(@NotNull UUID id, long version) {
    settingsService.requireEnabled();
    requireGoverning();
    PurchasingDelegate delegate = load(id, version);
    if (delegate.getRevokedAt() != null) {
      throw new BadRequestException("The appointment is already revoked");
    }
    UUID actor = authHelperService.currentUserId().orElse(null);
    delegate.setRevokedAt(Instant.now());
    delegate.setRevokedBy(actor);
    PurchasingDelegate saved = delegateRepository.saveAndFlush(delegate);
    auditRecorder.record(
        AuditEventType.DELEGATE_REVOKED,
        saved.getId(),
        "Handelsbeauftragte",
        saved.getUserId(),
        null);
    if (saved.getUserId() != null) {
      eventPublisher.publishEvent(
          PurchasingNotices.delegateRevoked(saved.getId(), saved.getUserId(), actor));
    }
    return toDtos(List.of(saved)).getFirst();
  }

  /**
   * Changes a delegate's personal monthly limit (REQ-PUR-003).
   *
   * @param id the appointment
   * @param request the new limit and the echoed version
   * @return the appointment
   */
  @NotNull
  @Transactional
  public DelegateDto setLimit(@NotNull UUID id, @NotNull UpdateDelegateLimitRequest request) {
    settingsService.requireEnabled();
    requireGoverning();
    PurchasingDelegate delegate = load(id, request.version());
    Long was = delegate.getMonthlyLimit();
    delegate.setMonthlyLimit(request.monthlyLimit());
    PurchasingDelegate saved = delegateRepository.saveAndFlush(delegate);
    auditRecorder.record(
        AuditEventType.DELEGATE_LIMIT_CHANGED,
        saved.getId(),
        "Handelsbeauftragte",
        saved.getUserId(),
        AuditDetails.of("limit", request.monthlyLimit()).with("wasLimit", was));
    return toDtos(List.of(saved)).getFirst();
  }

  /**
   * Returns the ids of the active delegates, for the notices that name every one of them.
   *
   * @return the members whose appointment confers the capability now
   */
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> activeDelegateIds() {
    return new HashSet<>(delegateRepository.findActiveUserIds(Instant.now()));
  }

  /**
   * Returns the monthly limit that applies to a member: the personal one of their open appointment,
   * else the default.
   *
   * @param userId the delegate
   * @return the limit in whole aUEC
   */
  @Transactional(readOnly = true)
  public long effectiveLimit(@NotNull UUID userId) {
    long fallback = settingsService.current().getDefaultMonthlyLimit();
    return delegateRepository
        .findFirstByUserIdAndRevokedAtIsNull(userId)
        .map(PurchasingDelegate::getMonthlyLimit)
        .orElse(fallback);
  }

  private @NotNull PurchasingDelegate load(@NotNull UUID id, long version) {
    PurchasingDelegate delegate =
        Entities.require(delegateRepository.findById(id), "Appointment not found");
    OptimisticLock.checkRequired(delegate.getVersion(), version, PurchasingDelegate.class, id);
    return delegate;
  }

  private void requireGoverning() {
    if (!accessPolicy.canAppointDelegates()) {
      throw new AccessDeniedException("Only the governing leadership appoints delegates");
    }
  }

  private @NotNull List<DelegateDto> toDtos(@NotNull List<PurchasingDelegate> delegates) {
    Map<UUID, String> names = new HashMap<>();
    List<UUID> ids = new ArrayList<>();
    for (PurchasingDelegate delegate : delegates) {
      if (delegate.getUserId() != null) {
        ids.add(delegate.getUserId());
      }
      if (delegate.getAppointedBy() != null) {
        ids.add(delegate.getAppointedBy());
      }
    }
    for (User user : userRepository.findAllById(ids)) {
      names.put(user.getId(), user.getEffectiveName());
    }
    long fallback = settingsService.current().getDefaultMonthlyLimit();
    Instant now = Instant.now();
    return delegates.stream()
        .map(delegate ->
            new DelegateDto(
                delegate.getId(),
                delegate.getUserId(),
                delegate.getUserId() == null ? null : names.get(delegate.getUserId()),
                delegate.getAppointedAt(),
                delegate.getAppointedBy() == null ? null : names.get(delegate.getAppointedBy()),
                delegate.getValidUntil(),
                delegate.getMonthlyLimit(),
                delegate.getMonthlyLimit() == null ? fallback : delegate.getMonthlyLimit(),
                delegate.getRevokedAt(),
                delegate.isActiveAt(now),
                delegate.getVersion()))
        .toList();
  }
}
