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

import { timeOfDay } from '/js/mission/mission-template-parts.js';

/** @typedef {'meetingTime' | 'plannedStartTime' | 'plannedEndTime'} ScheduleField */
/** @typedef {Partial<Record<ScheduleField, string | null>>} TimesOfDay */
/** @typedef {{ date: HTMLInputElement, time: HTMLInputElement }} SplitField */

const FIELDS = /** @type {const} */ (['meetingTime', 'plannedStartTime', 'plannedEndTime']);
const FOLLOWERS = /** @type {const} */ (['meetingTime', 'plannedEndTime']);

/**
 * @param {string} date an ISO calendar date
 * @param {number} days the days to add, negative to go back
 * @returns {string} the shifted ISO calendar date
 */
function shiftDate(date, days) {
    const [year, month, day] = date.split('-').map(Number);
    const shifted = new Date(year, month - 1, day + days);
    const pad = (/** @type {number} */ value) => String(value).padStart(2, '0');
    return `${shifted.getFullYear()}-${pad(shifted.getMonth() + 1)}-${pad(shifted.getDate())}`;
}

/**
 * Binds the schedule of the mission create form to template times of day.
 *
 * The TeamSpeak meeting and the planned end take their date from the planned start: a meeting
 * later in the day than the start lies on the day before, an end not after the start on the day
 * after.
 *
 * @param {HTMLFormElement} form the mission create form
 * @returns {{ apply: (times: TimesOfDay) => void, hasEntries: (times: TimesOfDay) => boolean } | null}
 *     applies times of day and tells whether they would replace entries, or null without a schedule
 */
export function createMissionScheduleDraft(form) {
    /** @type {Partial<Record<ScheduleField, SplitField>>} */
    const fields = {};
    for (const name of FIELDS) {
        const group = form
            .querySelector(`input[type="hidden"][name="${name}"]`)
            ?.closest('.datetime-split-group');
        const date = group?.querySelector('.date-part');
        const time = group?.querySelector('.time-part');
        if (!(date instanceof HTMLInputElement) || !(time instanceof HTMLInputElement)) return null;
        fields[name] = { date, time };
    }
    const split = /** @type {Record<ScheduleField, SplitField>} */ (fields);
    const start = split.plannedStartTime;

    /** @param {SplitField} field the field whose hidden value the splitter recomputes */
    function notify(field) {
        field.time.dispatchEvent(new Event('input', { bubbles: true }));
    }

    /** @param {(typeof FOLLOWERS)[number]} name the field that takes its date from the start */
    function followStart(name) {
        const field = split[name];
        if (!start.date.value || !field.time.value) return;
        const startTime = start.time.value;
        let days = 0;
        if (startTime && name === 'meetingTime' && field.time.value > startTime) days = -1;
        if (startTime && name === 'plannedEndTime' && field.time.value <= startTime) days = 1;
        field.date.value = shiftDate(start.date.value, days);
        notify(field);
    }

    /** @param {TimesOfDay} times the template's times of day */
    function apply(times) {
        for (const name of FIELDS) {
            const value = timeOfDay(times[name]);
            if (!value) continue;
            split[name].time.value = value;
            notify(split[name]);
        }
        for (const name of FOLLOWERS) {
            if (times[name] || !split[name].date.value) followStart(name);
        }
    }

    /**
     * @param {TimesOfDay} times the template's times of day
     * @returns {boolean} whether applying them would overwrite a date or time the planner entered
     */
    function hasEntries(times) {
        return FIELDS.some(
            (name) =>
                Boolean(times[name]) && Boolean(split[name].date.value || split[name].time.value),
        );
    }

    start.date.addEventListener('change', () => {
        for (const name of FOLLOWERS) {
            if (!split[name].date.value) followStart(name);
        }
    });

    return { apply, hasEntries };
}
