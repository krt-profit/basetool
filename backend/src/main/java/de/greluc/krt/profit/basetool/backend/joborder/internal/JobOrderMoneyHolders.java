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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Resolves order-local holder selections without guessing links from text. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyHolders {

  private static final Set<UUID> NO_PREFERRED_ACCOUNTS = Set.of(new UUID(0L, 0L));
  private final JobOrderMoneyHolderRepository holders;
  private final UserRepository users;

  private enum UnavailableReason {
    MERGED_HOLDER,
    INACTIVE_ACCOUNT
  }

  /** Identifies a holder selection whose observed identity is no longer available. */
  static final class UnavailableHolderException extends RuntimeException {
    private final UnavailableReason reason;

    private UnavailableHolderException(UnavailableReason reason) {
      this.reason = reason;
    }

    RuntimeException previewProblem() {
      return switch (reason) {
        case MERGED_HOLDER -> new BusinessConflictException("orders.money.holder.linkChanged");
        case INACTIVE_ACCOUNT -> new BadRequestException("orders.money.holder.accountInactive");
      };
    }
  }

  JobOrderMoneyHolder resolve(UUID orderId, UUID actor, JobOrderMoneySelection selection) {
    return switch (selection.kind()) {
      case SELF -> linked(orderId, actor);
      case EXISTING -> {
        JobOrderMoneyHolder holder =
            holders
                .findByOrderIdAndId(orderId, Objects.requireNonNull(selection.reference()))
                .orElseThrow(() -> new BadRequestException("orders.money.holder.notFound"));
        if (holder.getMergedIntoId() != null) {
          throw new UnavailableHolderException(UnavailableReason.MERGED_HOLDER);
        }
        yield holder;
      }
      case LINKED -> {
        UUID id = Objects.requireNonNull(selection.reference());
        if (activeUser(id).isEmpty()) {
          throw new UnavailableHolderException(UnavailableReason.INACTIVE_ACCOUNT);
        }
        yield linked(orderId, id);
      }
      case ACCOUNTLESS ->
          JobOrderMoneyHolder.named(orderId, Objects.requireNonNull(selection.name()));
    };
  }

  Optional<User> activeUser(UUID id) {
    return users.findById(id).filter(User::isActiveAccount);
  }

  List<JobOrderMoneyHolderCandidate> candidates(@Nullable String query) {
    return users
        .searchActiveReferences(
            LikePatterns.escape(query == null ? "" : query.strip()),
            NO_PREFERRED_ACCOUNTS,
            PageRequest.of(0, JobOrderAssigneeCandidateService.CANDIDATE_LIMIT))
        .stream()
        .map(account -> new JobOrderMoneyHolderCandidate(account.id(), account.effectiveName()))
        .toList();
  }

  private JobOrderMoneyHolder linked(UUID orderId, UUID userId) {
    return holders
        .findByOrderIdAndUserId(orderId, userId)
        .orElseGet(() -> JobOrderMoneyHolder.linked(orderId, userId));
  }

  @Nullable
  String nameOf(JobOrderMoneyHolder holder) {
    return holder.getUserId() == null ? holder.getAccountlessName() : nameOf(holder.getUserId());
  }

  @Nullable
  String nameOf(@Nullable UUID userId) {
    return userId == null ? null : users.findById(userId).map(User::getEffectiveName).orElse(null);
  }

  Map<UUID, String> namesOf(Iterable<UUID> userIds) {
    return users.findAllById(userIds).stream()
        .collect(Collectors.toUnmodifiableMap(User::getId, User::getEffectiveName));
  }
}
