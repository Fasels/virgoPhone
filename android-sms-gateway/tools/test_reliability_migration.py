"""Exercise the shipped Room migration and SQL against an isolated in-memory v23 fixture."""
import json
import re
import sqlite3
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--schema24", type=Path, help="Room-generated v24 JSON from the actual APK build")
args = parser.parse_args()
JAVA = ROOT / "app/src/main/java/me/capcom/smsgateway"
schema = json.loads((ROOT / "app/schemas/me.capcom.smsgateway.data.AppDatabase/23.json").read_text())
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for entity in schema["database"]["entities"]:
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for index in entity["indices"]:
        db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

fixtures = [("legacy-pending", "Pending", "Cloud"), ("sent", "Sent", "Cloud"),
            ("delivered", "Delivered", "Gateway"), ("failed", "Failed", "Cloud"),
            ("local-pending", "Pending", "Local")]
for message_id, state, source in fixtures:
    db.execute("INSERT INTO message(id,source,content,state,createdAt,processedAt,simNumber) VALUES(?,?,?,?,?,?,?)",
               (message_id, source, '{"text":"synthetic migration fixture"}', state, 100, 200 if state != "Pending" else None, 2))
    db.execute("INSERT INTO messagerecipient VALUES(?,?,?,?)", (message_id, "+15555550100", state, None))
    db.execute("INSERT INTO messagestate VALUES(?,?,?)", (message_id, state, 100))
    db.execute("INSERT INTO recipientstate VALUES(?,?,?,?)", (message_id, "+15555550100", state, 100))
old_tables = [e["tableName"] for e in schema["database"]["entities"]]
before = {name: db.execute(f'SELECT * FROM "{name}" ORDER BY rowid').fetchall() for name in old_tables}

source = (JAVA / "data/Migrations.kt").read_text()
migration = source[source.index("val MIGRATION_23_24"):]
statements = re.findall(r'database\.execSQL\(\s*(?:"""(.*?)"""\.trimIndent\(\)|"([^"\n]*)")\s*\)', migration, re.S)
assert len(statements) == 6, f"Unexpected migration statement count: {len(statements)}"
with db:
    for multiline, single in statements:
        db.execute(multiline or single)
for name, rows in before.items():
    assert db.execute(f'SELECT * FROM "{name}" ORDER BY rowid').fetchall() == rows, name
assert db.execute("SELECT messageId,phase,error FROM gateway_delivery").fetchall() == [
    ("legacy-pending", "UNKNOWN", "legacy-unverified")]
assert {r[0] for r in db.execute("SELECT messageId FROM message_status_outbox")} == {"sent", "delivered", "failed"}
assert db.execute("PRAGMA foreign_key_check").fetchall() == []

if args.schema24 is not None:
    expected = json.loads(args.schema24.read_text())["database"]
    assert expected["version"] == 24
    count_columns = count_indices = 0
    for entity in expected["entities"]:
        table = entity["tableName"]
        primary = entity["primaryKey"]["columnNames"]
        columns = {r[1]: (r[2], bool(r[3]), r[4], r[5]) for r in db.execute(f'PRAGMA table_info("{table}")')}
        expected_columns = {
            field["columnName"]: (field["affinity"], field["notNull"], field.get("defaultValue"),
                                  primary.index(field["columnName"]) + 1 if field["columnName"] in primary else 0)
            for field in entity["fields"]
        }
        assert columns == expected_columns, (table, "columns", columns, expected_columns)
        count_columns += len(columns)
        indices = {}
        for row in db.execute(f'PRAGMA index_list("{table}")'):
            if row[3] != "c":  # PRIMARY KEY autoindices are already checked above.
                continue
            fields = [(x[2], "DESC" if x[3] else "ASC") for x in db.execute(f'PRAGMA index_xinfo("{row[1]}")') if x[5] and x[1] >= 0]
            indices[row[1]] = (bool(row[2]), fields)
        expected_indices = {
            index["name"]: (index["unique"], list(zip(index["columnNames"], index["orders"] or ["ASC"] * len(index["columnNames"]))))
            for index in entity["indices"]
        }
        assert indices == expected_indices, (table, "indices", indices, expected_indices)
        count_indices += len(indices)
        foreign = {}
        for row in db.execute(f'PRAGMA foreign_key_list("{table}")'):
            foreign.setdefault(row[0], []).append(row)
        actual_foreign = sorted((rows[0][2], rows[0][5], rows[0][6],
                                 tuple(r[3] for r in sorted(rows, key=lambda r: r[1])),
                                 tuple(r[4] for r in sorted(rows, key=lambda r: r[1]))) for rows in foreign.values())
        expected_foreign = sorted((fk["table"], fk["onUpdate"], fk["onDelete"], tuple(fk["columns"]), tuple(fk["referencedColumns"]))
                                  for fk in entity["foreignKeys"])
        assert actual_foreign == expected_foreign, (table, "foreignKeys", actual_foreign, expected_foreign)
    print(f"PASS: migrated SQLite matches actual Room v24 schema: {len(expected['entities'])} tables, {count_columns} columns, {count_indices} indices; nullability/defaults/PK/index order/FKs checked")

dao_source = (JAVA / "data/dao/MessagesDao.kt").read_text()
def query(function):
    pattern = r'@Query\("([^"\n]*)"\)\s*(?:suspend\s+)?fun\s+' + re.escape(function) + r'\('
    match = re.search(pattern, dao_source)
    assert match, function
    return match.group(1)

# Old cloud Pending is preserved but cannot be selected for sending.
pending = db.execute(query("getPendingFifo"), {"now": "2099-01-01T00:00:00.000Z"}).fetchone()
assert pending[0] == "local-pending"

# A receipt is not executable before ACK and its single CAS fence cannot be claimed twice.
db.execute("INSERT INTO message(id,source,content,state,createdAt) VALUES('new','Cloud','{}','Pending',300)")
db.execute("INSERT INTO gateway_delivery(messageId,deviceId,serverUrl,payloadJson,payloadHash,claimToken,phase,updatedAt) VALUES('new','device','server','{}','hash','claim','ACKED',300)")
args = {"id": "new", "expected": "ENQUEUED", "phase": "SUBMITTING", "now": 400}
assert db.execute(query("_changeDeliveryPhase"), args).rowcount == 0
db.execute(query("_changeDeliveryPhase"), {**args, "expected": "ACKED", "phase": "ENQUEUED"})
assert db.execute(query("_changeDeliveryPhase"), args).rowcount == 1
assert db.execute(query("_changeDeliveryPhase"), args).rowcount == 0
db.execute(query("recoverUncertainSubmissions"), {"now": 500})
assert db.execute("SELECT phase FROM gateway_delivery WHERE messageId='new'").fetchone()[0] == "UNKNOWN"
assert db.execute(query("_changeDeliveryPhase"), args).rowcount == 0

# SQL, not just the JVM policy, rejects duplicate/stale callbacks.
assert db.execute(query("_updateRecipientState"), {"id": "delivered", "phoneNumber": "+15555550100", "state": "Processed", "error": None}).rowcount == 0
assert db.execute(query("_updateRecipientState"), {"id": "failed", "phoneNumber": "+15555550100", "state": "Sent", "error": None}).rowcount == 0
assert db.execute(query("_updateRecipientState"), {"id": "sent", "phoneNumber": "+15555550100", "state": "Sent", "error": None}).rowcount == 0

# A delayed old acknowledgement/block cannot remove or hide a newer outbox revision.
db.execute(query("_bumpStatus"), {"id": "sent", "now": 600})
assert db.execute(query("complete"), {"messageId": "sent", "revision": 1}).rowcount == 0
revision_block_query = re.search(r'@Query\("([^"\n]*revision = :revision[^"\n]*)"\)\s*fun markBlocked', dao_source).group(1)
assert db.execute(revision_block_query, {"messageId": "sent", "revision": 1, "error": "old error"}).rowcount == 0
assert db.execute(query("complete"), {"messageId": "sent", "revision": 2}).rowcount == 1
db.execute(query("_bumpStatus"), {"id": "sent", "now": 700})
assert db.execute("SELECT revision,reportedRevision FROM message_status_outbox WHERE messageId='sent'").fetchone() == (3, 2)
assert db.execute(query("complete"), {"messageId": "sent", "revision": 2}).rowcount == 0

# A large legacy migration backlog, including misleading future timestamps, cannot
# block a newly produced status with a routing ledger. Revision fencing still holds
# when another callback arrives while that high-priority row is being uploaded.
for index in range(1000):
    message_id = f"legacy-backlog-{index}"
    db.execute("INSERT INTO message_status_outbox(messageId,revision,updatedAt) VALUES(?,1,?)", (message_id, 100000 + index))
db.execute("INSERT INTO gateway_delivery(messageId,deviceId,serverUrl,payloadJson,payloadHash,claimToken,phase,updatedAt) VALUES('live-priority','device','server','{}','hash','claim','SUBMITTED',1)")
db.execute("INSERT INTO message_status_outbox(messageId,revision,updatedAt,reportedRevision) VALUES('live-priority',4,1,3)")
assert db.execute(query("nextPendingEntries"), {"limit": 1}).fetchone()[0] == "live-priority"
db.execute(query("_bumpStatus"), {"id": "live-priority", "now": 900})
assert db.execute(query("complete"), {"messageId": "live-priority", "revision": 4}).rowcount == 0
live = db.execute(query("nextPendingEntries"), {"limit": 1}).fetchone()
assert live[0] == "live-priority" and live[1] == 5
assert db.execute(query("complete"), {"messageId": "live-priority", "revision": 5}).rowcount == 1
assert db.execute(query("nextPendingEntries"), {"limit": 1}).fetchone()[0] != "live-priority"
print("PASS: 1000 legacy outbox rows cannot block a live ledger status; a delayed old revision cannot acknowledge its newer callback")

# Unreported/blocked status and UNKNOWN delivery rows survive normal log retention.
db.execute(query("truncateLog"), {"until": 1000})
assert db.execute("SELECT id FROM message WHERE id='legacy-pending'").fetchone()
assert db.execute("SELECT id FROM message WHERE id='sent'").fetchone()
print("PASS: v23 -> v24 preserves all old tables; ACK/submission fences, crash quarantine, monotonic SQL, durable outbox revisions and retention verified")
