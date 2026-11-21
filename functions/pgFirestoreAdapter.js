const crypto = require("crypto");

function makeTimestampWrapper(dateObj) {
  const safeDate = dateObj instanceof Date && Number.isFinite(dateObj.getTime()) ? dateObj : new Date();
  const ms = safeDate.getTime();
  return {
    seconds: Math.floor(ms / 1000),
    nanoseconds: (ms % 1000) * 1_000_000,
    toDate() {
      return new Date(ms);
    },
    toMillis() {
      return ms;
    },
    toISOString() {
      return safeDate.toISOString();
    },
    toJSON() {
      return safeDate.toISOString();
    },
  };
}

function serializeValue(val) {
  if (val === undefined) return undefined;
  if (val === null) return null;
  if (val instanceof Date) {
    return { __vidvora_ts__: val.toISOString() };
  }
  if (typeof val === "object") {
    if (typeof val.toDate === "function") {
      try {
        return { __vidvora_ts__: val.toDate().toISOString() };
      } catch (_) {
        return { __vidvora_ts__: new Date().toISOString() };
      }
    }
    // Support Firestore FieldValue sentinels if passed in
    const ctorName = val.constructor && val.constructor.name ? String(val.constructor.name) : "";
    if (ctorName === "ServerTimestampTransform" || val.__vidvora_server_ts__ === true) {
      return { __vidvora_ts__: new Date().toISOString() };
    }
    if (typeof val.__vidvora_ts_ms__ === "number" && Number.isFinite(val.__vidvora_ts_ms__)) {
      return { __vidvora_ts__: new Date(val.__vidvora_ts_ms__).toISOString() };
    }
    if (ctorName === "NumericIncrementTransform" && typeof val.operand === "number") {
      return { __vidvora_inc__: val.operand };
    }
    if (Array.isArray(val)) {
      return val.map((item) => serializeValue(item)).filter((item) => item !== undefined);
    }
    const out = {};
    for (const [k, v] of Object.entries(val)) {
      const sv = serializeValue(v);
      if (sv !== undefined) {
        out[k] = sv;
      }
    }
    return out;
  }
  return val;
}

function hydrateValue(val) {
  if (val === null || val === undefined) return val;
  if (Array.isArray(val)) {
    return val.map((item) => hydrateValue(item));
  }
  if (typeof val === "object") {
    if (typeof val.__vidvora_ts__ === "string" && Object.keys(val).length === 1) {
      return makeTimestampWrapper(new Date(val.__vidvora_ts__));
    }
    const out = {};
    for (const [k, v] of Object.entries(val)) {
      out[k] = hydrateValue(v);
    }
    return out;
  }
  return val;
}

function serializeForRestApi(val) {
  if (val === null || val === undefined) return val;
  if (val instanceof Date) return val.getTime();
  if (Array.isArray(val)) return val.map((item) => serializeForRestApi(item));
  if (typeof val === "object") {
    if (typeof val.toMillis === "function") return val.toMillis();
    if (typeof val.toDate === "function") return val.toDate().getTime();
    if (typeof val.__vidvora_ts__ === "string" && Object.keys(val).length === 1) {
      const ms = new Date(val.__vidvora_ts__).getTime();
      return Number.isFinite(ms) ? ms : 0;
    }
    const out = {};
    for (const [k, v] of Object.entries(val)) {
      out[k] = serializeForRestApi(v);
    }
    return out;
  }
  return val;
}

function applyMergePatch(baseObj, patchObj) {
  const result = baseObj && typeof baseObj === "object" && !Array.isArray(baseObj) ? { ...baseObj } : {};
  if (!patchObj || typeof patchObj !== "object") return result;

  for (const [rawKey, rawVal] of Object.entries(patchObj)) {
    if (rawVal === undefined) continue;
    const serialized = serializeValue(rawVal);
    if (serialized === undefined) continue;

    // Support dotted update paths like "hourlyViews.2026-11-18T10"
    if (rawKey.includes(".")) {
      const parts = rawKey.split(".");
      let cursor = result;
      for (let i = 0; i < parts.length - 1; i += 1) {
        const part = parts[i];
        if (!cursor[part] || typeof cursor[part] !== "object" || Array.isArray(cursor[part])) {
          cursor[part] = {};
        } else {
          cursor[part] = { ...cursor[part] };
        }
        cursor = cursor[part];
      }
      const leaf = parts[parts.length - 1];
      if (serialized && typeof serialized === "object" && typeof serialized.__vidvora_inc__ === "number") {
        cursor[leaf] = (Number(cursor[leaf]) || 0) + serialized.__vidvora_inc__;
      } else {
        cursor[leaf] = serialized;
      }
      continue;
    }

    if (serialized && typeof serialized === "object" && typeof serialized.__vidvora_inc__ === "number") {
      result[rawKey] = (Number(result[rawKey]) || 0) + serialized.__vidvora_inc__;
    } else if (
      serialized &&
      typeof serialized === "object" &&
      !Array.isArray(serialized) &&
      !serialized.__vidvora_ts__ &&
      result[rawKey] &&
      typeof result[rawKey] === "object" &&
      !Array.isArray(result[rawKey]) &&
      !result[rawKey].__vidvora_ts__
    ) {
      result[rawKey] = applyMergePatch(result[rawKey], serialized);
    } else {
      result[rawKey] = serialized;
    }
  }
  return result;
}

function getFieldValue(docData, fieldPath) {
  if (!docData || typeof docData !== "object") return undefined;
  const parts = String(fieldPath || "").split(".");
  let cursor = docData;
  for (const part of parts) {
    if (cursor == null || typeof cursor !== "object") return undefined;
    cursor = cursor[part];
  }
  return cursor;
}

function toComparable(val) {
  if (val && typeof val === "object") {
    if (typeof val.toMillis === "function") return val.toMillis();
    if (typeof val.__vidvora_ts__ === "string") return new Date(val.__vidvora_ts__).getTime();
  }
  if (val instanceof Date) return val.getTime();
  return val;
}

function matchesWhereClause(docData, { field, op, value }) {
  const actual = toComparable(getFieldValue(docData, field));
  const target = toComparable(value);
  switch (op) {
    case "==":
      return actual === target;
    case "!=":
      return actual !== target;
    case ">":
      return actual !== undefined && actual > target;
    case ">=":
      return actual !== undefined && actual >= target;
    case "<":
      return actual !== undefined && actual < target;
    case "<=":
      return actual !== undefined && actual <= target;
    case "in":
      return Array.isArray(value) && value.map(toComparable).includes(actual);
    case "array-contains":
      return Array.isArray(actual) && actual.map(toComparable).includes(target);
    default:
      return actual === target;
  }
}

const INDEXED_TEXT_FIELDS = new Set([
  "tokenHash",
  "passkey",
  "uploadedBy",
  "memberId",
  "videoId",
  "emailOrPhone",
  "storagePath",
]);

const FULL_CACHE_COLLECTIONS = new Set([
  "videos",
  "members",
  "withdrawalRequests",
  "notifications",
  "creatorEvents",
  "appInstalls",
  "appConfig",
  "supportChats",
]);

const DEFAULT_REVERIFY_INTERVAL_MS = Math.max(
  30_000,
  parseInt(process.env.PG_CACHE_REVERIFY_MS || String(5 * 60 * 1000), 10) || 5 * 60 * 1000
);
const DEFAULT_DOC_CACHE_TTL_MS = Math.max(
  30_000,
  parseInt(process.env.PG_DOC_CACHE_TTL_MS || String(10 * 60 * 1000), 10) || 10 * 60 * 1000
);
const DEFAULT_QUERY_CACHE_TTL_MS = Math.max(
  15_000,
  parseInt(process.env.PG_QUERY_CACHE_TTL_MS || String(3 * 60 * 1000), 10) || 3 * 60 * 1000
);
const MAX_FULL_CACHE_DOCS = 2500;
const MAX_DOC_CACHE_ENTRIES = 600;
const MAX_QUERY_CACHE_ENTRIES = 100;

function isFullCacheCollection(collectionName) {
  if (FULL_CACHE_COLLECTIONS.has(collectionName)) return true;
  // Also cache supportChats/{chatId}/messages subcollections with short reverify
  if (/^supportChats\/[^/]+\/messages$/.test(collectionName)) return true;
  return false;
}

function createPgFirestoreDb({
  connectionString = "",
  poolOverride = null,
  enableCache = true,
  reverifyIntervalMs = DEFAULT_REVERIFY_INTERVAL_MS,
  docCacheTtlMs = DEFAULT_DOC_CACHE_TTL_MS,
  queryCacheTtlMs = DEFAULT_QUERY_CACHE_TTL_MS,
} = {}) {
  const isRealPostgres = !poolOverride;
  let pool = poolOverride || null;
  if (!pool) {
    const cleanUrl = String(connectionString || "").trim();
    if (!cleanUrl) {
      throw new Error("DATABASE_URL is required to initialize PostgreSQL adapter.");
    }
    const { Pool } = require("pg");
    const needsSsl =
      !/localhost|127\.0\.0\.1/i.test(cleanUrl) &&
      !/sslmode=disable/i.test(cleanUrl);
    pool = new Pool({
      connectionString: cleanUrl,
      max: 5,
      idleTimeoutMillis: 20_000,
      connectionTimeoutMillis: 15_000,
      ...(needsSsl ? { ssl: { rejectUnauthorized: false } } : {}),
    });
    pool.on("error", (err) => {
      console.error("PostgreSQL pool idle client error:", err?.message || err);
    });
  }

  // Write-through in-memory caches to avoid repeatedly transferring large JSONB tables
  // (e.g. videos with thumbnails) from Neon over public network transfer.
  const collectionCache = new Map(); // collection -> { docs: Map<docId, rawObj>, verifiedAt, lastMaxUpdatedMs, lastRowCount, metaSynced }
  const collectionVersions = new Map(); // collection -> { structure: number, full: number }
  const inflightCollectionFetches = new Map(); // collection -> Promise<entry | null>
  const docCache = new Map(); // `${collection}::${docId}` -> { exists, raw, expiresAt }
  const inflightDocFetches = new Map(); // `${collection}::${docId}` -> Promise<entry>
  const queryCache = new Map(); // cacheKey -> { collection, rows, expiresAt }
  const stats = {
    cacheHits: 0,
    dbReads: 0,
    dbWrites: 0,
    deltaSyncs: 0,
    fastIncrements: 0,
  };

  function getDocCacheKey(collectionName, docId) {
    return `${collectionName}::${docId}`;
  }

  function setDocCacheEntry(collectionName, docId, exists, raw) {
    if (!enableCache) return;
    const key = getDocCacheKey(collectionName, docId);
    if (docCache.has(key)) docCache.delete(key);
    if (docCache.size >= MAX_DOC_CACHE_ENTRIES) {
      const oldestKey = docCache.keys().next().value;
      if (oldestKey !== undefined) docCache.delete(oldestKey);
    }
    docCache.set(key, {
      exists: Boolean(exists),
      raw: exists ? raw : null,
      expiresAt: Date.now() + docCacheTtlMs,
    });
  }

  function getDocCacheEntry(collectionName, docId) {
    if (!enableCache) return null;
    const colEntry = collectionCache.get(collectionName);
    if (colEntry) {
      const hasDoc = colEntry.docs.has(docId);
      return {
        exists: hasDoc,
        raw: hasDoc ? colEntry.docs.get(docId) : null,
        fromCollectionCache: true,
      };
    }
    const key = getDocCacheKey(collectionName, docId);
    const hit = docCache.get(key);
    if (!hit) return null;
    if (Date.now() > hit.expiresAt) {
      docCache.delete(key);
      return null;
    }
    return hit;
  }

  function invalidateQueryCacheForCollection(collectionName) {
    if (!enableCache || queryCache.size === 0) return;
    for (const [k, v] of queryCache.entries()) {
      if (v.collection === collectionName) {
        queryCache.delete(k);
      }
    }
  }

  function bumpCollectionVersion(collectionName, isMinorCounterOnly = false) {
    const prev = collectionVersions.get(collectionName) || { structure: 0, full: 0 };
    const next = {
      structure: isMinorCounterOnly ? prev.structure : prev.structure + 1,
      full: prev.full + 1,
    };
    collectionVersions.set(collectionName, next);
  }

  function updateCachesAfterWrite(
    collectionName,
    docId,
    nextData,
    returnedUpdatedMs = null,
    isMinorCounterOnly = false
  ) {
    bumpCollectionVersion(collectionName, isMinorCounterOnly);
    if (!enableCache) return;
    const colEntry = collectionCache.get(collectionName);
    if (colEntry) {
      colEntry.docs.set(docId, nextData);
      colEntry.lastRowCount = colEntry.docs.size;
      colEntry.verifiedAt = Date.now();
      if (Number.isFinite(returnedUpdatedMs) && returnedUpdatedMs > 0) {
        colEntry.lastMaxUpdatedMs = Math.max(colEntry.lastMaxUpdatedMs || 0, returnedUpdatedMs);
      }
      // Avoid duplicating full-cached collection docs into docCache (saves RAM)
      docCache.delete(getDocCacheKey(collectionName, docId));
    } else {
      setDocCacheEntry(collectionName, docId, true, nextData);
    }
    invalidateQueryCacheForCollection(collectionName);
  }

  function updateCachesAfterDelete(collectionName, docId) {
    bumpCollectionVersion(collectionName, false);
    if (!enableCache) return;
    const colEntry = collectionCache.get(collectionName);
    if (colEntry) {
      colEntry.docs.delete(docId);
      colEntry.lastRowCount = colEntry.docs.size;
      colEntry.verifiedAt = Date.now();
      docCache.delete(getDocCacheKey(collectionName, docId));
    } else {
      setDocCacheEntry(collectionName, docId, false, null);
    }
    invalidateQueryCacheForCollection(collectionName);
  }

  async function ensureCollectionCached(collectionName) {
    if (!enableCache || !isFullCacheCollection(collectionName)) return null;

    const now = Date.now();
    const existing = collectionCache.get(collectionName);
    const effectiveReverifyMs = collectionName.startsWith("supportChats/")
      ? Math.min(reverifyIntervalMs, 60_000)
      : reverifyIntervalMs;

    if (existing && now - existing.verifiedAt < effectiveReverifyMs) {
      return existing;
    }

    if (inflightCollectionFetches.has(collectionName)) {
      return inflightCollectionFetches.get(collectionName);
    }

    const fetchPromise = (async () => {
      try {
        await ensureInitialized();

        // If already cached on real Postgres, do a tiny ~20-byte COUNT/MAX(updated_at) check
        // instead of re-downloading megabytes of JSONB thumbnails.
        if (existing && isRealPostgres) {
          try {
            const metaRes = await pool.query(
              `SELECT COUNT(*)::int AS cnt, COALESCE(EXTRACT(EPOCH FROM MAX(updated_at)) * 1000, 0)::bigint AS max_ts
               FROM vidvora_docs
               WHERE collection = $1`,
              [collectionName]
            );
            const row = metaRes.rows && metaRes.rows[0] ? metaRes.rows[0] : null;
            const dbCount = row ? Number(row.cnt) || 0 : 0;
            const dbMaxTs = row ? Number(row.max_ts) || 0 : 0;

            if (!existing.metaSynced) {
              const refMs = Math.max(existing.verifiedAt + 60_000, existing.lastMaxUpdatedMs || 0);
              if (dbCount === existing.docs.size && dbMaxTs <= refMs) {
                existing.lastRowCount = dbCount;
                existing.lastMaxUpdatedMs = dbMaxTs;
                existing.metaSynced = true;
                existing.verifiedAt = Date.now();
                stats.deltaSyncs += 1;
                return existing;
              }
            } else if (dbCount === existing.lastRowCount && dbMaxTs <= existing.lastMaxUpdatedMs) {
              existing.verifiedAt = Date.now();
              stats.deltaSyncs += 1;
              return existing;
            } else if (dbCount >= existing.lastRowCount && existing.lastMaxUpdatedMs > 0) {
              // Only pull rows modified since our last synced timestamp!
              const deltaRes = await pool.query(
                `SELECT doc_id, data FROM vidvora_docs
                 WHERE collection = $1 AND updated_at > to_timestamp($2 / 1000.0)`,
                [collectionName, existing.lastMaxUpdatedMs]
              );
              stats.dbReads += 1;
              stats.deltaSyncs += 1;
              for (const r of deltaRes.rows || []) {
                existing.docs.set(r.doc_id, r.data || {});
              }
              if ((deltaRes.rows || []).length > 0) {
                bumpCollectionVersion(collectionName, false);
              }
              if (existing.docs.size <= MAX_FULL_CACHE_DOCS && existing.docs.size === dbCount) {
                existing.lastRowCount = dbCount;
                existing.lastMaxUpdatedMs = Math.max(existing.lastMaxUpdatedMs, dbMaxTs);
                existing.verifiedAt = Date.now();
                return existing;
              }
            }
          } catch (metaErr) {
            console.warn("PG cache lightweight reverify fallback:", metaErr?.message || metaErr);
          }
        }

        // Cold load or count mismatch (e.g. external delete): load full collection once.
        stats.dbReads += 1;
        const res = await pool.query(
          `SELECT doc_id, data FROM vidvora_docs WHERE collection = $1`,
          [collectionName]
        );
        const rows = res.rows || [];
        if (rows.length > MAX_FULL_CACHE_DOCS) {
          collectionCache.delete(collectionName);
          return null;
        }
        const docsMap = new Map();
        for (const r of rows) {
          docsMap.set(r.doc_id, r.data || {});
        }
        const entry = {
          docs: docsMap,
          verifiedAt: Date.now(),
          lastRowCount: docsMap.size,
          lastMaxUpdatedMs: 0,
          metaSynced: false,
        };
        collectionCache.set(collectionName, entry);
        bumpCollectionVersion(collectionName, false);
        return entry;
      } finally {
        inflightCollectionFetches.delete(collectionName);
      }
    })();

    inflightCollectionFetches.set(collectionName, fetchPromise);
    return fetchPromise;
  }

  let initPromise = null;
  async function ensureInitialized() {
    if (!initPromise) {
      initPromise = (async () => {
        await pool.query(`
          CREATE TABLE IF NOT EXISTS vidvora_docs (
            collection TEXT NOT NULL,
            doc_id TEXT NOT NULL,
            data JSONB NOT NULL DEFAULT '{}'::jsonb,
            updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            PRIMARY KEY (collection, doc_id)
          )
        `);
        await pool.query(
          `CREATE INDEX IF NOT EXISTS idx_vidvora_docs_collection ON vidvora_docs (collection)`
        );
        await pool.query(
          `CREATE INDEX IF NOT EXISTS idx_vidvora_docs_token_hash ON vidvora_docs (collection, (data->>'tokenHash'))`
        );
        await pool.query(
          `CREATE INDEX IF NOT EXISTS idx_vidvora_docs_passkey ON vidvora_docs (collection, (data->>'passkey'))`
        );
        await pool.query(
          `CREATE INDEX IF NOT EXISTS idx_vidvora_docs_uploaded_by ON vidvora_docs (collection, (data->>'uploadedBy'))`
        );
        await pool.query(
          `CREATE INDEX IF NOT EXISTS idx_vidvora_docs_member_id ON vidvora_docs (collection, (data->>'memberId'))`
        );
        await pool.query(
          `CREATE INDEX IF NOT EXISTS idx_vidvora_docs_video_id ON vidvora_docs (collection, (data->>'videoId'))`
        );
        await pool.query(
          `CREATE INDEX IF NOT EXISTS idx_vidvora_docs_storage_path ON vidvora_docs (collection, (data->>'storagePath'))`
        );
      })().catch((err) => {
        initPromise = null;
        throw err;
      });
    }
    return initPromise;
  }

  function makeDocSnapshot(collectionName, docId, rawData) {
    const ref = makeDocRef(collectionName, docId);
    if (!rawData) {
      return {
        id: docId,
        ref,
        exists: false,
        data: () => undefined,
      };
    }
    const hydrated = hydrateValue(rawData);
    return {
      id: docId,
      ref,
      exists: true,
      data: () => ({ ...hydrated }),
    };
  }

  function applyQueryTransforms(rows, clauses, orderBys, limitCount) {
    let out = rows;
    if (clauses.length > 0) {
      out = out.filter((r) => clauses.every((c) => matchesWhereClause(r.raw, c)));
    }
    if (orderBys.length > 0) {
      out = [...out].sort((a, b) => {
        for (const ob of orderBys) {
          const av = toComparable(getFieldValue(a.raw, ob.field));
          const bv = toComparable(getFieldValue(b.raw, ob.field));
          if (av === bv) continue;
          if (av === undefined || av === null) return ob.direction === "desc" ? 1 : -1;
          if (bv === undefined || bv === null) return ob.direction === "desc" ? -1 : 1;
          const cmp = av < bv ? -1 : 1;
          return ob.direction === "desc" ? -cmp : cmp;
        }
        return 0;
      });
    }
    if (Number.isFinite(limitCount) && limitCount > 0) {
      out = out.slice(0, limitCount);
    }
    return out;
  }

  function buildQuerySnapshot(collectionName, rows) {
    const docs = rows.map((r) => makeDocSnapshot(collectionName, r.id, r.raw));
    return {
      empty: docs.length === 0,
      size: docs.length,
      docs,
      forEach(fn) {
        docs.forEach(fn);
      },
    };
  }

  function makeDocRef(collectionName, docId) {
    const cleanId = String(docId || "").trim() || `doc_${crypto.randomBytes(8).toString("hex")}`;
    return {
      id: cleanId,
      collection(subName) {
        return makeCollectionRef(`${collectionName}/${cleanId}/${subName}`);
      },
      async get() {
        await ensureInitialized();

        const cached = getDocCacheEntry(collectionName, cleanId);
        if (cached) {
          stats.cacheHits += 1;
          return makeDocSnapshot(collectionName, cleanId, cached.exists ? cached.raw : null);
        }

        const key = getDocCacheKey(collectionName, cleanId);
        if (inflightDocFetches.has(key)) {
          const shared = await inflightDocFetches.get(key);
          return makeDocSnapshot(collectionName, cleanId, shared.exists ? shared.raw : null);
        }

        const fetchPromise = (async () => {
          try {
            stats.dbReads += 1;
            const res = await pool.query(
              `SELECT data FROM vidvora_docs WHERE collection = $1 AND doc_id = $2 LIMIT 1`,
              [collectionName, cleanId]
            );
            const found = Boolean(res.rows && res.rows.length > 0);
            const raw = found ? res.rows[0].data : null;
            setDocCacheEntry(collectionName, cleanId, found, raw);
            return { exists: found, raw };
          } finally {
            inflightDocFetches.delete(key);
          }
        })();

        inflightDocFetches.set(key, fetchPromise);
        const result = await fetchPromise;
        return makeDocSnapshot(collectionName, cleanId, result.exists ? result.raw : null);
      },
      async set(payload, options = {}) {
        await ensureInitialized();
        let nextData;
        if (options && options.merge) {
          let base = null;
          const cached = getDocCacheEntry(collectionName, cleanId);
          if (cached) {
            base = cached.exists ? cached.raw : {};
            stats.cacheHits += 1;
          } else {
            stats.dbReads += 1;
            const existing = await pool.query(
              `SELECT data FROM vidvora_docs WHERE collection = $1 AND doc_id = $2 LIMIT 1`,
              [collectionName, cleanId]
            );
            base = existing.rows && existing.rows[0] ? existing.rows[0].data : {};
          }
          nextData = applyMergePatch(base, payload);
        } else {
          nextData = applyMergePatch({}, payload);
        }
        stats.dbWrites += 1;
        const sql = isRealPostgres
          ? `INSERT INTO vidvora_docs (collection, doc_id, data, updated_at)
             VALUES ($1, $2, $3::jsonb, NOW())
             ON CONFLICT (collection, doc_id)
             DO UPDATE SET data = EXCLUDED.data, updated_at = NOW()
             RETURNING (EXTRACT(EPOCH FROM updated_at) * 1000)::bigint AS updated_ms`
          : `INSERT INTO vidvora_docs (collection, doc_id, data, updated_at)
             VALUES ($1, $2, $3::jsonb, NOW())
             ON CONFLICT (collection, doc_id)
             DO UPDATE SET data = EXCLUDED.data, updated_at = NOW()`;
        const res = await pool.query(sql, [collectionName, cleanId, JSON.stringify(nextData)]);
        const returnedMs =
          isRealPostgres && res.rows && res.rows[0] ? Number(res.rows[0].updated_ms) || null : null;
        updateCachesAfterWrite(collectionName, cleanId, nextData, returnedMs);
      },
      async update(updates) {
        await ensureInitialized();

        // Fast-path for simple atomic numeric increment (e.g. { totalViews: { __vidvora_inc__: 1 } })
        // on real Postgres: avoids downloading OR re-uploading large base64 thumbnail JSONB!
        const updateEntries = updates && typeof updates === "object" ? Object.entries(updates) : [];
        if (
          isRealPostgres &&
          updateEntries.length === 1 &&
          /^[a-zA-Z0-9_]+$/.test(updateEntries[0][0])
        ) {
          const [incKey, incRaw] = updateEntries[0];
          const serializedInc = serializeValue(incRaw);
          if (
            serializedInc &&
            typeof serializedInc === "object" &&
            typeof serializedInc.__vidvora_inc__ === "number" &&
            Number.isFinite(serializedInc.__vidvora_inc__)
          ) {
            const delta = serializedInc.__vidvora_inc__;
            try {
              stats.dbWrites += 1;
              const fastRes = await pool.query(
                `UPDATE vidvora_docs
                 SET data = jsonb_set(
                   data,
                   ARRAY[$3::text],
                   to_jsonb(COALESCE((data->>$3)::numeric, 0) + $4),
                   true
                 ),
                 updated_at = NOW()
                 WHERE collection = $1 AND doc_id = $2
                 RETURNING (EXTRACT(EPOCH FROM updated_at) * 1000)::bigint AS updated_ms`,
                [collectionName, cleanId, incKey, delta]
              );
              if (fastRes.rows && fastRes.rows.length > 0) {
                stats.fastIncrements += 1;
                const returnedMs = Number(fastRes.rows[0].updated_ms) || null;
                const cached = getDocCacheEntry(collectionName, cleanId);
                if (cached && cached.exists && cached.raw) {
                  const patched = applyMergePatch(cached.raw, updates);
                  updateCachesAfterWrite(collectionName, cleanId, patched, returnedMs, true);
                } else {
                  bumpCollectionVersion(collectionName, true);
                  const colEntry = collectionCache.get(collectionName);
                  if (colEntry && Number.isFinite(returnedMs) && returnedMs > 0) {
                    colEntry.lastMaxUpdatedMs = Math.max(colEntry.lastMaxUpdatedMs || 0, returnedMs);
                  }
                  invalidateQueryCacheForCollection(collectionName);
                }
                return;
              }
            } catch (fastErr) {
              console.warn("PG fast increment fallback:", fastErr?.message || fastErr);
            }
          }
        }

        let base = null;
        const cached = getDocCacheEntry(collectionName, cleanId);
        if (cached) {
          base = cached.exists ? cached.raw : {};
          stats.cacheHits += 1;
        } else {
          stats.dbReads += 1;
          const existing = await pool.query(
            `SELECT data FROM vidvora_docs WHERE collection = $1 AND doc_id = $2 LIMIT 1`,
            [collectionName, cleanId]
          );
          base = existing.rows && existing.rows[0] ? existing.rows[0].data : {};
        }
        const nextData = applyMergePatch(base, updates);
        stats.dbWrites += 1;
        const sql = isRealPostgres
          ? `INSERT INTO vidvora_docs (collection, doc_id, data, updated_at)
             VALUES ($1, $2, $3::jsonb, NOW())
             ON CONFLICT (collection, doc_id)
             DO UPDATE SET data = EXCLUDED.data, updated_at = NOW()
             RETURNING (EXTRACT(EPOCH FROM updated_at) * 1000)::bigint AS updated_ms`
          : `INSERT INTO vidvora_docs (collection, doc_id, data, updated_at)
             VALUES ($1, $2, $3::jsonb, NOW())
             ON CONFLICT (collection, doc_id)
             DO UPDATE SET data = EXCLUDED.data, updated_at = NOW()`;
        const res = await pool.query(sql, [collectionName, cleanId, JSON.stringify(nextData)]);
        const returnedMs =
          isRealPostgres && res.rows && res.rows[0] ? Number(res.rows[0].updated_ms) || null : null;
        updateCachesAfterWrite(collectionName, cleanId, nextData, returnedMs);
      },
      async delete() {
        await ensureInitialized();
        stats.dbWrites += 1;
        await pool.query(
          `DELETE FROM vidvora_docs WHERE collection = $1 AND doc_id = $2`,
          [collectionName, cleanId]
        );
        updateCachesAfterDelete(collectionName, cleanId);
      },
    };
  }

  function makeQueryRef(collectionName, clauses = [], orderBys = [], limitCount = null) {
    return {
      where(field, op, value) {
        return makeQueryRef(collectionName, [...clauses, { field, op, value }], orderBys, limitCount);
      },
      orderBy(field, direction = "asc") {
        return makeQueryRef(
          collectionName,
          clauses,
          [...orderBys, { field, direction: String(direction || "asc").toLowerCase() === "desc" ? "desc" : "asc" }],
          limitCount
        );
      },
      limit(n) {
        return makeQueryRef(collectionName, clauses, orderBys, Number(n) || null);
      },
      async get() {
        await ensureInitialized();

        // 1. Try full write-through collection cache first (zero Neon egress on hits!)
        if (enableCache && isFullCacheCollection(collectionName)) {
          const cachedCol = await ensureCollectionCached(collectionName);
          if (cachedCol) {
            stats.cacheHits += 1;
            const allRows = [];
            for (const [id, raw] of cachedCol.docs.entries()) {
              allRows.push({ id, raw });
            }
            const filtered = applyQueryTransforms(allRows, clauses, orderBys, limitCount);
            return buildQuerySnapshot(collectionName, filtered);
          }
        }

        // 2. For non-full-cached collections (e.g. accessLinks), check short queryCache
        const qKey = enableCache
          ? JSON.stringify([collectionName, clauses, orderBys, limitCount])
          : "";
        if (enableCache) {
          const qHit = queryCache.get(qKey);
          if (qHit && Date.now() <= qHit.expiresAt) {
            stats.cacheHits += 1;
            return buildQuerySnapshot(collectionName, qHit.rows);
          }
          if (qHit) queryCache.delete(qKey);
        }

        const sqlParams = [collectionName];
        const sqlWhere = ["collection = $1"];
        const remainingClauses = [];

        for (const clause of clauses) {
          if (clause.op === "==" && INDEXED_TEXT_FIELDS.has(clause.field) && typeof clause.value === "string") {
            sqlParams.push(clause.value);
            sqlWhere.push(`data->>'${clause.field}' = $${sqlParams.length}`);
          } else if (clause.op === "==" && clause.field === "isActive" && typeof clause.value === "boolean") {
            sqlParams.push(String(clause.value));
            sqlWhere.push(`data->>'isActive' = $${sqlParams.length}`);
          } else {
            remainingClauses.push(clause);
          }
        }

        const canApplySqlLimit =
          Number.isFinite(limitCount) &&
          limitCount > 0 &&
          remainingClauses.length === 0 &&
          orderBys.length === 0;

        let sql = `SELECT doc_id, data FROM vidvora_docs WHERE ${sqlWhere.join(" AND ")}`;
        if (canApplySqlLimit) {
          sqlParams.push(limitCount);
          sql += ` LIMIT $${sqlParams.length}`;
        }

        stats.dbReads += 1;
        const res = await pool.query(sql, sqlParams);
        let rows = (res.rows || []).map((row) => ({
          id: row.doc_id,
          raw: row.data || {},
        }));

        rows = applyQueryTransforms(
          rows,
          remainingClauses,
          orderBys,
          canApplySqlLimit ? null : limitCount
        );

        if (enableCache) {
          if (queryCache.size >= MAX_QUERY_CACHE_ENTRIES) {
            const oldestKey = queryCache.keys().next().value;
            if (oldestKey !== undefined) queryCache.delete(oldestKey);
          }
          queryCache.set(qKey, {
            collection: collectionName,
            rows,
            expiresAt: Date.now() + queryCacheTtlMs,
          });
          // Also warm individual docCache for returned rows (e.g. accessLinks lookups)
          for (const r of rows) {
            setDocCacheEntry(collectionName, r.id, true, r.raw);
          }
        }

        return buildQuerySnapshot(collectionName, rows);
      },
    };
  }

  function makeCollectionRef(collectionName) {
    const queryBase = makeQueryRef(collectionName, [], [], null);
    return {
      ...queryBase,
      doc(docId) {
        return makeDocRef(collectionName, docId);
      },
      document(docId) {
        return makeDocRef(collectionName, docId);
      },
    };
  }

  return {
    isPostgresAdapter: true,
    pool,
    ensureInitialized,
    getCacheStats() {
      const cachedCollections = {};
      for (const [col, entry] of collectionCache.entries()) {
        cachedCollections[col] = entry.docs.size;
      }
      return {
        ...stats,
        cachedCollections,
        docCacheSize: docCache.size,
        queryCacheSize: queryCache.size,
      };
    },
    getCollectionVersion(collectionName) {
      const v = collectionVersions.get(String(collectionName || "").trim());
      return v ? { structure: v.structure, full: v.full } : { structure: 0, full: 0 };
    },
    async getRawCollectionEntries(collectionName) {
      const cleanName = String(collectionName || "").trim();
      await ensureInitialized();
      if (enableCache && isFullCacheCollection(cleanName)) {
        const cachedCol = await ensureCollectionCached(cleanName);
        if (cachedCol) {
          stats.cacheHits += 1;
          const entries = [];
          for (const [id, raw] of cachedCol.docs.entries()) {
            entries.push({ id, raw: raw || {} });
          }
          return entries;
        }
      }
      stats.dbReads += 1;
      const res = await pool.query(
        `SELECT doc_id, data FROM vidvora_docs WHERE collection = $1`,
        [cleanName]
      );
      return (res.rows || []).map((row) => ({ id: row.doc_id, raw: row.data || {} }));
    },
    trimSoftCaches() {
      docCache.clear();
      queryCache.clear();
    },
    clearCache() {
      collectionCache.clear();
      docCache.clear();
      queryCache.clear();
      for (const col of collectionVersions.keys()) {
        bumpCollectionVersion(col, false);
      }
    },
    async healthCheck() {
      await ensureInitialized();
      await pool.query("SELECT 1");
      return true;
    },
    collection(name) {
      return makeCollectionRef(String(name || "").trim());
    },
  };
}

module.exports = {
  createPgFirestoreDb,
  makeTimestampWrapper,
  serializeValue,
  hydrateValue,
  serializeForRestApi,
  applyMergePatch,
};
