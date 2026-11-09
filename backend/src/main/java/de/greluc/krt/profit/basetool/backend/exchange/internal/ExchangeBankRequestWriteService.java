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

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestWrites;
import de.greluc.krt.profit.basetool.backend.bank.api.BankProblemCode;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCancelledDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCorrectionDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCreateDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestWrittenDto;
import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Raises, corrects and cancels the member's own booking requests for an exchange client
 * (REQ-BXCH-004) through {@link BankOwnRequestWrites}, for the member's actor without Admin ({@link
 * BankActors#forExchange}). It decides nothing itself: every rule is the bank's.
 *
 * <p>Every write counts on {@code basetool_exchange_writes_total{resource="bank_request"}}, a
 * shared {@code 404} and a cap refusal also on {@code basetool_exchange_bank_refused_total}
 * (REQ-OBS-024). There is no exchange journal and no undo: a request books nothing.
 */
@Service
@RequiredArgsConstructor
public class ExchangeBankRequestWriteService {

  private final BankActors bankActors;
  private final BankOwnRequestWrites requestWrites;
  private final ExchangeBankMetrics metrics;

  /**
   * Raises a request.
   *
   * @param caller the acting member and client
   * @param body the request
   * @return the terse answer
   */
  @NotNull
  public ExchangeBankRequestWrittenDto raise(
      @NotNull ExchangeCaller caller, @NotNull ExchangeBankRequestCreateDto body) {
    return counted(
        caller,
        () ->
            written(
                requestWrites.raise(
                    actor(caller),
                    new BankOwnRequestWrites.NewRequest(
                        body.type(),
                        body.sourceAccountId(),
                        body.targetAccountId(),
                        body.amount(),
                        body.note(),
                        body.justification(),
                        body.splitEnabled(),
                        body.splitPercent()),
                    caller.clientId())));
  }

  /**
   * Corrects one of the member's pending requests.
   *
   * @param caller the acting member and client
   * @param requestId the request
   * @param body the corrected values and the echoed version
   * @return the terse answer
   */
  @NotNull
  public ExchangeBankRequestWrittenDto correct(
      @NotNull ExchangeCaller caller,
      @NotNull UUID requestId,
      @NotNull ExchangeBankRequestCorrectionDto body) {
    return counted(
        caller,
        () ->
            written(
                requestWrites.correct(
                    actor(caller),
                    requestId,
                    new BankOwnRequestWrites.Correction(
                        body.amount(),
                        body.note(),
                        body.justification(),
                        body.targetAccountId(),
                        body.version()))));
  }

  /**
   * Cancels one of the member's pending requests.
   *
   * @param caller the acting member and client
   * @param requestId the request
   * @param version the echoed version
   * @return the cancelled request
   */
  @NotNull
  public ExchangeBankRequestCancelledDto cancel(
      @NotNull ExchangeCaller caller, @NotNull UUID requestId, long version) {
    return counted(
        caller,
        () -> {
          BankOwnRequestWrites.Written cancelled =
              requestWrites.cancel(actor(caller), requestId, version);
          return new ExchangeBankRequestCancelledDto(
              cancelled.id(), cancelled.status(), cancelled.version());
        });
  }

  /**
   * Runs one write and counts its outcome, and the refusal reason of a shared {@code 404} or a cap
   * refusal.
   *
   * @param caller the acting member and client
   * @param write the write
   * @param <T> the answer's type
   * @return the answer
   */
  @NotNull
  private <T> T counted(@NotNull ExchangeCaller caller, @NotNull Supplier<T> write) {
    try {
      T answer = write.get();
      metrics.countWrite(caller.clientId(), ExchangeOps.APPLIED);
      return answer;
    } catch (NotFoundException e) {
      metrics.countRefused(BankExchangeRefusal.NOT_FOUND);
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw e;
    } catch (DomainProblem e) {
      if (BankProblemCode.BANK_OPEN_REQUEST_CAP.code().equals(e.code())) {
        metrics.countRefused(BankExchangeRefusal.OPEN_REQUEST_CAP);
      }
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw e;
    } catch (RuntimeException e) {
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw e;
    }
  }

  /**
   * The member's bank actor.
   *
   * @param caller the acting member and client
   * @return the actor without Admin
   */
  @NotNull
  private BankActor actor(@NotNull ExchangeCaller caller) {
    return bankActors.forExchange(caller.member());
  }

  /**
   * Shapes the terse answer; money is a whole JSON integer.
   *
   * @param written the request after the write
   * @return the answer
   */
  @NotNull
  private static ExchangeBankRequestWrittenDto written(
      @NotNull BankOwnRequestWrites.Written written) {
    return new ExchangeBankRequestWrittenDto(
        written.id(), written.status(), written.version(), written.amount().longValueExact());
  }
}
