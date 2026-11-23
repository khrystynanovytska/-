'use strict';

const FEED_URLS = [
  'https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Ternopiloblenerho.json',
  'https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/ternopiloblenergo.json'
];

const DEFAULT_QUEUES = [
  '1.1', '1.2', '2.1', '2.2',
  '3.1', '3.2', '4.1', '4.2',
  '5.1', '5.2', '6.1', '6.2'
];

const DEFAULT_QUEUE = '3.1';
const DEFAULT_ADDRESS = 'с. Городище, вул. Польова';

const KEYS = {
  CACHED_JSON: 'svitlo31_cached_json',
  FETCHED_AT: 'svitlo31_fetched_at',
  QUEUE: 'svitlo31_queue',
  ADDRESS: 'svitlo31_address',
  MANUAL_LIVE: 'svitlo31_manual_live',
  MANUAL_EXPIRES: 'svitlo31_manual_expires'
};

const UK_MONTHS = [
  '', 'січ.', 'лют.', 'бер.', 'квіт.', 'трав.', 'черв.',
  'лип.', 'серп.', 'вер.', 'жовт.', 'лист.', 'груд.'
];

// --- State ---
const state = {
  selectedQueue: localStorage.getItem(KEYS.QUEUE) || DEFAULT_QUEUE,
  addressLabel: localStorage.getItem(KEYS.ADDRESS) ?? DEFAULT_ADDRESS,
  selectedTomorrow: false,
  dataset: null,
  isLoading: true,
  error: null,
  pickedQueueDraft: DEFAULT_QUEUE,
  editingHourTarget: null,
  deferredInstallPrompt: null
};

// --- Kyiv Time Helpers ---
const kyivPartsFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/Kyiv',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
});

function getKyivParts(epochMs) {
  const parts = kyivPartsFormatter.formatToParts(new Date(epochMs));
  const map = {};
  for (const p of parts) {
    if (p.type !== 'literal') map[p.type] = parseInt(p.value, 10);
  }
  if (map.hour === 24) map.hour = 0;
  return map;
}

function dateKey(y, m, d) {
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

function parseDateKey(key) {
  const [y, m, d] = key.split('-').map(Number);
  return { year: y, month: m, day: d };
}

function formatDateShort(key) {
  const { month, day } = parseDateKey(key);
  return `${day} ${UK_MONTHS[month] || ''}`;
}

function addDaysToKey(key, deltaDays) {
  const { year, month, day } = parseDateKey(key);
  const dt = new Date(Date.UTC(year, month - 1, day + deltaDays));
  return dateKey(dt.getUTCFullYear(), dt.getUTCMonth() + 1, dt.getUTCDate());
}

function kyivDateKeyFromEpochMs(epochMs) {
  const p = getKyivParts(epochMs);
  return dateKey(p.year, p.month, p.day);
}

function kyivMinuteOfDay(epochMs) {
  const p = getKyivParts(epochMs);
  return p.hour * 60 + p.minute;
}

function kyivMidnightMillis(key) {
  const { year, month, day } = parseDateKey(key);
  const utcGuess = Date.UTC(year, month - 1, day, 0, 0, 0, 0);
  const p1 = getKyivParts(utcGuess);
  const asUtc1 = Date.UTC(p1.year, p1.month - 1, p1.day, p1.hour, p1.minute, p1.second, 0);
  const corr1 = utcGuess - (asUtc1 - utcGuess);
  const p2 = getKyivParts(corr1);
  const asUtc2 = Date.UTC(p2.year, p2.month - 1, p2.day, p2.hour, p2.minute, p2.second, 0);
  return corr1 - (asUtc2 - utcGuess);
}

function kyivEpochMillisFor(key, minutesFromMidnight) {
  const daysOffset = Math.floor(minutesFromMidnight / 1440);
  const remMinutes = ((minutesFromMidnight % 1440) + 1440) % 1440;
  const targetKey = daysOffset === 0 ? key : addDaysToKey(key, daysOffset);
  return kyivMidnightMillis(targetKey) + remMinutes * 60_000;
}

function formatHHmmFromMillis(epochMs) {
  const p = getKyivParts(epochMs);
  return `${String(p.hour).padStart(2, '0')}:${String(p.minute).padStart(2, '0')}`;
}

function formatUpdatedLabel(raw, fallbackMs) {
  const trimmed = (raw || '').trim();
  if (!trimmed) {
    const p = getKyivParts(fallbackMs);
    return `${String(p.day).padStart(2, '0')}.${String(p.month).padStart(2, '0')} ${String(p.hour).padStart(2, '0')}:${String(p.minute).padStart(2, '0')}`;
  }
  if (trimmed.length >= 16 && trimmed[4] === '-' && trimmed[10] === 'T') {
    return `${trimmed.slice(8, 10)}.${trimmed.slice(5, 7)} ${trimmed.slice(11, 16)}`;
  }
  return trimmed;
}

function formatMinutesHHmm(totalMinutes) {
  const clamped = Math.max(0, Math.min(1440, totalMinutes));
  const h = Math.floor(clamped / 60);
  const m = clamped % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function formatCountdownHMS(totalSeconds) {
  const safe = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(safe / 3600);
  const m = Math.floor((safe % 3600) / 60);
  const s = safe % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

function formatDurationUa(totalMinutes) {
  const safe = Math.max(0, totalMinutes);
  const h = Math.floor(safe / 60);
  const m = safe % 60;
  if (h > 0 && m > 0) return `${h} год ${m} хв`;
  if (h > 0) return `${h} год`;
  return `${m} хв`;
}

// --- Manual Overrides Storage ---
function slotOverrideKey(queueId, dKey) {
  return `svitlo31_slots_${queueId}_${dKey}`;
}

function charToState(ch) {
  if (ch === '1') return 'ON';
  if (ch === '2') return 'MAYBE';
  if (ch === '0') return 'OFF';
  return null;
}

function stateToChar(st) {
  if (st === 'ON') return '1';
  if (st === 'MAYBE') return '2';
  if (st === 'OFF') return '0';
  return '.';
}

function setHourOverride(queueId, dKey, hour, slotState) {
  const key = slotOverrideKey(queueId, dKey);
  const current = (localStorage.getItem(key) || '.'.repeat(48)).padEnd(48, '.').split('');
  const ch = stateToChar(slotState);
  current[hour * 2] = ch;
  current[hour * 2 + 1] = ch;
  const encoded = current.join('');
  if (/^\.+$/.test(encoded)) {
    localStorage.removeItem(key);
  } else {
    localStorage.setItem(key, encoded);
  }
}

function getHourOverride(queueId, dKey, hour) {
  const encoded = localStorage.getItem(slotOverrideKey(queueId, dKey));
  if (!encoded || encoded.length <= hour * 2) return null;
  return charToState(encoded[hour * 2]);
}

function clearDaySlotOverrides(queueId, dKey) {
  localStorage.removeItem(slotOverrideKey(queueId, dKey));
}

function getActiveManualLive(nowMs) {
  const raw = localStorage.getItem(KEYS.MANUAL_LIVE);
  const exp = Number(localStorage.getItem(KEYS.MANUAL_EXPIRES) || '0');
  if (!raw || exp <= nowMs) {
    localStorage.removeItem(KEYS.MANUAL_LIVE);
    localStorage.removeItem(KEYS.MANUAL_EXPIRES);
    return null;
  }
  return { state: raw, expiresAtMillis: exp };
}

function setManualLive(slotState, expiresAtMillis) {
  if (!slotState) {
    localStorage.removeItem(KEYS.MANUAL_LIVE);
    localStorage.removeItem(KEYS.MANUAL_EXPIRES);
  } else {
    localStorage.setItem(KEYS.MANUAL_LIVE, slotState);
    localStorage.setItem(KEYS.MANUAL_EXPIRES, String(expiresAtMillis));
  }
}

// --- Parser & Snapshot ---
function normalizeQueueId(rawKey) {
  const cleaned = rawKey.trim()
    .replace(/^gpv/i, '')
    .replace(/^_/, '')
    .replace(/_/g, '.');
  return /^\d+\.\d+$/.test(cleaned) ? cleaned : null;
}

function findQueueObject(dayQueues, queueId) {
  const norm = queueId.trim();
  const candidates = [`GPV${norm}`, `gpv${norm}`, norm, `GPV_${norm.replace('.', '_')}`];
  for (const k of candidates) {
    if (dayQueues[k]) return dayQueues[k];
  }
  for (const k of Object.keys(dayQueues)) {
    if (normalizeQueueId(k) === norm) return dayQueues[k];
  }
  return null;
}

function mapHourStatus(status) {
  switch (status) {
    case 'yes': return ['ON', 'ON'];
    case 'no': return ['OFF', 'OFF'];
    case 'first': return ['OFF', 'ON'];
    case 'mfirst': return ['MAYBE', 'ON'];
    case 'second': return ['ON', 'OFF'];
    case 'msecond': return ['ON', 'MAYBE'];
    case 'maybe': return ['MAYBE', 'MAYBE'];
    default: return ['ON', 'ON'];
  }
}

function parseJson(rawJson, queueId, fetchedAtMillis, isFromCache) {
  const root = JSON.parse(rawJson);
  const fact = root.fact;
  const dataObj = fact && fact.data;
  if (!dataObj) throw new Error('Відсутній розділ fact.data у графіку');

  const days = {};
  const discoveredQueues = new Set();

  for (const tsKey of Object.keys(dataObj)) {
    const epochSec = Number(tsKey);
    if (!Number.isFinite(epochSec)) continue;
    const dayQueues = dataObj[tsKey];
    if (!dayQueues || typeof dayQueues !== 'object') continue;

    for (const qk of Object.keys(dayQueues)) {
      const nq = normalizeQueueId(qk);
      if (nq) discoveredQueues.add(nq);
    }

    const queueObj = findQueueObject(dayQueues, queueId);
    if (!queueObj) continue;

    const slots = new Array(48).fill('ON');
    for (let hour = 1; hour <= 24; hour++) {
      const status = String(queueObj[String(hour)] || 'yes').trim().toLowerCase();
      const [s1, s2] = mapHourStatus(status);
      slots[(hour - 1) * 2] = s1;
      slots[(hour - 1) * 2 + 1] = s2;
    }

    const dKey = kyivDateKeyFromEpochMs(epochSec * 1000);
    days[dKey] = { dateKey: dKey, slots, hasManualEdits: false };
  }

  if (Object.keys(days).length === 0) {
    throw new Error(`Не знайдено даних для черги ${queueId}`);
  }

  const lastUpdatedLabel = formatUpdatedLabel(fact.update || root.lastUpdated || '', fetchedAtMillis);
  const availableQueues = discoveredQueues.size > 0
    ? Array.from(discoveredQueues).sort()
    : [...DEFAULT_QUEUES];

  return applyManualSlotOverrides({
    queueId,
    availableQueues,
    days,
    lastUpdatedLabel,
    fetchedAtMillis,
    isFromCache
  });
}

function applyManualSlotOverrides(dataset) {
  const updatedDays = {};
  for (const [dKey, day] of Object.entries(dataset.days)) {
    const encoded = localStorage.getItem(slotOverrideKey(dataset.queueId, dKey));
    if (!encoded || encoded.length < 48) {
      updatedDays[dKey] = { ...day, hasManualEdits: false };
    } else {
      let edited = false;
      const mergedSlots = day.slots.map((orig, idx) => {
        const ov = charToState(encoded[idx]);
        if (ov) {
          edited = true;
          return ov;
        }
        return orig;
      });
      updatedDays[dKey] = { dateKey: dKey, slots: mergedSlots, hasManualEdits: edited };
    }
  }
  return { ...dataset, days: updatedDays };
}

function computeTimeRanges(slots, targetState) {
  if (!slots || slots.length !== 48) return [];
  const ranges = [];
  let start = null;
  for (let i = 0; i < slots.length; i++) {
    const matches = slots[i] === targetState;
    if (matches && start === null) {
      start = i * 30;
    } else if (!matches && start !== null) {
      ranges.push({ startMinute: start, endMinute: i * 30 });
      start = null;
    }
  }
  if (start !== null) {
    ranges.push({ startMinute: start, endMinute: 1440 });
  }
  return ranges;
}

function formatRange(r) {
  return `${formatMinutesHHmm(r.startMinute)} – ${formatMinutesHHmm(r.endMinute)}`;
}

function computeCurrentBlockEndMillis(dataset, nowMs) {
  const todayKey = kyivDateKeyFromEpochMs(nowMs);
  const tomorrowKey = addDaysToKey(todayKey, 1);
  const todayDay = dataset.days[todayKey];
  if (!todayDay) return kyivEpochMillisFor(todayKey, 1440);
  const tomorrowDay = dataset.days[tomorrowKey];

  const currentMinute = kyivMinuteOfDay(nowMs);
  const currentSlotIdx = Math.max(0, Math.min(47, Math.floor(currentMinute / 30)));
  const currentState = todayDay.slots[currentSlotIdx];
  const timeline = [...todayDay.slots, ...(tomorrowDay ? tomorrowDay.slots : [])];

  for (let idx = currentSlotIdx + 1; idx < timeline.length; idx++) {
    if (timeline[idx] !== currentState) {
      return kyivEpochMillisFor(todayKey, idx * 30);
    }
  }
  return kyivEpochMillisFor(todayKey, timeline.length * 30);
}

function computeSnapshot(dataset, nowMs, manualOverride) {
  const todayKey = kyivDateKeyFromEpochMs(nowMs);
  const tomorrowKey = addDaysToKey(todayKey, 1);
  const yesterdayKey = addDaysToKey(todayKey, -1);

  const todaySchedule = dataset.days[todayKey] || null;
  const tomorrowSchedule = dataset.days[tomorrowKey] || null;
  const yesterdaySchedule = dataset.days[yesterdayKey] || null;

  const activeOverride = manualOverride && manualOverride.expiresAtMillis > nowMs ? manualOverride : null;
  const currentMinute = kyivMinuteOfDay(nowMs);

  const tomorrowOnRanges = tomorrowSchedule ? computeTimeRanges(tomorrowSchedule.slots, 'ON') : [];
  const tomorrowMaybeRanges = tomorrowSchedule ? computeTimeRanges(tomorrowSchedule.slots, 'MAYBE') : [];
  const tomorrowOffRanges = tomorrowSchedule ? computeTimeRanges(tomorrowSchedule.slots, 'OFF') : [];

  if (!todaySchedule) {
    return {
      todayKey,
      tomorrowKey,
      todaySchedule: null,
      tomorrowSchedule,
      effectiveState: activeOverride ? activeOverride.state : 'ON',
      isManualOverride: !!activeOverride,
      remainingSeconds: activeOverride ? Math.max(0, Math.floor((activeOverride.expiresAtMillis - nowMs) / 1000)) : -1,
      blockProgress: 0,
      nextChangeTimeLabel: activeOverride ? formatHHmmFromMillis(activeOverride.expiresAtMillis) : null,
      nextChangeTargetState: null,
      todayOnMinutes: 1440,
      todayMaybeMinutes: 0,
      todayOffMinutes: 0,
      todayOnRanges: [],
      todayMaybeRanges: [],
      todayOffRanges: [],
      tomorrowOnRanges,
      tomorrowMaybeRanges,
      tomorrowOffRanges,
      currentMinuteOfDay: currentMinute
    };
  }

  const currentSlotIdx = Math.max(0, Math.min(47, Math.floor(currentMinute / 30)));
  const rawTimeline = [...todaySchedule.slots, ...(tomorrowSchedule ? tomorrowSchedule.slots : [])];
  const effectiveTimeline = [...rawTimeline];

  if (activeOverride) {
    const todayMid = kyivMidnightMillis(todayKey);
    const endSlotExcl = Math.max(
      currentSlotIdx + 1,
      Math.min(effectiveTimeline.length, Math.ceil((activeOverride.expiresAtMillis - todayMid) / 1800_000))
    );
    for (let idx = currentSlotIdx; idx < endSlotExcl; idx++) {
      effectiveTimeline[idx] = activeOverride.state;
    }
  }

  const effectiveState = effectiveTimeline[currentSlotIdx];

  let nextChangeSlotIdx = null;
  for (let idx = currentSlotIdx + 1; idx < effectiveTimeline.length; idx++) {
    if (effectiveTimeline[idx] !== effectiveState) {
      nextChangeSlotIdx = idx;
      break;
    }
  }

  let blockStartSlotOffset = currentSlotIdx;
  let i = currentSlotIdx - 1;
  while (i >= 0 && effectiveTimeline[i] === effectiveState) {
    blockStartSlotOffset = i;
    i--;
  }
  if (i < 0 && yesterdaySchedule && !activeOverride) {
    let yIdx = 47;
    while (yIdx >= 0 && yesterdaySchedule.slots[yIdx] === effectiveState) {
      blockStartSlotOffset = yIdx - 48;
      yIdx--;
    }
  }

  let remainingSeconds = -1;
  let nextChangeTimeLabel = null;
  let nextChangeTargetState = null;
  let blockProgress = 1;

  const hasAnyInterruption = todaySchedule.slots.some((s) => s !== 'ON');

  if (nextChangeSlotIdx !== null) {
    const changeMin = nextChangeSlotIdx * 30;
    const changeMs = kyivEpochMillisFor(todayKey, changeMin);
    remainingSeconds = Math.max(0, Math.floor((changeMs - nowMs) / 1000));
    nextChangeTargetState = effectiveTimeline[nextChangeSlotIdx];
    const timeStr = formatMinutesHHmm(changeMin % 1440);
    nextChangeTimeLabel = nextChangeSlotIdx >= 48 ? `завтра о ${timeStr}` : timeStr;

    const startMs = kyivEpochMillisFor(todayKey, blockStartSlotOffset * 30);
    const totalMs = Math.max(1, changeMs - startMs);
    blockProgress = Math.max(0, Math.min(1, (nowMs - startMs) / totalMs));
  } else if (hasAnyInterruption || tomorrowSchedule) {
    const endMs = kyivEpochMillisFor(todayKey, effectiveTimeline.length * 30);
    remainingSeconds = Math.max(0, Math.floor((endMs - nowMs) / 1000));
    const startMs = kyivEpochMillisFor(todayKey, blockStartSlotOffset * 30);
    const totalMs = Math.max(1, endMs - startMs);
    blockProgress = Math.max(0, Math.min(1, (nowMs - startMs) / totalMs));
  }

  const todayOnMinutes = todaySchedule.slots.filter((s) => s === 'ON').length * 30;
  const todayMaybeMinutes = todaySchedule.slots.filter((s) => s === 'MAYBE').length * 30;
  const todayOffMinutes = todaySchedule.slots.filter((s) => s === 'OFF').length * 30;

  return {
    todayKey,
    tomorrowKey,
    todaySchedule,
    tomorrowSchedule,
    effectiveState,
    isManualOverride: !!activeOverride,
    remainingSeconds,
    blockProgress,
    nextChangeTimeLabel,
    nextChangeTargetState,
    todayOnMinutes,
    todayMaybeMinutes,
    todayOffMinutes,
    todayOnRanges: computeTimeRanges(todaySchedule.slots, 'ON'),
    todayMaybeRanges: computeTimeRanges(todaySchedule.slots, 'MAYBE'),
    todayOffRanges: computeTimeRanges(todaySchedule.slots, 'OFF'),
    tomorrowOnRanges,
    tomorrowMaybeRanges,
    tomorrowOffRanges,
    currentMinuteOfDay: currentMinute
  };
}

// --- Network & Cache ---
function getCachedDataset(queueId = state.selectedQueue) {
  const raw = localStorage.getItem(KEYS.CACHED_JSON);
  if (!raw) return null;
  const fetchedAt = Number(localStorage.getItem(KEYS.FETCHED_AT) || '0');
  try {
    return parseJson(raw, queueId, fetchedAt, true);
  } catch {
    return null;
  }
}

const AUTO_REFRESH_MS = 15 * 60 * 1000;
let isFetching = false;
let lastFetchAttemptMs = 0;

async function fetchSchedule() {
  if (isFetching) return;
  isFetching = true;
  lastFetchAttemptMs = Date.now();

  const refreshBtn = document.getElementById('btn-refresh');
  refreshBtn.classList.add('spinning');
  refreshBtn.disabled = true;

  const cacheBust = Math.floor(Date.now() / 60000);
  let lastErr = null;
  for (const url of FEED_URLS) {
    try {
      const resp = await fetch(`${url}?t=${cacheBust}`, { cache: 'no-store' });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const text = await resp.text();
      const now = Date.now();
      const parsed = parseJson(text, state.selectedQueue, now, false);
      localStorage.setItem(KEYS.CACHED_JSON, text);
      localStorage.setItem(KEYS.FETCHED_AT, String(now));
      state.dataset = parsed;
      state.error = null;
      state.isLoading = false;
      isFetching = false;
      refreshBtn.classList.remove('spinning');
      refreshBtn.disabled = false;
      render();
      return;
    } catch (err) {
      lastErr = err;
    }
  }

  isFetching = false;
  refreshBtn.classList.remove('spinning');
  refreshBtn.disabled = false;

  const cached = getCachedDataset();
  if (cached) {
    state.dataset = cached;
    state.error = null;
  } else if (!state.dataset) {
    state.error = lastErr ? lastErr.message : 'Перевірте інтернет-з’єднання';
  }
  state.isLoading = false;
  render();
}

// --- Rendering ---
function render() {
  document.getElementById('queue-title-text').textContent = `Черга ${state.selectedQueue}`;
  const addrEl = document.getElementById('address-label-text');
  addrEl.textContent = state.addressLabel;
  addrEl.hidden = !state.addressLabel.trim();

  const loadingCard = document.getElementById('loading-card');
  const errorCard = document.getElementById('error-card');
  const content = document.getElementById('schedule-content');

  if (state.isLoading && !state.dataset) {
    loadingCard.hidden = false;
    errorCard.hidden = true;
    content.hidden = true;
    return;
  }

  if (state.error && !state.dataset) {
    loadingCard.hidden = true;
    errorCard.hidden = false;
    content.hidden = true;
    document.getElementById('error-message').textContent = state.error;
    return;
  }

  if (!state.dataset) return;

  loadingCard.hidden = true;
  errorCard.hidden = true;
  content.hidden = false;

  const now = Date.now();
  const manualOverride = getActiveManualLive(now);
  const snap = computeSnapshot(state.dataset, now, manualOverride);

  // Hero card
  const heroCard = document.getElementById('hero-card');
  heroCard.classList.remove('state-on', 'state-maybe', 'state-off');
  const stateClass = snap.effectiveState === 'ON'
    ? 'state-on'
    : (snap.effectiveState === 'MAYBE' ? 'state-maybe' : 'state-off');
  heroCard.classList.add(stateClass);

  const metaTheme = document.getElementById('meta-theme-color');
  metaTheme.setAttribute(
    'content',
    snap.effectiveState === 'ON' ? '#15803D' : (snap.effectiveState === 'MAYBE' ? '#B45309' : '#B91C1C')
  );

  document.getElementById('hero-badge-text').textContent = snap.isManualOverride ? 'Вказано вручну' : 'Зараз';
  document.getElementById('hero-icon').textContent = snap.effectiveState === 'ON' ? '⚡' : (snap.effectiveState === 'MAYBE' ? '⚠️' : '✕');
  document.getElementById('hero-headline').textContent = snap.effectiveState === 'ON'
    ? 'Є СВІТЛО'
    : (snap.effectiveState === 'MAYBE' ? 'МОЖЛИВЕ ВІДКЛЮЧЕННЯ' : 'НЕМА СВІТЛА');

  const waitingBody = document.getElementById('hero-body-waiting');
  const activeBody = document.getElementById('hero-body-active');

  if (!snap.todaySchedule) {
    waitingBody.hidden = false;
    activeBody.hidden = true;
  } else {
    waitingBody.hidden = true;
    activeBody.hidden = false;

    const countdownBlock = document.getElementById('countdown-block');
    const noOutagesText = document.getElementById('no-outages-text');

    if (snap.remainingSeconds >= 0) {
      countdownBlock.hidden = false;
      noOutagesText.hidden = true;

      document.getElementById('timer-prefix').textContent = snap.effectiveState === 'ON'
        ? 'Світло ще буде:'
        : (snap.effectiveState === 'MAYBE' ? 'Період можливих відключень ще:' : 'Електроенергії не буде ще:');

      document.getElementById('countdown-digits').textContent = formatCountdownHMS(snap.remainingSeconds);

      const nextLabelEl = document.getElementById('next-change-label');
      if (snap.nextChangeTimeLabel) {
        nextLabelEl.hidden = false;
        nextLabelEl.textContent = snap.nextChangeTargetState === 'ON'
          ? `Увімкнення о ${snap.nextChangeTimeLabel}`
          : (snap.nextChangeTargetState === 'MAYBE'
            ? `Можливе вимкнення о ${snap.nextChangeTimeLabel}`
            : `Вимкнення о ${snap.nextChangeTimeLabel}`);
      } else {
        nextLabelEl.hidden = true;
      }

      document.getElementById('progress-fill').style.width = `${Math.round(snap.blockProgress * 100)}%`;
    } else {
      countdownBlock.hidden = true;
      noOutagesText.hidden = false;
    }

    document.getElementById('total-on-value').textContent = formatDurationUa(snap.todayOnMinutes);
    document.getElementById('total-off-value').textContent = formatDurationUa(snap.todayOffMinutes);
    const hasMaybe = snap.todayMaybeMinutes > 0;
    document.getElementById('total-maybe-divider').hidden = !hasMaybe;
    document.getElementById('total-maybe-col').hidden = !hasMaybe;
    if (hasMaybe) {
      document.getElementById('total-maybe-value').textContent = formatDurationUa(snap.todayMaybeMinutes);
    }

    const btnOn = document.getElementById('btn-manual-on');
    const btnOff = document.getElementById('btn-manual-off');
    const btnAuto = document.getElementById('btn-manual-auto');
    btnOn.classList.toggle('selected', snap.isManualOverride && snap.effectiveState === 'ON');
    btnOff.classList.toggle('selected', snap.isManualOverride && snap.effectiveState === 'OFF');
    btnAuto.hidden = !snap.isManualOverride;
  }

  // Daily schedule card
  const hasTomorrow = !!snap.tomorrowSchedule;
  const isTomorrow = state.selectedTomorrow && hasTomorrow;
  const daySwitchPill = document.getElementById('day-switch-pill');
  const singleDateBadge = document.getElementById('single-date-badge');

  if (hasTomorrow) {
    daySwitchPill.hidden = false;
    singleDateBadge.hidden = true;
    document.getElementById('tab-today').classList.toggle('active', !isTomorrow);
    document.getElementById('tab-tomorrow').classList.toggle('active', isTomorrow);
  } else {
    daySwitchPill.hidden = true;
    singleDateBadge.hidden = false;
    singleDateBadge.textContent = formatDateShort(snap.todayKey);
  }

  const activeDay = isTomorrow ? snap.tomorrowSchedule : snap.todaySchedule;
  const dayMissing = document.getElementById('day-missing-msg');
  const dayBody = document.getElementById('day-schedule-body');

  if (!activeDay) {
    dayMissing.hidden = false;
    dayBody.hidden = true;
  } else {
    dayMissing.hidden = true;
    dayBody.hidden = false;

    // 24h bar
    const timelineKey = `${activeDay.dateKey}:${activeDay.slots.join(',')}`;
    const timelineSlots = document.getElementById('timeline-slots');
    if (timelineSlots.dataset.renderKey !== timelineKey) {
      timelineSlots.dataset.renderKey = timelineKey;
      timelineSlots.innerHTML = '';
      for (let i = 0; i < 48; i++) {
        const div = document.createElement('div');
        const st = activeDay.slots[i] || 'ON';
        div.className = `t-slot ${st.toLowerCase()}`;
        timelineSlots.appendChild(div);
      }
    }

    const nowMarker = document.getElementById('now-marker');
    const legendNow = document.getElementById('legend-now-item');
    if (isTomorrow) {
      nowMarker.hidden = true;
      legendNow.hidden = true;
    } else {
      nowMarker.hidden = false;
      legendNow.hidden = false;
      const pct = Math.max(1, Math.min(99, (snap.currentMinuteOfDay / 1440) * 100));
      nowMarker.style.left = `${pct}%`;
    }

    const resetDayBtn = document.getElementById('btn-reset-day');
    resetDayBtn.hidden = !activeDay.hasManualEdits;

    // Hour grid
    const hourGrid = document.getElementById('hour-grid');
    const currentHour = isTomorrow ? -1 : Math.floor(snap.currentMinuteOfDay / 60);
    const gridKey = `${timelineKey}:${currentHour}`;

    if (hourGrid.dataset.renderKey !== gridKey) {
      hourGrid.dataset.renderKey = gridKey;
      hourGrid.innerHTML = '';

      for (let hr = 0; hr < 24; hr++) {
        const s1 = activeDay.slots[hr * 2] || 'ON';
        const s2 = activeDay.slots[hr * 2 + 1] || 'ON';
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = `hour-cell${hr === currentHour ? ' current' : ''}`;
        btn.setAttribute('aria-label', `Година ${String(hr).padStart(2, '0')}:00`);

        const halves = document.createElement('span');
        halves.className = 'hour-halves';
        const h1 = document.createElement('span');
        h1.className = `hour-half ${s1.toLowerCase()}`;
        const h2 = document.createElement('span');
        h2.className = `hour-half ${s2.toLowerCase()}`;
        halves.appendChild(h1);
        halves.appendChild(h2);

        const lbl = document.createElement('span');
        lbl.className = `hour-label ${s1 === s2 ? s1.toLowerCase() : 'mixed'}`;
        lbl.textContent = `${String(hr).padStart(2, '0')}:00`;

        btn.appendChild(halves);
        btn.appendChild(lbl);
        btn.addEventListener('click', () => openHourModal(activeDay.dateKey, hr));
        hourGrid.appendChild(btn);
      }
    }
  }

  // Intervals card
  const onRanges = isTomorrow ? snap.tomorrowOnRanges : snap.todayOnRanges;
  const maybeRanges = isTomorrow ? snap.tomorrowMaybeRanges : snap.todayMaybeRanges;
  const offRanges = isTomorrow ? snap.tomorrowOffRanges : snap.todayOffRanges;

  document.getElementById('title-on-intervals').textContent = isTomorrow
    ? 'Електроенергія завтра буде:'
    : 'Електроенергія сьогодні буде:';
  document.getElementById('text-on-intervals').textContent = !activeDay
    ? '—'
    : (onRanges.length === 0
      ? 'Відсутні'
      : (onRanges.length === 1 && onRanges[0].startMinute === 0 && onRanges[0].endMinute === 1440
        ? 'Увесь день (00:00 – 24:00)'
        : onRanges.map(formatRange).join(', ')));

  const maybeSection = document.getElementById('section-maybe-intervals');
  if (activeDay && maybeRanges.length > 0) {
    maybeSection.hidden = false;
    document.getElementById('title-maybe-intervals').textContent = isTomorrow
      ? 'Можливі відключення завтра:'
      : 'Можливі відключення сьогодні:';
    document.getElementById('text-maybe-intervals').textContent = maybeRanges.map(formatRange).join(', ');
  } else {
    maybeSection.hidden = true;
  }

  document.getElementById('title-off-intervals').textContent = isTomorrow
    ? 'Електроенергії завтра не буде:'
    : 'Електроенергії не буде:';
  document.getElementById('text-off-intervals').textContent = !activeDay
    ? '—'
    : (offRanges.length === 0 ? 'Відсутні' : offRanges.map(formatRange).join(', '));

  // Footer
  const parts = [];
  if (state.dataset.lastUpdatedLabel) {
    parts.push(`Графік: ${state.dataset.lastUpdatedLabel}`);
  }
  if (state.dataset.fetchedAtMillis > 0) {
    parts.push(`Перевірено о ${formatHHmmFromMillis(state.dataset.fetchedAtMillis)}`);
  }
  const cacheSuffix = state.dataset.isFromCache ? ' (кеш)' : '';
  document.getElementById('footer-updated').textContent = parts.join(' · ') + cacheSuffix;
}

// --- Modals & Actions ---
function openQueueModal() {
  state.pickedQueueDraft = state.selectedQueue;
  document.getElementById('input-address').value = state.addressLabel;
  renderQueueGrid();
  document.getElementById('queue-modal').hidden = false;
}

function renderQueueGrid() {
  const queues = (state.dataset && state.dataset.availableQueues) || DEFAULT_QUEUES;
  const grid = document.getElementById('queue-options-grid');
  grid.innerHTML = '';
  for (const q of queues) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `queue-option${q === state.pickedQueueDraft ? ' selected' : ''}`;
    btn.textContent = q;
    btn.addEventListener('click', () => {
      const addrInput = document.getElementById('input-address');
      if (q !== '3.1' && addrInput.value === DEFAULT_ADDRESS) {
        addrInput.value = '';
      } else if (q === '3.1' && !addrInput.value.trim()) {
        addrInput.value = DEFAULT_ADDRESS;
      }
      state.pickedQueueDraft = q;
      renderQueueGrid();
    });
    grid.appendChild(btn);
  }
}

function openHourModal(dKey, hr) {
  state.editingHourTarget = { dateKey: dKey, hour: hr };
  const rangeStr = `${String(hr).padStart(2, '0')}:00 – ${String(hr + 1).padStart(2, '0')}:00`;
  document.getElementById('hour-modal-title').textContent = `Година ${rangeStr}`;
  const hasOverride = !!getHourOverride(state.selectedQueue, dKey, hr);
  document.getElementById('btn-reset-hour').hidden = !hasOverride;
  document.getElementById('hour-modal').hidden = false;
}

function reapplyLocalDataset() {
  const reparsed = getCachedDataset(state.selectedQueue);
  if (reparsed) {
    state.dataset = reparsed;
    render();
  }
}

function wireEvents() {
  document.getElementById('btn-refresh').addEventListener('click', () => fetchSchedule());
  document.getElementById('btn-retry').addEventListener('click', () => fetchSchedule());

  document.getElementById('tab-today').addEventListener('click', () => {
    state.selectedTomorrow = false;
    render();
  });
  document.getElementById('tab-tomorrow').addEventListener('click', () => {
    state.selectedTomorrow = true;
    render();
  });

  document.getElementById('btn-manual-on').addEventListener('click', () => {
    if (!state.dataset) return;
    const now = Date.now();
    const blockEnd = computeCurrentBlockEndMillis(state.dataset, now);
    setManualLive('ON', Math.max(blockEnd, now + 30 * 60_000));
    render();
  });

  document.getElementById('btn-manual-off').addEventListener('click', () => {
    if (!state.dataset) return;
    const now = Date.now();
    const blockEnd = computeCurrentBlockEndMillis(state.dataset, now);
    setManualLive('OFF', Math.max(blockEnd, now + 30 * 60_000));
    render();
  });

  document.getElementById('btn-manual-auto').addEventListener('click', () => {
    setManualLive(null, 0);
    render();
  });

  document.getElementById('btn-reset-day').addEventListener('click', () => {
    if (!state.dataset) return;
    const snap = computeSnapshot(state.dataset, Date.now(), null);
    const activeKey = state.selectedTomorrow && snap.tomorrowSchedule ? snap.tomorrowKey : snap.todayKey;
    clearDaySlotOverrides(state.selectedQueue, activeKey);
    reapplyLocalDataset();
  });

  // Queue modal
  document.getElementById('btn-open-queue').addEventListener('click', openQueueModal);
  document.getElementById('btn-cancel-queue').addEventListener('click', () => {
    document.getElementById('queue-modal').hidden = true;
  });
  document.getElementById('queue-modal').addEventListener('click', (e) => {
    if (e.target.id === 'queue-modal') document.getElementById('queue-modal').hidden = true;
  });
  document.getElementById('btn-save-queue').addEventListener('click', () => {
    const newAddr = document.getElementById('input-address').value.trim();
    state.selectedQueue = state.pickedQueueDraft;
    state.addressLabel = newAddr;
    localStorage.setItem(KEYS.QUEUE, state.selectedQueue);
    localStorage.setItem(KEYS.ADDRESS, state.addressLabel);
    setManualLive(null, 0);
    document.getElementById('queue-modal').hidden = true;
    const reparsed = getCachedDataset(state.selectedQueue);
    if (reparsed) {
      state.dataset = reparsed;
      render();
    } else {
      fetchSchedule();
    }
  });

  // Hour modal
  document.getElementById('btn-close-hour').addEventListener('click', () => {
    document.getElementById('hour-modal').hidden = true;
  });
  document.getElementById('hour-modal').addEventListener('click', (e) => {
    if (e.target.id === 'hour-modal') document.getElementById('hour-modal').hidden = true;
  });
  document.querySelectorAll('.hour-option-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      if (!state.editingHourTarget) return;
      const { dateKey: dKey, hour } = state.editingHourTarget;
      setHourOverride(state.selectedQueue, dKey, hour, btn.dataset.state);
      document.getElementById('hour-modal').hidden = true;
      reapplyLocalDataset();
    });
  });
  document.getElementById('btn-reset-hour').addEventListener('click', () => {
    if (!state.editingHourTarget) return;
    const { dateKey: dKey, hour } = state.editingHourTarget;
    setHourOverride(state.selectedQueue, dKey, hour, null);
    document.getElementById('hour-modal').hidden = true;
    reapplyLocalDataset();
  });

  // PWA Install Prompt
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    state.deferredInstallPrompt = e;
    document.getElementById('btn-install-pwa').hidden = false;
    document.getElementById('install-banner').hidden = false;
  });

  async function triggerInstall() {
    if (!state.deferredInstallPrompt) return;
    state.deferredInstallPrompt.prompt();
    const choice = await state.deferredInstallPrompt.userChoice;
    if (choice.outcome === 'accepted') {
      document.getElementById('btn-install-pwa').hidden = true;
      document.getElementById('install-banner').hidden = true;
    }
    state.deferredInstallPrompt = null;
  }

  document.getElementById('btn-install-pwa').addEventListener('click', triggerInstall);
  document.getElementById('btn-install-banner').addEventListener('click', triggerInstall);
}

let swRegistration = null;

// --- Boot ---
function init() {
  if ('serviceWorker' in navigator) {
    let reloaded = false;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!reloaded) {
        reloaded = true;
        window.location.reload();
      }
    });
    navigator.serviceWorker.register('./sw.js')
      .then((reg) => {
        swRegistration = reg;
        reg.update().catch(() => {});
      })
      .catch(() => {});
  }

  wireEvents();

  const cached = getCachedDataset();
  if (cached) {
    state.dataset = cached;
    state.isLoading = false;
    render();
  }

  fetchSchedule();

  setInterval(() => {
    if (state.dataset) render();
  }, 1000);

  setInterval(() => {
    fetchSchedule();
    if (swRegistration) swRegistration.update().catch(() => {});
  }, AUTO_REFRESH_MS);

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && Date.now() - lastFetchAttemptMs >= AUTO_REFRESH_MS) {
      fetchSchedule();
      if (swRegistration) swRegistration.update().catch(() => {});
    }
  });

  window.addEventListener('online', () => {
    fetchSchedule();
  });
}

init();
