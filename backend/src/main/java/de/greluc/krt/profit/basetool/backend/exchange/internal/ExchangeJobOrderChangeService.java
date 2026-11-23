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
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderWrittenDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientWriteRefusedException;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderInformationCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderReadCheck;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatusCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
  private final JobOrderInformationCommand informationCommand;
  private final JobOrderExchangeReads reads;
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
        () ->
            statusCommand.cancel(
                orderId,
                lifecycleVersion,
                JobOrderWriterRule.DIRECT_LOGISTICIAN,
                JobOrderReadCheck.OWN_RELATION,
                new JobOrderActor.Client(caller.member(), caller.clientId())));
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
                orderId,
                comment,
                commentVersion,
                JobOrderWriterRule.DIRECT_LOGISTICIAN,
                JobOrderReadCheck.OWN_RELATION,
                new JobOrderActor.Client(caller.member(), caller.clientId())));
  }

  /**
   * Runs one write on an existing order with the read of its answer in one transaction, and counts
   * its outcome and the reason of a refusal.
   *
   * @param caller the acting member and the client the outcome is counted for
   * @param orderId the order the write edits
   * @param write the command call
   * @return the order's id, status and versions after the write
   */
  private @NotNull ExchangeJobOrderWrittenDto written(
      @NotNull ExchangeCaller caller, @NotNull UUID orderId, @NotNull Runnable write) {
    try {
      ExchangeJobOrderWrittenDto answer =
          Objects.requireNonNull(
              transaction.execute(_ -> {
                write.run();
                return answer(orderId);
              }));
      metrics.countWrite(caller.clientId(), ExchangeOps.APPLIED);
      return answer;
    } catch (NotFoundException e) {
      throw refused(caller, JobOrderExchangeRefusal.NOT_FOUND, e);
    } catch (AccessDeniedException e) {
      throw refused(caller, JobOrderExchangeRefusal.NOT_PERMITTED, e);
    } catch (ObjectOptimisticLockingFailureException e) {
      throw refused(caller, JobOrderExchangeRefusal.VERSION_CONFLICT, e);
    } catch (JobOrderClientWriteRefusedException e) {
      throw switch (e.getRefusal()) {
        case RESPONSIBILITY_INVALID ->
            refused(
                caller,
                JobOrderExchangeRefusal.RESPONSIBILITY_INVALID,
                ExchangeProblemException.jobOrderResponsibilityInvalid(e));
        case CLOSED ->
            refused(
                caller, JobOrderExchangeRefusal.CLOSED, ExchangeProblemException.jobOrderClosed(e));
        case MONEY_STARTED ->
            refused(
                caller,
                JobOrderExchangeRefusal.MONEY_STARTED,
                ExchangeProblemException.jobOrderMoneyStarted(e));
      };
    } catch (RuntimeException e) {
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw e;
    }
  }

  /**
   * Counts a refused write under its reason and hands back the refusal to throw.
   *
   * @param caller the acting member and the client the outcome is counted for
   * @param reason the bounded reason
   * @param refusal the exception the client's answer comes from
   * @param <E> the exception's type
   * @return the refusal
   */
  private <E extends RuntimeException> @NotNull E refused(
      @NotNull ExchangeCaller caller, @NotNull JobOrderExchangeRefusal reason, @NotNull E refusal) {
    metrics.countRefused(reason);
    metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
    return refusal;
  }

  /**
   * Reads the written order's terse answer in the write's transaction.
   *
   * @param orderId the written order
   * @return its id, status and versions
   */
  private @NotNull ExchangeJobOrderWrittenDto answer(@NotNull UUID orderId) {
    JobOrderExchangeRead read =
        Objects.requireNonNull(reads.project(List.of(orderId)).get(orderId), "written order");
    return new ExchangeJobOrderWrittenDto(
        read.id(), read.status(), ExchangeJobOrderDto.Versions.of(read.versions()));
  }
}
