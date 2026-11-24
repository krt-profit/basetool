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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderWithTargetDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderWrittenDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientWriteRefusedException;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderInformationCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderReadCheck;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatusCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderTargetCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The writes of an exchange client on an existing aUEC order (REQ-OXCH-005), through the web's
 * commands under {@link JobOrderWriterRule#DIRECT_LOGISTICIAN}, the read check {@link
 * JobOrderReadCheck#OWN_RELATION} and the member with the client as the actor (ADR-0408). It
 * decides nothing itself: the commands decide every rule under the order locks, in the exchange's
 * order, and the service only answers their refusals with the exchange's codes.
 *
 * <p>Every write counts on {@code basetool_exchange_writes_total{resource="job_order"}} and every
 * refusal on {@code basetool_exchange_job_order_refused_total} under its reason (REQ-OBS-025). The
 * commands publish the frames and notices after the commit, as for a web write.
 */
@Service
@RequiredArgsConstructor
public class ExchangeJobOrderChangeService {

  private final JobOrderStatusCommand statusCommand;
  private final JobOrderTargetCommand targetCommand;
  private final JobOrderInformationCommand informationCommand;
  private final ExchangeJobOrderAnswers answers;
  private final ExchangeJobOrderMetrics metrics;

  /** Runs a write and the read of its answer in one transaction. */
  private final TransactionTemplate transaction;

  /**
   * Cancels an active aUEC order of a unit the member writes for, while no money operation of the
   * order was accepted (REQ-OXCH-006).
   *
   * @param caller the acting member and client
   * @param orderId the order
   * @param lifecycleVersion the lifecycle version the client last read
   * @return the order's id, status and new versions
   * @throws NotFoundException for an unknown, hidden or deleted order, alike
   * @throws AccessDeniedException for an order the member does not write for, or one of another
   *     kind than aUEC
   * @throws ObjectOptimisticLockingFailureException when the lifecycle version is stale
   * @throws ExchangeProblemException {@code 409 JOB_ORDER_RESPONSIBILITY_INVALID}, {@code 409
   *     JOB_ORDER_CLOSED} or {@code 409 JOB_ORDER_MONEY_STARTED}
   */
  @NotNull
  public ExchangeJobOrderWrittenDto cancel(
      @NotNull ExchangeCaller caller, @NotNull UUID orderId, long lifecycleVersion) {
    return written(
        caller,
        orderId,
        () -> statusCommand.cancel(orderId, lifecycleVersion, caller.jobOrderCaller()),
        answers::written);
  }

  /**
   * Changes the target of an active aUEC order of a unit the member writes for, in either
   * direction, to an amount above the order's net handover, so the order is neither completed nor
   * reopened (REQ-OXCH-007).
   *
   * @param caller the acting member and client
   * @param orderId the order
   * @param target the new target in whole aUEC
   * @param targetVersion the target version the client last read
   * @return the order's id, status, new versions and stored target
   * @throws NotFoundException for an unknown, hidden or deleted order, alike
   * @throws AccessDeniedException for an order the member does not write for, or one of another
   *     kind than aUEC
   * @throws ObjectOptimisticLockingFailureException when the target version is stale
   * @throws ExchangeProblemException {@code 409 JOB_ORDER_RESPONSIBILITY_INVALID}, {@code 409
   *     JOB_ORDER_CLOSED} or {@code 409 JOB_ORDER_TARGET_TOO_LOW}
   */
  @NotNull
  public ExchangeJobOrderWithTargetDto changeTarget(
      @NotNull ExchangeCaller caller, @NotNull UUID orderId, long target, long targetVersion) {
    return written(
        caller,
        orderId,
        () ->
            targetCommand.changeTarget(
                orderId, BigDecimal.valueOf(target), targetVersion, caller.jobOrderCaller()),
        answers::withTarget);
  }

  /**
   * Replaces the comment of an aUEC order of a unit the member writes for, under the comment's own
   * version, and leaves the contact untouched (REQ-OXCH-008).
   *
   * @param caller the acting member and client
   * @param orderId the order
   * @param comment the new comment; {@code null} or blank clears it
   * @param commentVersion the comment version the client last read
   * @return the order's id, status and new versions
   * @throws NotFoundException for an unknown, hidden or deleted order, alike
   * @throws AccessDeniedException for an order the member does not write for, or one of another
   *     kind than aUEC
   * @throws ObjectOptimisticLockingFailureException when the comment version is stale
   * @throws ExchangeProblemException {@code 409 JOB_ORDER_RESPONSIBILITY_INVALID} or {@code 409
   *     JOB_ORDER_CLOSED}
   */
  @NotNull
  public ExchangeJobOrderWrittenDto comment(
      @NotNull ExchangeCaller caller,
      @NotNull UUID orderId,
      @Nullable String comment,
      long commentVersion) {
    return written(
        caller,
        orderId,
        () ->
            informationCommand.changeComment(
                orderId, comment, commentVersion, caller.jobOrderCaller()),
        answers::written);
  }

  /**
   * Runs one write on an existing order with the read of its answer in one transaction, and counts
   * its outcome and the reason of a refusal.
   *
   * @param caller the acting member and the client the outcome is counted for
   * @param orderId the order the write edits
   * @param write the command call
   * @param answer the answer read from the written order in the write's transaction
   * @param <T> the answer's type
   * @return the answer
   */
  private <T> @NotNull T written(
      @NotNull ExchangeCaller caller,
      @NotNull UUID orderId,
      @NotNull Runnable write,
      @NotNull Function<UUID, T> answer) {
    try {
      T written =
          Objects.requireNonNull(
              transaction.execute(_ -> {
                write.run();
                return answer.apply(orderId);
              }));
      metrics.countWrite(caller.clientId(), ExchangeOps.APPLIED);
      return written;
    } catch (NotFoundException e) {
      throw metrics.refusedWrite(caller.clientId(), JobOrderExchangeRefusal.NOT_FOUND, e);
    } catch (AccessDeniedException e) {
      throw metrics.refusedWrite(caller.clientId(), JobOrderExchangeRefusal.NOT_PERMITTED, e);
    } catch (ObjectOptimisticLockingFailureException e) {
      throw metrics.refusedWrite(caller.clientId(), JobOrderExchangeRefusal.VERSION_CONFLICT, e);
    } catch (JobOrderClientWriteRefusedException e) {
      throw switch (e.getRefusal()) {
        case RESPONSIBILITY_INVALID ->
            metrics.refusedWrite(
                caller.clientId(),
                JobOrderExchangeRefusal.RESPONSIBILITY_INVALID,
                ExchangeProblemException.jobOrderResponsibilityInvalid(e));
        case CLOSED ->
            metrics.refusedWrite(
                caller.clientId(),
                JobOrderExchangeRefusal.CLOSED,
                ExchangeProblemException.jobOrderClosed(e));
        case MONEY_STARTED ->
            metrics.refusedWrite(
                caller.clientId(),
                JobOrderExchangeRefusal.MONEY_STARTED,
                ExchangeProblemException.jobOrderMoneyStarted(e));
        case TARGET_TOO_LOW ->
            metrics.refusedWrite(
                caller.clientId(),
                JobOrderExchangeRefusal.TARGET_TOO_LOW,
                ExchangeProblemException.jobOrderTargetTooLow(e));
      };
    } catch (RuntimeException e) {
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw e;
    }
  }
}
