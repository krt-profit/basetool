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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.notification.api.TimedNoticeProducer;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class NotificationTimedRunnerTest {

  private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

  @Mock private NotificationRepository notificationRepository;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final List<String> calls = new ArrayList<>();

  private static final class NoOpTransactionManager implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }

  private NotificationTimedRunner runnerOf(TimedNoticeProducer... producers) {
    return new NotificationTimedRunner(
        List.of(producers), notificationRepository, meterRegistry, new NoOpTransactionManager());
  }

  private TimedNoticeProducer producer(String kind, int count) {
    return new TimedNoticeProducer() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public int produce(Instant now) {
        calls.add(kind + "@" + now);
        return count;
      }
    };
  }

  private TimedNoticeProducer failing(String kind) {
    return new TimedNoticeProducer() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public int produce(Instant now) {
        calls.add(kind);
        throw new IllegalStateException("boom " + kind);
      }
    };
  }

  private double produced(String kind) {
    return meterRegistry
        .counter(MetricNames.NOTIFICATION_TIMED_PRODUCED, MetricNames.TAG_KIND, kind)
        .count();
  }

  @BeforeEach
  void takeTheLock() {
    when(notificationRepository.tryTimedProducerLock(NotificationTimedRunner.LOCK_KEY))
        .thenReturn(true);
  }

  @Test
  void runsEveryProducerAndSumsWhatTheyPublished() {
    int total = runnerOf(producer("a", 2), producer("b", 3)).runOnce(NOW);

    assertThat(total).isEqualTo(5);
    assertThat(calls).containsExactly("a@" + NOW, "b@" + NOW);
    assertThat(produced("a")).isEqualTo(2);
    assertThat(produced("b")).isEqualTo(3);
  }

  @Test
  void skipsTheRunWhenAnotherInstanceHoldsTheLock() {
    when(notificationRepository.tryTimedProducerLock(NotificationTimedRunner.LOCK_KEY))
        .thenReturn(false);

    int total = runnerOf(producer("a", 2)).runOnce(NOW);

    assertThat(total).isZero();
    assertThat(calls).isEmpty();
    verify(notificationRepository).tryTimedProducerLock(NotificationTimedRunner.LOCK_KEY);
  }

  @Test
  void aFailingProducerDoesNotStopTheOthersAndIsRethrown() {
    NotificationTimedRunner runner = runnerOf(failing("bad"), producer("good", 4));

    assertThatThrownBy(() -> runner.runOnce(NOW))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom bad");

    assertThat(calls).containsExactly("bad", "good@" + NOW);
    assertThat(produced("good")).isEqualTo(4);
  }

  @Test
  void withoutProducersTheRunIsEmpty() {
    assertThat(runnerOf().runOnce(NOW)).isZero();
  }
}
