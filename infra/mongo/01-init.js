// Mongo init for the orchestrator's investigation trace store (NoSQL side).
// Creates the collection + an index for recent-first listing. Trace documents
// are written by agent-orchestrator-service when ARGUS_TRACE_STORE=mongo.

db = db.getSiblingDB('argus');
db.createCollection('investigations');
db.investigations.createIndex({ createdAt: -1 });
db.investigations.createIndex({ subjectAddress: 1 });
print('argus: investigations collection initialised');
