const test = require("node:test");
const assert = require("node:assert/strict");
const {
  createPgFirestoreDb,
  serializeForRestApi,
  applyMergePatch,
} = require("./pgFirestoreAdapter");

function createFakePgPool() {
  const rows = new Map(); // key = `${collection}::${docId}` -> { collection, doc_id, data }
  return {
    rows,
    async query(sql, params = []) {
      const clean = String(sql || "").trim();
      if (/^CREATE TABLE|^CREATE INDEX|^SELECT 1/i.test(clean)) {
        return { rows: [] };
      }
      if (/^SELECT data FROM vidvora_docs WHERE collection = \$1 AND doc_id = \$2/i.test(clean)) {
        const item = rows.get(`${params[0]}::${params[1]}`);
        return { rows: item ? [{ data: JSON.parse(JSON.stringify(item.data)) }] : [] };
      }
      if (/^INSERT INTO vidvora_docs/i.test(clean)) {
        const [col, docId, jsonStr] = params;
        rows.set(`${col}::${docId}`, {
          collection: col,
          doc_id: docId,
          data: JSON.parse(jsonStr),
        });
        return { rows: [] };
      }
      if (/^DELETE FROM vidvora_docs/i.test(clean)) {
        rows.delete(`${params[0]}::${params[1]}`);
        return { rows: [] };
      }
      if (/^SELECT doc_id, data FROM vidvora_docs WHERE/i.test(clean)) {
        const col = params[0];
        let matched = [];
        for (const item of rows.values()) {
          if (item.collection !== col) continue;
          matched.push({ doc_id: item.doc_id, data: JSON.parse(JSON.stringify(item.data)) });
        }
        // Apply simple indexed field filters parsed from SQL
        const eqMatches = [...clean.matchAll(/data->>'([A-Za-z0-9_]+)' = \$(\d+)/g)];
        for (const m of eqMatches) {
          const field = m[1];
          const val = params[Number(m[2]) - 1];
          matched = matched.filter((r) => String(r.data[field]) === String(val));
        }
        const limitMatch = clean.match(/LIMIT \$(\d+)/i);
        if (limitMatch) {
          matched = matched.slice(0, Number(params[Number(limitMatch[1]) - 1]));
        }
        return { rows: matched };
      }
      throw new Error(`Unhandled fake SQL: ${clean}`);
    },
  };
}

test("pgFirestoreAdapter supports doc set/merge/update/delete, timestamps, increments, and indexed where queries", async () => {
  const fakePool = createFakePgPool();
  const db = createPgFirestoreDb({ poolOverride: fakePool });

  const now = new Date("2026-11-18T10:00:00.000Z");
  await db.collection("videos").doc("vid_pg_1").set({
    videoId: "vid_pg_1",
    title: "Neon Video",
    uploadedBy: "mem_1",
    isActive: true,
    totalViews: 5,
    createdAt: now,
  });

  const snap = await db.collection("videos").doc("vid_pg_1").get();
  assert.equal(snap.exists, true);
  assert.equal(snap.id, "vid_pg_1");
  const d = snap.data();
  assert.equal(d.title, "Neon Video");
  assert.equal(d.createdAt.toDate().toISOString(), "2026-11-18T10:00:00.000Z");
  assert.equal(serializeForRestApi(d).createdAt, now.getTime());

  // Atomic dotted increment & merge
  await db.collection("videos").doc("vid_pg_1").update({
    totalViews: { __vidvora_inc__: 2 },
    "hourlyViews.2026-11-18T10": { __vidvora_inc__: 1 },
  });
  const updated = (await db.collection("videos").doc("vid_pg_1").get()).data();
  assert.equal(updated.totalViews, 7);
  assert.equal(updated.hourlyViews["2026-11-18T10"], 1);

  // Indexed where query + limit
  await db.collection("videos").doc("vid_pg_2").set({
    videoId: "vid_pg_2",
    title: "Other Video",
    uploadedBy: "mem_2",
    isActive: true,
    totalViews: 1,
  });
  const qSnap = await db.collection("videos").where("uploadedBy", "==", "mem_1").limit(5).get();
  assert.equal(qSnap.empty, false);
  assert.equal(qSnap.docs.length, 1);
  assert.equal(qSnap.docs[0].id, "vid_pg_1");

  // Subcollection support (supportChats/{id}/messages)
  await db.collection("supportChats").doc("chat_1").collection("messages").doc("msg_1").set({
    text: "Hello from Neon",
    createdAt: now,
  });
  const msgSnap = await db.collection("supportChats").doc("chat_1").collection("messages").get();
  assert.equal(msgSnap.docs.length, 1);
  assert.equal(msgSnap.docs[0].data().text, "Hello from Neon");

  // Delete
  await db.collection("videos").doc("vid_pg_2").delete();
  const deletedSnap = await db.collection("videos").doc("vid_pg_2").get();
  assert.equal(deletedSnap.exists, false);

  // Helper test
  const merged = applyMergePatch({ a: 1, nested: { x: 2 } }, { nested: { y: 3 } });
  assert.deepEqual(merged, { a: 1, nested: { x: 2, y: 3 } });
});

test("pgFirestoreAdapter serves repeated collection & doc polls from RAM cache and keeps write-through fresh", async () => {
  const fakePool = createFakePgPool();
  let selectCount = 0;
  const wrappedPool = {
    async query(sql, params) {
      if (/^SELECT /i.test(String(sql || "").trim()) && !/^SELECT 1/i.test(String(sql || "").trim())) {
        selectCount += 1;
      }
      return fakePool.query(sql, params);
    },
  };

  // Seed directly into underlying table before adapter reads
  await fakePool.query(
    `INSERT INTO vidvora_docs (collection, doc_id, data, updated_at) VALUES ($1, $2, $3::jsonb, NOW())`,
    ["videos", "v1", JSON.stringify({ videoId: "v1", title: "Video 1", isActive: true, totalViews: 10 })]
  );
  await fakePool.query(
    `INSERT INTO vidvora_docs (collection, doc_id, data, updated_at) VALUES ($1, $2, $3::jsonb, NOW())`,
    ["videos", "v2", JSON.stringify({ videoId: "v2", title: "Video 2", isActive: false, totalViews: 3 })]
  );

  const db = createPgFirestoreDb({ poolOverride: wrappedPool });

  // 10 concurrent polls on videos should fire only 1 SQL SELECT (single-flight deduplication)
  const concurrentResults = await Promise.all(
    Array.from({ length: 10 }, () => db.collection("videos").where("isActive", "==", true).get())
  );
  assert.equal(selectCount, 1);
  for (const snap of concurrentResults) {
    assert.equal(snap.docs.length, 1);
    assert.equal(snap.docs[0].id, "v1");
  }

  // Subsequent unfiltered poll, filtered poll, and single-doc read all hit RAM cache (0 extra SQL SELECTs!)
  const allSnap = await db.collection("videos").get();
  assert.equal(allSnap.docs.length, 2);
  const singleSnap = await db.collection("videos").doc("v1").get();
  assert.equal(singleSnap.data().totalViews, 10);
  assert.equal(selectCount, 1);

  // Write-through update skips SELECT and immediately reflects in RAM cache
  await db.collection("videos").doc("v1").update({ totalViews: { __vidvora_inc__: 5 } });
  assert.equal(selectCount, 1);
  const afterUpdate = await db.collection("videos").doc("v1").get();
  assert.equal(afterUpdate.data().totalViews, 15);
  assert.equal(selectCount, 1);

  // Write-through set & delete reflect immediately in collection queries without extra SQL SELECTs
  await db.collection("videos").doc("v3").set({ videoId: "v3", title: "Video 3", isActive: true, totalViews: 0 });
  const activeAfterAdd = await db.collection("videos").where("isActive", "==", true).get();
  assert.equal(activeAfterAdd.docs.length, 2);
  assert.equal(selectCount, 1);

  await db.collection("videos").doc("v1").delete();
  const activeAfterDel = await db.collection("videos").where("isActive", "==", true).get();
  assert.equal(activeAfterDel.docs.length, 1);
  assert.equal(activeAfterDel.docs[0].id, "v3");
  assert.equal(selectCount, 1);

  const cacheStats = db.getCacheStats();
  assert.ok(cacheStats.cacheHits >= 10);
  assert.equal(cacheStats.dbReads, 1);
});

