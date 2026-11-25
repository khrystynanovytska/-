'use strict';

const OE_FEED_URLS = [
  'https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Ternopiloblenerho.json'
];
const SVITLOBOT_BASE_URL = 'https://raw.githubusercontent.com/Svitlobot-official/timetables/main/ternopil';

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
  MANUAL_EXPIRES: 'svitlo31_manual_expires',
  SAVED_PROFILES: 'svitlo31_saved_profiles',
  ACTIVE_PROFILE_ID: 'svitlo31_active_profile_id'
};

const UK_MONTHS = [
  '', 'січ.', 'лют.', 'бер.', 'квіт.', 'трав.', 'черв.',
  'лип.', 'серп.', 'вер.', 'жовт.', 'лист.', 'груд.'
];

function getSavedProfiles() {
  const raw = localStorage.getItem(KEYS.SAVED_PROFILES);
  if (raw) {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.length > 0) {
        const clean = parsed
          .filter((p) => p && typeof p.id === 'string' && typeof p.queueId === 'string')
          .map((p) => ({
            id: p.id.trim(),
            queueId: p.queueId.trim(),
            label: String(p.label || '').trim()
          }))
          .filter((p) => p.id && p.queueId);
        if (clean.length > 0) return clean;
      }
    } catch {
      // fall through to migration
    }
  }
  const initialQueue = localStorage.getItem(KEYS.QUEUE) || DEFAULT_QUEUE;
  const storedAddr = localStorage.getItem(KEYS.ADDRESS);
  const initialLabel = storedAddr !== null ? storedAddr : DEFAULT_ADDRESS;
  const initial = [{ id: 'profile_default', queueId: initialQueue, label: initialLabel }];
  localStorage.setItem(KEYS.SAVED_PROFILES, JSON.stringify(initial));
  localStorage.setItem(KEYS.ACTIVE_PROFILE_ID, 'profile_default');
  return initial;
}

function persistSavedProfiles(profiles) {
  localStorage.setItem(KEYS.SAVED_PROFILES, JSON.stringify(profiles));
}

function getActiveProfile() {
  const profiles = getSavedProfiles();
  const activeId = localStorage.getItem(KEYS.ACTIVE_PROFILE_ID);
  return profiles.find((p) => p.id === activeId) || profiles[0];
}

function selectSavedProfile(profileId) {
  const profiles = getSavedProfiles();
  const target = profiles.find((p) => p.id === profileId);
  if (!target) return null;
  localStorage.setItem(KEYS.ACTIVE_PROFILE_ID, target.id);
  localStorage.setItem(KEYS.QUEUE, target.queueId);
  localStorage.setItem(KEYS.ADDRESS, target.label);
  return target;
}

function saveQueueProfile(profileId, queueId, label) {
  const profiles = getSavedProfiles();
  const cleanQueue = String(queueId || DEFAULT_QUEUE).trim();
  const cleanLabel = String(label || '').trim();
  const idx = profileId ? profiles.findIndex((p) => p.id === profileId) : -1;
  let saved;
  if (idx >= 0) {
    saved = { ...profiles[idx], queueId: cleanQueue, label: cleanLabel };
    profiles[idx] = saved;
  } else {
    saved = {
      id: `p_${Date.now()}_${profiles.length + 1}`,
      queueId: cleanQueue,
      label: cleanLabel
    };
    profiles.push(saved);
  }
  persistSavedProfiles(profiles);
  localStorage.setItem(KEYS.ACTIVE_PROFILE_ID, saved.id);
  localStorage.setItem(KEYS.QUEUE, saved.queueId);
  localStorage.setItem(KEYS.ADDRESS, saved.label);
  return saved;
}

function deleteSavedProfile(profileId) {
  const profiles = getSavedProfiles();
  if (profiles.length <= 1) return null;
  const nextList = profiles.filter((p) => p.id !== profileId);
  if (nextList.length === profiles.length || nextList.length === 0) return null;
  persistSavedProfiles(nextList);
  const currentActiveId = localStorage.getItem(KEYS.ACTIVE_PROFILE_ID);
  const nextActive = nextList.find((p) => p.id === currentActiveId) || nextList[0];
  localStorage.setItem(KEYS.ACTIVE_PROFILE_ID, nextActive.id);
  localStorage.setItem(KEYS.QUEUE, nextActive.queueId);
  localStorage.setItem(KEYS.ADDRESS, nextActive.label);
  return nextActive;
}

const initialProfilesList = getSavedProfiles();
const initialActiveProfile = getActiveProfile();

// --- State ---
const state = {
  savedProfiles: initialProfilesList,
  activeProfileId: initialActiveProfile.id,
  selectedQueue: initialActiveProfile.queueId,
  addressLabel: initialActiveProfile.label,
  selectedTomorrow: false,
  dataset: null,
  isLoading: true,
  error: null,
  pickedQueueDraft: initialActiveProfile.queueId,
  editingProfileIdDraft: initialActiveProfile.id,
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

function setSlotOverride(queueId, dKey, slotIdx, slotState) {
  const key = slotOverrideKey(queueId, dKey);
  const current = (localStorage.getItem(key) || '.'.repeat(48)).padEnd(48, '.').split('');
  const safeIdx = Math.max(0, Math.min(47, slotIdx));
  current[safeIdx] = stateToChar(slotState);
  const encoded = current.join('');
  if (/^\.+$/.test(encoded)) {
    localStorage.removeItem(key);
  } else {
    localStorage.setItem(key, encoded);
  }
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

function setHourSlotsOverride(queueId, dKey, hour, firstState, secondState) {
  const key = slotOverrideKey(queueId, dKey);
  const current = (localStorage.getItem(key) || '.'.repeat(48)).padEnd(48, '.').split('');
  current[hour * 2] = stateToChar(firstState);
  current[hour * 2 + 1] = stateToChar(secondState);
  localStorage.setItem(key, current.join(''));
}

function getHourOverride(queueId, dKey, hour) {
  const encoded = localStorage.getItem(slotOverrideKey(queueId, dKey));
  if (!encoded || encoded.length <= hour * 2 + 1) return null;
  return charToState(encoded[hour * 2]) || charToState(encoded[hour * 2 + 1]);
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
  const cleaned = String(rawKey || '').trim()
    .replace(/^gpv/i, '')
    .replace(/^[-_]/, '')
    .replace(/[-_\s]/g, '.');
  return /^\d+\.\d+$/.test(cleaned) ? cleaned : null;
}

function findQueueObject(dayQueues, queueId) {
  const norm = queueId.trim();
  const candidates = [
    `GPV${norm}`,
    `gpv${norm}`,
    norm,
    `GPV_${norm.replace('.', '_')}`,
    `GPV-${norm.replace('.', '-')}`
  ];
  for (const k of candidates) {
    if (dayQueues[k]) return dayQueues[k];
  }
  for (const k of Object.keys(dayQueues)) {
    if (normalizeQueueId(k) === norm) return dayQueues[k];
  }
  return null;
}

function mapHourStatus(status) {
  const norm = String(status || '').trim().toLowerCase().replace(/[+/\-\s]+/g, '_');
  switch (norm) {
    case 'yes':
    case 'on':
      return ['ON', 'ON'];
    case 'no':
    case 'off':
      return ['OFF', 'OFF'];
    case 'first':
    case 'first_yes':
    case 'first_on':
    case 'off_on':
    case 'no_yes':
      return ['OFF', 'ON'];
    case 'mfirst':
    case 'mfirst_yes':
    case 'maybe_yes':
    case 'maybe_on':
      return ['MAYBE', 'ON'];
    case 'second':
    case 'yes_second':
    case 'on_second':
    case 'on_off':
    case 'yes_no':
      return ['ON', 'OFF'];
    case 'msecond':
    case 'yes_msecond':
    case 'yes_maybe':
    case 'on_maybe':
      return ['ON', 'MAYBE'];
    case 'maybe':
      return ['MAYBE', 'MAYBE'];
    case 'first_msecond':
    case 'first_maybe':
    case 'no_msecond':
    case 'no_maybe':
    case 'off_maybe':
    case 'off_msecond':
      return ['OFF', 'MAYBE'];
    case 'mfirst_second':
    case 'maybe_second':
    case 'mfirst_no':
    case 'maybe_no':
    case 'maybe_off':
    case 'mfirst_off':
      return ['MAYBE', 'OFF'];
    default: return ['ON', 'ON'];
  }
}

function parseTimestampMillis(raw) {
  const trimmed = String(raw || '').trim();
  if (!trimmed) return null;

  const isoMatch = trimmed.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$/);
  if (isoMatch) {
    const [, y, m, d, hh, mm, ss, tz] = isoMatch;
    if (tz) {
      const normTz = tz === 'Z' ? 'Z' : (tz.length === 5 ? `${tz.slice(0, 3)}:${tz.slice(3)}` : tz);
      const ms = Date.parse(`${y}-${m}-${d}T${hh}:${mm}:${ss || '00'}${normTz}`);
      return Number.isFinite(ms) ? ms : null;
    }
    const key = dateKey(Number(y), Number(m), Number(d));
    return kyivEpochMillisFor(key, Number(hh) * 60 + Number(mm)) + Number(ss || 0) * 1000;
  }

  const uaMatch = trimmed.match(/^(\d{2})\.(\d{2})\.(\d{4})\s+(\d{2}):(\d{2})(?::(\d{2}))?$/);
  if (uaMatch) {
    const [, d, m, y, hh, mm, ss] = uaMatch;
    const key = dateKey(Number(y), Number(m), Number(d));
    return kyivEpochMillisFor(key, Number(hh) * 60 + Number(mm)) + Number(ss || 0) * 1000;
  }

  const shortUaMatch = trimmed.match(/^(\d{2})\.(\d{2})\s+(\d{2}):(\d{2})$/);
  if (shortUaMatch) {
    const [, d, m, hh, mm] = shortUaMatch;
    const nowYear = getKyivParts(Date.now()).year;
    const key = dateKey(nowYear, Number(m), Number(d));
    return kyivEpochMillisFor(key, Number(hh) * 60 + Number(mm));
  }

  return null;
}

function parseDayKey(tsKey) {
  const trimmed = String(tsKey || '').trim();
  if (/^\d{4}-\d{2}-\d{2}$/.test(trimmed)) return trimmed;
  const epochSec = Number(trimmed);
  if (Number.isFinite(epochSec) && trimmed !== '') {
    return kyivDateKeyFromEpochMs(epochSec * 1000);
  }
  return null;
}

function parseHHmmToMinutes(raw, isEnd) {
  const m = String(raw || '').trim().match(/^(\d{1,2}):(\d{2})$/);
  if (!m) return null;
  const hh = Number(m[1]);
  const mm = Number(m[2]);
  if (hh === 24 && mm === 0) return 1440;
  if (hh < 0 || hh > 23 || mm < 0 || mm > 59) return null;
  if (isEnd && hh === 0 && mm === 0) return 1440;
  if (isEnd && hh === 23 && mm === 59) return 1440;
  return hh * 60 + mm;
}

function parseOutageRangesMask(outagesArr) {
  if (!Array.isArray(outagesArr) || outagesArr.length === 0) return null;
  const mask = new Array(48).fill(false);
  let parsedAny = false;
  for (const item of outagesArr) {
    let startMin = null;
    let endMin = null;
    if (Array.isArray(item) && item.length >= 2) {
      startMin = parseHHmmToMinutes(item[0], false);
      endMin = parseHHmmToMinutes(item[1], true);
    } else if (typeof item === 'string') {
      const parts = item.split(/[-–—]/);
      if (parts.length === 2) {
        startMin = parseHHmmToMinutes(parts[0], false);
        endMin = parseHHmmToMinutes(parts[1], true);
      }
    }
    if (startMin !== null && endMin !== null && endMin > startMin) {
      parsedAny = true;
      for (let i = 0; i < 48; i++) {
        const sStart = i * 30;
        const sEnd = sStart + 30;
        if (startMin < sEnd && endMin > sStart) {
          mask[i] = true;
        }
      }
    }
  }
  return parsedAny ? mask : null;
}

function parseSvitlobotDaySlots(dayObj) {
  const slots = new Array(48).fill('ON');
  const rawSlots = dayObj.slots;
  const hasHourSlots = Array.isArray(rawSlots) && rawSlots.length >= 24;

  if (Array.isArray(rawSlots)) {
    if (rawSlots.length === 48) {
      for (let i = 0; i < 48; i++) {
        const v = String(rawSlots[i] ?? '0').trim().toLowerCase();
        slots[i] = (v === '1' || v === 'no' || v === 'off') ? 'OFF' : ((v === '2' || v === 'maybe') ? 'MAYBE' : 'ON');
      }
    } else if (rawSlots.length >= 24) {
      for (let h = 0; h < 24; h++) {
        const v = String(rawSlots[h] ?? '0').trim().toLowerCase();
        let pair;
        if (v === '0') pair = ['ON', 'ON'];
        else if (v === '1') pair = ['OFF', 'OFF'];
        else if (v === '2') pair = ['OFF', 'ON'];
        else if (v === '3') pair = ['ON', 'OFF'];
        else pair = mapHourStatus(v);
        slots[h * 2] = pair[0];
        slots[h * 2 + 1] = pair[1];
      }
    }
  }

  const mask = parseOutageRangesMask(dayObj.outages);
  if (mask) {
    if (!hasHourSlots) {
      for (let i = 0; i < 48; i++) {
        if (mask[i]) slots[i] = 'OFF';
      }
    } else {
      for (let h = 0; h < 24; h++) {
        const m1 = mask[h * 2];
        const m2 = mask[h * 2 + 1];
        if (m1 !== m2) {
          slots[h * 2] = m1 ? 'OFF' : 'ON';
          slots[h * 2 + 1] = m2 ? 'OFF' : 'ON';
        }
      }
    }
  }

  return slots;
}

function fixOeBoundaryMismatches(slots) {
  if (!Array.isArray(slots) || slots.length !== 48) return slots;
  const fixed = [...slots];
  for (let h = 0; h < 24; h++) {
    const i1 = h * 2;
    const i2 = i1 + 1;
    // OE_OUTAGE_DATA encodes OFF+MAYBE as msecond (ON+MAYBE); recover OFF when preceded by OFF
    if (fixed[i1] === 'ON' && fixed[i2] === 'MAYBE' && i1 > 0 && fixed[i1 - 1] === 'OFF') {
      fixed[i1] = 'OFF';
    }
    // OE_OUTAGE_DATA encodes MAYBE+OFF as mfirst (MAYBE+ON); recover OFF when followed by OFF
    if (fixed[i1] === 'MAYBE' && fixed[i2] === 'ON' && i2 + 1 < 48 && fixed[i2 + 1] === 'OFF') {
      fixed[i2] = 'OFF';
    }
  }
  return fixed;
}

function parseSvitlobotJson(rawJson, queueId, fetchedAtMillis, isFromCache) {
  const root = JSON.parse(rawJson);
  const rawGroup = root.group ? normalizeQueueId(root.group) : null;
  if (rawGroup && rawGroup !== queueId.trim()) {
    throw new Error(`Дані належать іншій черзі (${rawGroup})`);
  }
  if (!Array.isArray(root.days)) {
    throw new Error('Відсутній розділ days у графіку');
  }

  const days = {};
  for (const dayObj of root.days) {
    if (!dayObj || !/^\d{4}-\d{2}-\d{2}$/.test(String(dayObj.date || '').trim())) continue;
    const hasSlots = Array.isArray(dayObj.slots) && dayObj.slots.length > 0;
    const hasOutages = Array.isArray(dayObj.outages);
    if (!hasSlots && !hasOutages) continue;
    const dKey = String(dayObj.date).trim();
    days[dKey] = { dateKey: dKey, slots: parseSvitlobotDaySlots(dayObj), hasManualEdits: false };
  }

  if (Object.keys(days).length === 0) {
    throw new Error(`Не знайдено даних для черги ${queueId}`);
  }

  const sourceUpdatedRaw = String(root.source_updated_at || '').trim();
  const updatedRaw = String(root.updated_at || '').trim();
  const bestRaw = sourceUpdatedRaw || updatedRaw;
  const sourceUpdatedAtMillis = parseTimestampMillis(sourceUpdatedRaw) ?? parseTimestampMillis(updatedRaw);
  const lastUpdatedLabel = formatUpdatedLabel(bestRaw, fetchedAtMillis);

  return {
    queueId,
    availableQueues: [...DEFAULT_QUEUES],
    days,
    lastUpdatedLabel,
    fetchedAtMillis,
    isFromCache,
    sourceUpdatedAtMillis
  };
}

function parseJson(rawJson, queueId, fetchedAtMillis, isFromCache) {
  const root = JSON.parse(rawJson);
  if (Array.isArray(root.days)) {
    return parseSvitlobotJson(rawJson, queueId, fetchedAtMillis, isFromCache);
  }
  const fact = root.fact;
  const dataObj = fact && fact.data;
  if (!dataObj) throw new Error('Відсутній розділ fact.data у графіку');

  const days = {};
  const discoveredQueues = new Set();

  for (const tsKey of Object.keys(dataObj)) {
    const dKey = parseDayKey(tsKey);
    if (!dKey) continue;
    const dayQueues = dataObj[tsKey];
    if (!dayQueues || typeof dayQueues !== 'object') continue;

    for (const qk of Object.keys(dayQueues)) {
      const nq = normalizeQueueId(qk);
      if (nq) discoveredQueues.add(nq);
    }

    const queueObj = findQueueObject(dayQueues, queueId);
    if (!queueObj) continue;
    const isZeroIndexed = Object.prototype.hasOwnProperty.call(queueObj, '0') &&
      !Object.prototype.hasOwnProperty.call(queueObj, '24');

    const slots = new Array(48).fill('ON');
    for (let h = 0; h < 24; h++) {
      const key = String(isZeroIndexed ? h : h + 1);
      const status = String(queueObj[key] || 'yes').trim().toLowerCase();
      const [s1, s2] = mapHourStatus(status);
      slots[h * 2] = s1;
      slots[h * 2 + 1] = s2;
    }

    days[dKey] = { dateKey: dKey, slots: fixOeBoundaryMismatches(slots), hasManualEdits: false };
  }

  if (Object.keys(days).length === 0) {
    throw new Error(`Не знайдено даних для черги ${queueId}`);
  }

  // Cross-midnight boundary check between adjacent parsed OE days
  for (const dKey of Object.keys(days).sort()) {
    const day = days[dKey];
    const prevDay = days[addDaysToKey(dKey, -1)];
    const nextDay = days[addDaysToKey(dKey, 1)];
    if (prevDay && prevDay.slots[47] === 'OFF' && day.slots[0] === 'ON' && day.slots[1] === 'MAYBE') {
      day.slots[0] = 'OFF';
    }
    if (nextDay && nextDay.slots[0] === 'OFF' && day.slots[46] === 'MAYBE' && day.slots[47] === 'ON') {
      day.slots[47] = 'OFF';
    }
  }

  const factUpdateRaw = String(fact.update || '').trim();
  const rootUpdateRaw = String(root.lastUpdated || '').trim();
  const lastUpdatedLabel = formatUpdatedLabel(factUpdateRaw || rootUpdateRaw, fetchedAtMillis);
  const sourceUpdatedAtMillis = parseTimestampMillis(factUpdateRaw) ?? parseTimestampMillis(rootUpdateRaw);
  const availableQueues = discoveredQueues.size > 0
    ? Array.from(discoveredQueues).sort()
    : [...DEFAULT_QUEUES];

  return {
    queueId,
    availableQueues,
    days,
    lastUpdatedLabel,
    fetchedAtMillis,
    isFromCache,
    sourceUpdatedAtMillis
  };
}

function mergeWithSvitlobot(primary, secondary) {
  if (!primary || !secondary || primary.queueId !== secondary.queueId) return primary;

  const pTs = primary.sourceUpdatedAtMillis;
  const sTs = secondary.sourceUpdatedAtMillis;
  const toleranceMs = 5 * 60 * 1000;

  if (pTs != null && sTs != null && sTs < pTs - toleranceMs) {
    return primary;
  }
  const isSecondaryStrictlyNewer = pTs != null && sTs != null && sTs > pTs + toleranceMs;

  const mergedDays = { ...primary.days };
  for (const [dKey, sbDay] of Object.entries(secondary.days)) {
    const oeDay = mergedDays[dKey];
    if (!oeDay) {
      mergedDays[dKey] = sbDay;
      continue;
    }

    const mergedSlots = [...oeDay.slots];
    for (let h = 0; h < 24; h++) {
      const i1 = h * 2;
      const i2 = i1 + 1;
      const oe1 = oeDay.slots[i1];
      const oe2 = oeDay.slots[i2];
      const sb1 = sbDay.slots[i1];
      const sb2 = sbDay.slots[i2];

      if (oe1 === 'OFF' && oe2 === 'OFF' && sb1 === 'OFF' && sb2 === 'ON') {
        mergedSlots[i1] = 'OFF';
        mergedSlots[i2] = 'MAYBE';
      } else if (oe1 === 'OFF' && oe2 === 'OFF' && sb1 === 'ON' && sb2 === 'OFF') {
        mergedSlots[i1] = 'MAYBE';
        mergedSlots[i2] = 'OFF';
      } else if (oe1 === 'ON' && oe2 === 'ON' && (sb1 === 'OFF' || sb2 === 'OFF') && isSecondaryStrictlyNewer) {
        mergedSlots[i1] = sb1;
        mergedSlots[i2] = sb2;
      } else if (oe1 === 'OFF' && oe2 === 'OFF' && sb1 === 'ON' && sb2 === 'ON' && isSecondaryStrictlyNewer) {
        mergedSlots[i1] = 'ON';
        mergedSlots[i2] = 'ON';
      } else if ((oe1 === 'MAYBE' || oe2 === 'MAYBE') && (sb1 === 'OFF' || sb2 === 'OFF') && isSecondaryStrictlyNewer) {
        mergedSlots[i1] = sb1 === 'OFF' ? 'OFF' : oe1;
        mergedSlots[i2] = sb2 === 'OFF' ? 'OFF' : oe2;
      }
    }
    mergedDays[dKey] = { ...oeDay, slots: mergedSlots };
  }

  return {
    ...primary,
    days: mergedDays,
    lastUpdatedLabel: (isSecondaryStrictlyNewer && secondary.lastUpdatedLabel) ? secondary.lastUpdatedLabel : primary.lastUpdatedLabel,
    fetchedAtMillis: Math.max(primary.fetchedAtMillis, secondary.fetchedAtMillis),
    isFromCache: primary.isFromCache && secondary.isFromCache,
    sourceUpdatedAtMillis: isSecondaryStrictlyNewer ? sTs : pTs
  };
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
function svitlobotJsonKey(queueId) {
  return `svitlo31_svitlobot_json_${queueId.trim()}`;
}

function svitlobotFetchedKey(queueId) {
  return `svitlo31_svitlobot_at_${queueId.trim()}`;
}

function hasFreshSvitlobotCache(queueId = state.selectedQueue, nowMs = Date.now()) {
  const fetchedAt = Number(localStorage.getItem(svitlobotFetchedKey(queueId)) || '0');
  return fetchedAt > 0 && (nowMs - fetchedAt) < AUTO_REFRESH_MS;
}

function getCachedDataset(queueId = state.selectedQueue) {
  const rawOe = localStorage.getItem(KEYS.CACHED_JSON);
  const oeFetchedAt = Number(localStorage.getItem(KEYS.FETCHED_AT) || '0');
  const rawSb = localStorage.getItem(svitlobotJsonKey(queueId));
  const sbFetchedAt = Number(localStorage.getItem(svitlobotFetchedKey(queueId)) || '0');

  let oeParsed = null;
  if (rawOe) {
    try { oeParsed = parseJson(rawOe, queueId, oeFetchedAt, true); } catch {}
  }
  let sbParsed = null;
  if (rawSb) {
    try { sbParsed = parseSvitlobotJson(rawSb, queueId, sbFetchedAt, true); } catch {}
  }

  let combined = null;
  if (oeParsed && sbParsed) combined = mergeWithSvitlobot(oeParsed, sbParsed);
  else if (oeParsed) combined = oeParsed;
  else if (sbParsed) combined = sbParsed;

  return combined ? applyManualSlotOverrides(combined) : null;
}

const AUTO_REFRESH_MS = 15 * 60 * 1000;
let isFetching = false;
let lastFetchAttemptMs = 0;

async function fetchWithTimeout(url, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const resp = await fetch(url, { cache: 'no-store', signal: controller.signal });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    return await resp.text();
  } finally {
    clearTimeout(timer);
  }
}

async function fetchOeFeed(queueId, cacheBust) {
  let lastErr = null;
  for (const url of OE_FEED_URLS) {
    try {
      const text = await fetchWithTimeout(`${url}?t=${cacheBust}`, 12000);
      const now = Date.now();
      const parsed = parseJson(text, queueId, now, false);
      localStorage.setItem(KEYS.CACHED_JSON, text);
      localStorage.setItem(KEYS.FETCHED_AT, String(now));
      return parsed;
    } catch (err) {
      lastErr = err;
    }
  }
  throw lastErr || new Error('Не вдалося завантажити основний графік');
}

async function fetchSvitlobotFeed(queueId, cacheBust) {
  let lastErr = null;
  const q = queueId.trim();
  const candidates = Array.from(new Set([
    `${SVITLOBOT_BASE_URL}/${q}.json`,
    `${SVITLOBOT_BASE_URL}/${q.replace('.', '-')}.json`
  ]));
  for (const url of candidates) {
    try {
      const text = await fetchWithTimeout(`${url}?t=${cacheBust}`, 5000);
      const now = Date.now();
      const parsed = parseSvitlobotJson(text, q, now, false);
      localStorage.setItem(svitlobotJsonKey(q), text);
      localStorage.setItem(svitlobotFetchedKey(q), String(now));
      return parsed;
    } catch (err) {
      lastErr = err;
    }
  }
  throw lastErr || new Error('Не знайдено додаткових даних');
}

async function fetchSchedule() {
  if (isFetching) return;
  isFetching = true;
  lastFetchAttemptMs = Date.now();
  const targetQueue = state.selectedQueue;

  const refreshBtn = document.getElementById('btn-refresh');
  refreshBtn.classList.add('spinning');
  refreshBtn.disabled = true;

  const cacheBust = Math.floor(Date.now() / 60000);
  const [oeRes, sbRes] = await Promise.allSettled([
    fetchOeFeed(targetQueue, cacheBust),
    fetchSvitlobotFeed(targetQueue, cacheBust)
  ]);

  const oeDataset = oeRes.status === 'fulfilled' ? oeRes.value : null;
  const sbDataset = sbRes.status === 'fulfilled' ? sbRes.value : null;

  let combined = null;
  if (oeDataset && sbDataset) combined = mergeWithSvitlobot(oeDataset, sbDataset);
  else if (oeDataset) combined = oeDataset;
  else if (sbDataset) combined = sbDataset;

  isFetching = false;
  refreshBtn.classList.remove('spinning');
  refreshBtn.disabled = false;

  if (combined) {
    if (state.selectedQueue !== targetQueue) {
      const latest = getCachedDataset(state.selectedQueue);
      if (latest) {
        state.dataset = latest;
        state.error = null;
        state.isLoading = false;
        render();
      }
      if (!hasFreshSvitlobotCache(state.selectedQueue)) {
        fetchSchedule();
      }
      return;
    }
    state.dataset = applyManualSlotOverrides(combined);
    state.error = null;
    state.isLoading = false;
    render();
    return;
  }

  const cached = getCachedDataset();
  if (cached) {
    state.dataset = cached;
    state.error = null;
  } else if (!state.dataset) {
    const err = oeRes.status === 'rejected' ? oeRes.reason : (sbRes.status === 'rejected' ? sbRes.reason : null);
    state.error = err ? err.message : 'Перевірте інтернет-з’єднання';
  }
  state.isLoading = false;
  render();
  if (state.selectedQueue !== targetQueue && !hasFreshSvitlobotCache(state.selectedQueue)) {
    fetchSchedule();
  }
}

// --- Rendering ---
function renderSavedQueuesStrip() {
  const strip = document.getElementById('saved-queues-strip');
  if (!strip) return;
  const profiles = state.savedProfiles || [];
  const key = `${state.activeProfileId}|${profiles.map((p) => `${p.id}:${p.queueId}:${p.label}`).join(',')}`;
  if (strip.dataset.renderKey === key) return;
  strip.dataset.renderKey = key;
  strip.innerHTML = '';

  for (const p of profiles) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `saved-queue-chip${p.id === state.activeProfileId ? ' active' : ''}`;
    if (p.label) {
      const lbl = document.createElement('span');
      lbl.className = 'saved-queue-label';
      lbl.textContent = p.label;
      const num = document.createElement('span');
      num.className = 'saved-queue-num';
      num.textContent = `· ${p.queueId}`;
      btn.appendChild(lbl);
      btn.appendChild(num);
    } else {
      btn.textContent = `Черга ${p.queueId}`;
    }
    btn.addEventListener('click', () => activateProfile(p.id));
    strip.appendChild(btn);
  }

  const addBtn = document.createElement('button');
  addBtn.type = 'button';
  addBtn.className = 'saved-queue-chip-add';
  addBtn.textContent = '+ Додати місце';
  addBtn.addEventListener('click', () => openQueueModal(true));
  strip.appendChild(addBtn);
}

function render() {
  document.getElementById('queue-title-text').textContent = `Черга ${state.selectedQueue}`;
  const addrEl = document.getElementById('address-label-text');
  addrEl.textContent = state.addressLabel;
  addrEl.hidden = !state.addressLabel.trim();
  renderSavedQueuesStrip();

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
        const nextTime = snap.nextChangeTimeLabel;
        const isTomorrowLabel = nextTime.startsWith('завтра');
        nextLabelEl.hidden = false;
        nextLabelEl.textContent = snap.nextChangeTargetState === 'ON'
          ? (isTomorrowLabel ? `Увімкнення ${nextTime}` : `Увімкнення о ${nextTime}`)
          : (snap.nextChangeTargetState === 'MAYBE'
            ? (snap.effectiveState === 'ON'
              ? (isTomorrowLabel ? `Можливе вимкнення ${nextTime}` : `Можливе вимкнення о ${nextTime}`)
              : (isTomorrowLabel ? `Можливі відключення ${nextTime}` : `Можливі відключення з ${nextTime}`))
            : (isTomorrowLabel ? `Вимкнення ${nextTime}` : `Вимкнення о ${nextTime}`));
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

    // 24h bar grouped by 24 hour blocks (0px gap at :30, crisp boundary between hours)
    const timelineKey = `${activeDay.dateKey}:${activeDay.slots.join(',')}`;
    const timelineSlots = document.getElementById('timeline-slots');
    if (timelineSlots.dataset.renderKey !== timelineKey) {
      timelineSlots.dataset.renderKey = timelineKey;
      timelineSlots.innerHTML = '';
      for (let hr = 0; hr < 24; hr++) {
        const s1 = activeDay.slots[hr * 2] || 'ON';
        const s2 = activeDay.slots[hr * 2 + 1] || 'ON';
        const hrBlock = document.createElement('div');
        hrBlock.className = `t-hour${s1 !== s2 ? ' split' : ''}`;

        const half1 = document.createElement('div');
        half1.className = `t-slot ${s1.toLowerCase()}`;
        const half2 = document.createElement('div');
        half2.className = `t-slot ${s2.toLowerCase()}`;

        hrBlock.appendChild(half1);
        hrBlock.appendChild(half2);
        timelineSlots.appendChild(hrBlock);
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
        if (s1 !== s2) {
          const midDiv = document.createElement('span');
          midDiv.className = 'hour-divider-mid';
          halves.appendChild(midDiv);
        }
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
function activateProfile(profileId) {
  const target = selectSavedProfile(profileId);
  if (!target) return;
  state.savedProfiles = getSavedProfiles();
  state.activeProfileId = target.id;
  state.selectedQueue = target.queueId;
  state.addressLabel = target.label;
  setManualLive(null, 0);
  const reparsed = getCachedDataset(state.selectedQueue);
  if (reparsed) {
    state.dataset = reparsed;
    render();
    if (!hasFreshSvitlobotCache(state.selectedQueue)) {
      fetchSchedule();
    }
  } else {
    render();
    fetchSchedule();
  }
}

function syncPresetChipsUI() {
  const val = (document.getElementById('input-address').value || '').trim().toLowerCase();
  document.querySelectorAll('.preset-chip').forEach((chip) => {
    chip.classList.toggle('selected', (chip.dataset.preset || '').toLowerCase() === val);
  });
}

function syncQueueModalModeUI() {
  const isAdding = !state.editingProfileIdDraft;
  document.getElementById('queue-form-title').textContent = isAdding
    ? 'Нова черга / місце'
    : 'Редагувати місце';
  document.getElementById('btn-save-queue').textContent = isAdding ? 'Додати чергу' : 'Застосувати';
  document.getElementById('btn-new-profile-mode').hidden = isAdding;
  syncPresetChipsUI();
}

function renderSavedProfilesList() {
  const listEl = document.getElementById('saved-profiles-list');
  if (!listEl) return;
  const profiles = state.savedProfiles || [];
  listEl.innerHTML = '';

  for (const p of profiles) {
    const row = document.createElement('div');
    const isActive = p.id === state.activeProfileId;
    const isEditing = p.id === state.editingProfileIdDraft;
    row.className = `saved-profile-row${isEditing || isActive ? ' active' : ''}`;

    const infoBtn = document.createElement('button');
    infoBtn.type = 'button';
    infoBtn.className = 'saved-profile-info';

    const nameSpan = document.createElement('span');
    nameSpan.className = 'saved-profile-name';
    nameSpan.textContent = p.label || `Черга ${p.queueId}`;

    const subSpan = document.createElement('span');
    subSpan.className = 'saved-profile-sub';
    subSpan.textContent = `Черга ${p.queueId}${isActive ? ' · активна' : ''}`;

    infoBtn.appendChild(nameSpan);
    infoBtn.appendChild(subSpan);
    infoBtn.addEventListener('click', () => {
      state.editingProfileIdDraft = p.id;
      state.pickedQueueDraft = p.queueId;
      document.getElementById('input-address').value = p.label;
      activateProfile(p.id);
      renderSavedProfilesList();
      renderQueueGrid();
      syncQueueModalModeUI();
    });
    row.appendChild(infoBtn);

    if (profiles.length > 1) {
      const delBtn = document.createElement('button');
      delBtn.type = 'button';
      delBtn.className = 'saved-profile-delete';
      delBtn.setAttribute('aria-label', 'Видалити чергу');
      delBtn.textContent = '✕';
      delBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        const nextActive = deleteSavedProfile(p.id);
        if (!nextActive) return;
        state.savedProfiles = getSavedProfiles();
        state.activeProfileId = nextActive.id;
        state.selectedQueue = nextActive.queueId;
        state.addressLabel = nextActive.label;
        if (state.editingProfileIdDraft === p.id) {
          state.editingProfileIdDraft = nextActive.id;
          state.pickedQueueDraft = nextActive.queueId;
          document.getElementById('input-address').value = nextActive.label;
        }
        setManualLive(null, 0);
        const reparsed = getCachedDataset(state.selectedQueue);
        if (reparsed) state.dataset = reparsed;
        render();
        renderSavedProfilesList();
        renderQueueGrid();
        syncQueueModalModeUI();
      });
      row.appendChild(delBtn);
    }

    listEl.appendChild(row);
  }
}

function openQueueModal(startInAddMode = false) {
  state.savedProfiles = getSavedProfiles();
  if (startInAddMode) {
    state.editingProfileIdDraft = null;
    state.pickedQueueDraft = state.selectedQueue;
    document.getElementById('input-address').value = '';
  } else {
    state.editingProfileIdDraft = state.activeProfileId;
    state.pickedQueueDraft = state.selectedQueue;
    document.getElementById('input-address').value = state.addressLabel;
  }
  renderSavedProfilesList();
  renderQueueGrid();
  syncQueueModalModeUI();
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
      state.pickedQueueDraft = q;
      renderQueueGrid();
    });
    grid.appendChild(btn);
  }
}

function syncHourModalUI() {
  if (!state.editingHourTarget || !state.dataset) return;
  const { dateKey: dKey, hour: hr } = state.editingHourTarget;
  const day = state.dataset.days[dKey];
  const s1 = (day && day.slots[hr * 2]) || 'ON';
  const s2 = (day && day.slots[hr * 2 + 1]) || 'ON';

  document.querySelectorAll('.hour-option-btn').forEach((btn) => {
    btn.classList.toggle('selected', s1 === s2 && btn.dataset.state === s1);
  });
  document.querySelectorAll('.hour-split-btn').forEach((btn) => {
    btn.classList.toggle('selected', btn.dataset.first === s1 && btn.dataset.second === s2);
  });
  document.querySelectorAll('.half-chip-btn').forEach((btn) => {
    const half = Number(btn.dataset.half);
    const activeState = half === 0 ? s1 : s2;
    btn.classList.toggle('selected', btn.dataset.state === activeState);
  });
  const hasOverride = !!getHourOverride(state.selectedQueue, dKey, hr);
  document.getElementById('btn-reset-hour').hidden = !hasOverride;
}

function openHourModal(dKey, hr) {
  state.editingHourTarget = { dateKey: dKey, hour: hr };
  const h0 = String(hr).padStart(2, '0');
  const h1 = String(hr + 1).padStart(2, '0');
  document.getElementById('hour-modal-title').textContent = `Година ${h0}:00 – ${h1}:00`;
  document.getElementById('half-first-label').textContent = `${h0}:00 – ${h0}:30`;
  document.getElementById('half-second-label').textContent = `${h0}:30 – ${h1}:00`;
  syncHourModalUI();
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
  document.getElementById('btn-open-queue').addEventListener('click', () => openQueueModal(false));
  document.getElementById('btn-new-profile-mode').addEventListener('click', () => {
    state.editingProfileIdDraft = null;
    document.getElementById('input-address').value = '';
    renderSavedProfilesList();
    syncQueueModalModeUI();
  });
  document.querySelectorAll('.preset-chip').forEach((chip) => {
    chip.addEventListener('click', () => {
      document.getElementById('input-address').value = chip.dataset.preset || '';
      syncPresetChipsUI();
    });
  });
  document.getElementById('input-address').addEventListener('input', syncPresetChipsUI);
  document.getElementById('btn-cancel-queue').addEventListener('click', () => {
    document.getElementById('queue-modal').hidden = true;
  });
  document.getElementById('queue-modal').addEventListener('click', (e) => {
    if (e.target.id === 'queue-modal') document.getElementById('queue-modal').hidden = true;
  });
  document.getElementById('btn-save-queue').addEventListener('click', () => {
    const newAddr = document.getElementById('input-address').value.trim();
    const prevQueue = state.selectedQueue;
    const saved = saveQueueProfile(state.editingProfileIdDraft, state.pickedQueueDraft, newAddr);
    state.savedProfiles = getSavedProfiles();
    state.activeProfileId = saved.id;
    state.selectedQueue = saved.queueId;
    state.addressLabel = saved.label;
    if (prevQueue !== saved.queueId || !state.editingProfileIdDraft) {
      setManualLive(null, 0);
    }
    document.getElementById('queue-modal').hidden = true;
    const reparsed = getCachedDataset(state.selectedQueue);
    if (reparsed) {
      state.dataset = reparsed;
      render();
      if (!hasFreshSvitlobotCache(state.selectedQueue)) {
        fetchSchedule();
      }
    } else {
      render();
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
  document.querySelectorAll('.hour-split-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      if (!state.editingHourTarget) return;
      const { dateKey: dKey, hour } = state.editingHourTarget;
      setHourSlotsOverride(state.selectedQueue, dKey, hour, btn.dataset.first, btn.dataset.second);
      document.getElementById('hour-modal').hidden = true;
      reapplyLocalDataset();
    });
  });
  document.querySelectorAll('.half-chip-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      if (!state.editingHourTarget) return;
      const { dateKey: dKey, hour } = state.editingHourTarget;
      const half = Number(btn.dataset.half);
      setSlotOverride(state.selectedQueue, dKey, hour * 2 + half, btn.dataset.state);
      reapplyLocalDataset();
      syncHourModalUI();
    });
  });
  document.getElementById('btn-reset-hour').addEventListener('click', () => {
    if (!state.editingHourTarget) return;
    const { dateKey: dKey, hour } = state.editingHourTarget;
    setHourOverride(state.selectedQueue, dKey, hour, null);
    document.getElementById('hour-modal').hidden = true;
    reapplyLocalDataset();
  });

  // PWA Install Prompt (Chromium + Apple Safari)
  function isStandaloneInstalled() {
    return window.matchMedia('(display-mode: standalone)').matches ||
      (typeof navigator !== 'undefined' && navigator.standalone === true);
  }

  function isAppleMobileDevice() {
    const ua = navigator.userAgent || '';
    return /iPad|iPhone|iPod/.test(ua) ||
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  }

  function isAppleDesktopSafari() {
    const ua = navigator.userAgent || '';
    return /Macintosh/.test(ua) && /Safari/.test(ua) && !/Chrome|CriOS|FxiOS|Edg|OPR/.test(ua);
  }

  const installBtn = document.getElementById('btn-install-pwa');
  const installBanner = document.getElementById('install-banner');
  const appleModal = document.getElementById('apple-install-modal');

  function showInstallAffordances() {
    if (isStandaloneInstalled()) return;
    installBtn.hidden = false;
    if (localStorage.getItem('svitlo31_install_banner_dismissed') !== '1') {
      installBanner.hidden = false;
    }
  }

  if (!isStandaloneInstalled() && (isAppleMobileDevice() || isAppleDesktopSafari())) {
    showInstallAffordances();
  }

  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    state.deferredInstallPrompt = e;
    showInstallAffordances();
  });

  window.addEventListener('appinstalled', () => {
    installBtn.hidden = true;
    installBanner.hidden = true;
    state.deferredInstallPrompt = null;
  });

  function openAppleInstallGuide() {
    const isDesktopMac = !isAppleMobileDevice() && isAppleDesktopSafari();
    document.getElementById('apple-install-title').textContent = isDesktopMac
      ? 'Встановлення на Mac (Safari)'
      : 'Встановлення на iPhone / iPad';
    document.getElementById('apple-steps-ios').hidden = isDesktopMac;
    document.getElementById('apple-steps-mac').hidden = !isDesktopMac;
    appleModal.hidden = false;
  }

  async function triggerInstall() {
    if (state.deferredInstallPrompt) {
      state.deferredInstallPrompt.prompt();
      const choice = await state.deferredInstallPrompt.userChoice;
      if (choice.outcome === 'accepted') {
        installBtn.hidden = true;
        installBanner.hidden = true;
      }
      state.deferredInstallPrompt = null;
      return;
    }
    openAppleInstallGuide();
  }

  installBtn.addEventListener('click', triggerInstall);
  document.getElementById('btn-install-banner').addEventListener('click', triggerInstall);
  document.getElementById('btn-dismiss-install-banner').addEventListener('click', () => {
    installBanner.hidden = true;
    localStorage.setItem('svitlo31_install_banner_dismissed', '1');
  });
  document.getElementById('btn-close-apple-install').addEventListener('click', () => {
    appleModal.hidden = true;
  });
  appleModal.addEventListener('click', (e) => {
    if (e.target.id === 'apple-install-modal') appleModal.hidden = true;
  });
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
