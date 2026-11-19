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

function createPgFirestoreDb({ connectionString = "", poolOverride = null } = {}) {
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

  function makeDocSnapshot(docId, rawData) {
    if (!rawData) {
      return {
        id: docId,
        exists: false,
        data: () => undefined,
      };
    }
    const hydrated = hydrateValue(rawData);
    return {
      id: docId,
      exists: true,
      data: () => ({ ...hydrated }),
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
        const res = await pool.query(
          `SELECT data FROM vidvora_docs WHERE collection = $1 AND doc_id = $2 LIMIT 1`,
          [collectionName, cleanId]
        );
        if (!res.rows || res.rows.length === 0) {
          return makeDocSnapshot(cleanId, null);
        }
        return makeDocSnapshot(cleanId, res.rows[0].data);
      },
      async set(payload, options = {}) {
        await ensureInitialized();
        let nextData;
        if (options && options.merge) {
          const existing = await pool.query(
            `SELECT data FROM vidvora_docs WHERE collection = $1 AND doc_id = $2 LIMIT 1`,
            [collectionName, cleanId]
          );
          const base = existing.rows && existing.rows[0] ? existing.rows[0].data : {};
          nextData = applyMergePatch(base, payload);
        } else {
          nextData = applyMergePatch({}, payload);
        }
        await pool.query(
          `INSERT INTO vidvora_docs (collection, doc_id, data, updated_at)
           VALUES ($1, $2, $3::jsonb, NOW())
           ON CONFLICT (collection, doc_id)
           DO UPDATE SET data = EXCLUDED.data, updated_at = NOW()`,
          [collectionName, cleanId, JSON.stringify(nextData)]
        );
      },
      async update(updates) {
        await ensureInitialized();
        const existing = await pool.query(
          `SELECT data FROM vidvora_docs WHERE collection = $1 AND doc_id = $2 LIMIT 1`,
          [collectionName, cleanId]
        );
        const base = existing.rows && existing.rows[0] ? existing.rows[0].data : {};
        const nextData = applyMergePatch(base, updates);
        await pool.query(
          `INSERT INTO vidvora_docs (collection, doc_id, data, updated_at)
           VALUES ($1, $2, $3::jsonb, NOW())
           ON CONFLICT (collection, doc_id)
           DO UPDATE SET data = EXCLUDED.data, updated_at = NOW()`,
          [collectionName, cleanId, JSON.stringify(nextData)]
        );
      },
      async delete() {
        await ensureInitialized();
        await pool.query(
          `DELETE FROM vidvora_docs WHERE collection = $1 AND doc_id = $2`,
          [collectionName, cleanId]
        );
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

        const res = await pool.query(sql, sqlParams);
        let rows = (res.rows || []).map((row) => ({
          id: row.doc_id,
          raw: row.data || {},
        }));

        if (remainingClauses.length > 0) {
          rows = rows.filter((r) => remainingClauses.every((c) => matchesWhereClause(r.raw, c)));
        }

        if (orderBys.length > 0) {
          rows.sort((a, b) => {
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

        if (!canApplySqlLimit && Number.isFinite(limitCount) && limitCount > 0) {
          rows = rows.slice(0, limitCount);
        }

        const docs = rows.map((r) => makeDocSnapshot(r.id, r.raw));
        return {
          empty: docs.length === 0,
          size: docs.length,
          docs,
          forEach(fn) {
            docs.forEach(fn);
          },
        };
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
