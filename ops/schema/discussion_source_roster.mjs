// Offline release inventory. No DB/session access, deploy command or device writes.
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const scope = ['tenant_id', 'client_id', 'owner_jiacn', 'task_id'];
const fence = ['tenant_id', 'client_id', 'target_agent_id', 'installation_id', 'host_id', 'runtime_instance_id', 'session_generation'];
const sha = /^[a-f0-9]{64}$/;
const check = (ok, reason) => { if (!ok) throw new Error(reason); };
const tuple = (row, fields) => fields.map(k => row[k]);
const text = v => typeof v === 'string' && v.trim().length > 0;
function serial(value) { // JSON exports may use strings to preserve BIGINT precision.
  if (Number.isSafeInteger(value)) value = String(value);
  check(typeof value === 'string' && /^[1-9][0-9]*$/.test(value) && BigInt(value) <= 9223372036854775807n, 'INVALID_COUNTER');
  return value;
}
export function collect(scopes, rows, subjectKey) {
  check(Array.isArray(scopes) && scopes.length && Array.isArray(rows), 'NO_FIXED_INVENTORY');
  const tasks = new Map(), devices = new Map(), captures = new Set(), facts = [];
  for (const s of scopes) {
    const id = JSON.stringify(tuple(s,scope));
    check(tuple(s,scope).every(text) && !tasks.has(id), 'INVALID_TASK_SCOPE');
    tasks.set(id,{ revisions:new Set() });
  }
  for (const row of rows) {
    const id = JSON.stringify(tuple(row,scope)), task = tasks.get(id);
    check(task && row.source_state === 'MAPPED_SENT_ORIGIN', 'UNKNOWN_TASK_OR_SOURCE');
    const count = serial(row.execution_count), revision = serial(row.member_revision), end = serial(row.set_revision);
    const ids = ['capture_id','admission_sha256','execution_set_sha256','payload_sha256'];
    check(ids.every(k=>typeof row[k]==='string' && sha.test(row[k])) && /^sha256:[a-f0-9]{64}$/.test(row.context_hash), 'INVALID_EVIDENCE');
    check([row.execution_id,row.run_id,row.event_id,...tuple(row,fence.slice(0,-1))].every(text), 'MISSING_ORIGIN');
    const set = JSON.stringify([count,end,row.execution_set_sha256]);
    check((!task.set || task.set===set) && !task.revisions.has(revision) && BigInt(revision)<=BigInt(end) && !captures.has(row.capture_id), 'INCONSISTENT_MEMBERS');
    task.set=set; task.count=count; task.end=end; task.revisions.add(revision); captures.add(row.capture_id);
    const origin={...Object.fromEntries(fence.map(k=>[k,row[k]])),session_generation:serial(row.session_generation)};
    // A subject on one original installation cannot attest two live generations at cutover.
    const deviceKey=origin.installation_id;
    const device=devices.get(deviceKey) || {origin, manifest:{subjectKey:subjectKey({tenantId:row.tenant_id,clientId:row.client_id,canonicalAgentId:row.target_agent_id}),captureIds:[]}};
    check(JSON.stringify(device.origin)===JSON.stringify(origin),'CONFLICTING_ORIGINAL_SESSION');
    device.manifest.captureIds.push(row.capture_id);devices.set(deviceKey,device);
    facts.push(JSON.stringify([id,set,revision,row.execution_id,row.run_id,row.capture_id,row.admission_sha256,row.event_id,row.context_hash,row.payload_sha256,tuple(origin,fence)]));
  }
  for (const t of tasks.values()) check(String(t.revisions.size)===t.count && t.revisions.has(t.end),'MISSING_MEMBERS');
  for (const d of devices.values()) d.manifest.captureIds.sort();
  return {schema:'discussion-inventory-v1',snapshotSha256:createHash('sha256').update(JSON.stringify(facts.sort())).digest('hex'),
    tasks:tasks.size,sources:facts.length,devices:[...devices.keys()].sort().map(k=>devices.get(k))};
}
if (process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [runtime,scopes,before,after,pinned,...extra]=process.argv.slice(2);
    check(runtime && scopes && before && !extra.length && (!after || (pinned && sha.test(pinned))),'USAGE_RUNTIME_SCOPES_BEFORE_OPTIONAL_AFTER_AND_PIN');
    const {runtimeSubjectKey}=await import(pathToFileURL(resolve(runtime,'conf/cyf-agent-runtime-v1/lib/manifest.mjs')));
    const load=async p=>JSON.parse(await readFile(p,'utf8'));
    const scopeList=await load(scopes), prior=collect(scopeList,await load(before),runtimeSubjectKey);
    if (after) {
      const current=collect(scopeList,await load(after),runtimeSubjectKey);
      check(pinned===prior.snapshotSha256 && pinned===current.snapshotSha256,'INVENTORY_CHANGED');
      console.log(JSON.stringify({inventoryUnchanged:true,tasks:prior.tasks,sources:prior.sources,snapshotSha256:prior.snapshotSha256}));
    } else console.log(JSON.stringify(prior));
  } catch (e) { console.error(/^[A-Z_]+$/.test(e.message)?e.message:'INVENTORY_UNREADABLE');process.exitCode=2; }
}
