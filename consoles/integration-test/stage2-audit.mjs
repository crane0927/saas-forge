import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

export function readAcceptanceRows(project, database, query) {
  assert.match(project, /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
  assert.ok(['iam_db', 'tenant_access_db', 'audit_db'].includes(database));
  try {
    return JSON.parse(
      execFileSync(
        'docker',
        [
          'exec',
          '-i',
          `${project}-postgres-1`,
          'psql',
          '-X',
          '-qAt',
          '-U',
          'saas.forge_console_e2e',
          '-d',
          database,
          '-v',
          'ON_ERROR_STOP=1',
        ],
        {
          input: `BEGIN READ ONLY; SET LOCAL statement_timeout = '5s'; SELECT coalesce(json_agg(result), '[]'::json) FROM (${query}) result; COMMIT;`,
          encoding: 'utf8',
          timeout: 10_000,
          stdio: ['pipe', 'pipe', 'pipe'],
        },
      ).trim(),
    );
  } catch {
    throw new Error('本轮只读权威观察失败');
  }
}

// 从本轮操作时间窗内的生产 Outbox 取事件 ID/Trace，再逐个匹配真实消费者记录。
// 不读取事件原文或凭据，也不将历史任意记录存在作为成功条件。
export async function verifyMainChainAudit(project, operations) {
  const evidence = [];
  for (const operation of operations) {
    for (const id of [operation.actor, operation.resource]) assert.match(id, /^[0-9a-f-]{36}$/);
    const since = new Date(operation.startedAt).toISOString();
    const until = new Date(operation.finishedAt).toISOString();
    const isTenant = operation.action === 'TENANT_CREATED';
    assert.ok(
      ['TENANT_CREATED', 'SESSION_STARTED', 'TENANT_CONTEXT_SWITCHED'].includes(operation.action),
    );
    const type = isTenant
      ? 'com.saas.forge.tenant.created.v1'
      : operation.action === 'SESSION_STARTED'
        ? 'com.saas.forge.iam.session.started.v1'
        : 'com.saas.forge.iam.tenant-context-switched.v1';
    const table = isTenant ? 'tenant_access_outbox_events' : 'iam_outbox_events';
    const sourceRows = () =>
      readAcceptanceRows(
        project,
        isTenant ? 'tenant_access_db' : 'iam_db',
        `SELECT event_id, trace_id, published_at, event_snapshot->>'subject' AS resource_id
       FROM ${table} WHERE event_snapshot->>'type' = '${type}'
       AND occurred_at >= '${since}' AND occurred_at <= '${until}'
       AND event_snapshot->'data'->>'${isTenant ? 'tenantId' : 'identityId'}' = '${isTenant ? operation.resource : operation.actor}'
       ${isTenant ? '' : `AND event_snapshot->>'subject' = '${operation.resource}'`}`,
      );
    const deadline = Date.now() + 30_000;
    let matched;
    while (Date.now() < deadline) {
      const sources = sourceRows();
      assert.ok(sources.length <= 1, '本轮操作必须唯一关联一个生产事件');
      if (sources.length === 1 && sources[0].published_at) {
        const source = sources[0];
        assert.match(source.event_id, /^[0-9a-f-]{36}$/);
        assert.match(source.trace_id, /^(?!0{32}$)[0-9a-f]{32}$/);
        const rows = readAcceptanceRows(
          project,
          'audit_db',
          `SELECT a.source_event_id, a.trace_id, a.actor_identity_id, a.resource_id, a.tenant_id, a.metadata
           FROM audit_records a JOIN audit_consumed_events c ON c.event_id = a.source_event_id AND c.source = a.source
           WHERE a.source_event_id = '${source.event_id}' AND a.action = '${operation.action}'
           AND a.source_type = '${type}' AND a.result = 'SUCCESS'`,
        );
        if (rows.length) {
          assert.equal(rows.length, 1);
          const row = rows[0];
          assert.equal(row.trace_id, source.trace_id);
          assert.equal(row.actor_identity_id, operation.actor);
          assert.equal(row.resource_id, operation.resource);
          if (operation.tenantId) assert.equal(row.tenant_id, operation.tenantId);
          if (operation.membershipId)
            assert.equal(row.metadata.targetMembershipId, operation.membershipId);
          matched = {
            action: operation.action,
            eventId: source.event_id,
            traceId: source.trace_id,
            actorId: row.actor_identity_id,
            resourceId: row.resource_id,
            tenantId: row.tenant_id,
          };
          break;
        }
      }
      await setTimeout(300);
    }
    assert.ok(matched, '本轮生产事件未在有界等待内形成匹配的 Audit Record');
    evidence.push(matched);
  }
  return evidence;
}
