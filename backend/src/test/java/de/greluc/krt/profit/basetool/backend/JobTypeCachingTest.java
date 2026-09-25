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

package de.greluc.krt.profit.basetool.backend;

import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.service.JobTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobTypeCachingTest {

  @Autowired private JobTypeService jobTypeService;

  @Autowired private JobTypeRepository jobTypeRepository;

  @Autowired private CacheManager cacheManager;

  @BeforeEach
  void initData() {
    jobTypeRepository.deleteAll();
    JobType a = new JobType();
    a.setName("Alpha");
    a.setArchetype(JobTypeArchetype.CREW);
    jobTypeRepository.save(a);

    JobType b = new JobType();
    b.setName("Beta");
    b.setArchetype(JobTypeArchetype.CREW);
    jobTypeRepository.save(b);

    Cache cache = cacheManager.getCache("jobTypes");
    if (cache != null) {
      cache.clear();
    }
  }

  @Test
  void cacheShouldHit_OnSecondCall_WithSameParams() {
    Pageable pageable = PageRequest.of(0, 50, Sort.by("name"));

    jobTypeService.getJobTypes(null, pageable, false);
    jobTypeService.getJobTypes(null, pageable, false);

    Cache cache = cacheManager.getCache("jobTypes");
    assert cache != null;
    Object cached = cache.get(new SimpleKey(null, pageable, false));
    org.junit.jupiter.api.Assertions.assertNotNull(
        cached, "Expected cache to contain entry after first call");
  }

  @Test
  void cacheShouldEvict_OnCreate() {
    Pageable pageable = PageRequest.of(0, 50, Sort.by("name"));

    jobTypeService.getJobTypes(null, pageable, false);

    JobType c = new JobType();
    c.setName("Gamma");
    c.setArchetype(JobTypeArchetype.CREW);
    jobTypeService.createJobType(c);

    Cache cache = cacheManager.getCache("jobTypes");
    assert cache != null;
    Object cachedAfterEvict = cache.get(new SimpleKey(null, pageable, false));
    org.junit.jupiter.api.Assertions.assertNull(
        cachedAfterEvict, "Expected cache to be evicted after create operation");

    jobTypeService.getJobTypes(null, pageable, false);
    Object cachedAfterRepopulate = cache.get(new SimpleKey(null, pageable, false));
    org.junit.jupiter.api.Assertions.assertNotNull(
        cachedAfterRepopulate, "Expected cache to be repopulated after subsequent call");
  }
}
