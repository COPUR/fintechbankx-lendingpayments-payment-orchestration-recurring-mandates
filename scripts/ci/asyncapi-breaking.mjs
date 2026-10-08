#!/usr/bin/env node
// AsyncAPI breaking-change gate.
// Status: Proposed (API Governance Guild review required).
//
// `asyncapi diff` (@asyncapi/cli 2.13.0) does not support AsyncAPI 3.0 documents, so the rule set is
// implemented here. Each top-level asyncapi/*.yaml|yml is compared with the same file at the merge base
// of BASE_REF (default origin/main) and HEAD. Specs that do not exist at the base are skipped (new files).
//
// Breaking findings (each one fails the gate):
//   removed-spec       a spec present at the base is gone
//   removed-channel    a channel address present at the base is gone (dead-letter topics excepted:
//                      they belong to the consuming service, ADR-019)
//   removed-message    a message key of a channel is gone
//   removed-property   a payload property path (envelope or data) is gone
//   newly-required     a payload property is required now but was optional or absent at the base
//   changed-type       the declared JSON type(s) of a payload property changed
//   removed-enum-value an enum value present at the base is gone
//   no-longer-required a payload property was required at the base and is optional now (consumers rely on it)
//   changed-const      a const was added, removed or changed (eventType, producer, fixed values)
//   changed-constraint a validation keyword (pattern, format, min/max length, range, items) was added,
//                      removed or changed, an enum was added to a property that had none, or
//                      additionalProperties was closed or changed (opening it is compatible); replayed records
//                      may no longer validate, or consumers get values they reject. Applies at every level,
//                      array items ('[]') included
//   changed-binding    the channel's Kafka binding changed its topic, partitions or cleanup.policy, or
//                      lowered retention.ms (ordering per key, compaction and replay depend on them)
//   changed-message-key the message's Kafka key schema changed (descriptions excepted)
//
// Accepted exceptions: asyncapi/<spec-name>.accepted-breaking.txt (same name as the spec without the
// extension), one finding key per line as printed below; '#' starts a comment. Use it only with a
// documented major-version and dual-publish plan.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { loadYaml, createResolver, flattenPayload, listChannels } from './lib/asyncapi-model.mjs';

// Drops descriptive keywords so only the shape of a schema is compared.
const shape = (node) => {
  if (Array.isArray(node)) return node.map(shape);
  if (node === null || typeof node !== 'object') return node;
  return Object.fromEntries(
    Object.keys(node)
      .filter((k) => !['description', 'title', 'examples', 'example'].includes(k))
      .sort()
      .map((k) => [k, shape(node[k])]),
  );
};

/** The Kafka binding fields a consumer depends on. */
function kafkaBinding(channel) {
  const k = channel?.bindings?.kafka ?? {};
  const cfg = k.topicConfiguration ?? {};
  const policy = cfg['cleanup.policy'];
  return {
    topic: k.topic,
    partitions: k.partitions,
    'cleanup.policy': policy === undefined ? undefined : [].concat(policy).sort().join(','),
    'retention.ms': cfg['retention.ms'],
  };
}

/**
 * Builds Map<address, { binding, messages: Map<messageKey, { props: Map<path, info>, key }> }> for one spec.
 */
export function describeSpec(file, readFile) {
  const resolver = createResolver(readFile);
  const doc = resolver.load(file);
  const channels = new Map();
  for (const ch of listChannels(doc, file, resolver)) {
    const address = ch.address ?? `#${ch.key}`;
    const messages = new Map();
    for (const m of ch.messages) {
      const payload = m.message?.payload;
      const keySchema = m.message?.bindings?.kafka?.key;
      messages.set(m.key, {
        props: payload ? flattenPayload(payload, m.ctx, resolver) : new Map(),
        key: keySchema === undefined ? undefined : JSON.stringify(shape(resolver.deref(keySchema, m.ctx).node)),
      });
    }
    channels.set(address, { binding: kafkaBinding(ch.channel), messages });
  }
  return channels;
}

export function compareSpecs(file, readBase, readHead) {
  const findings = [];
  const add = (rule, subject, detail) => findings.push({ rule, key: `${rule} ${subject}`, detail });
  const base = describeSpec(file, readBase);
  const head = describeSpec(file, readHead);
  for (const [address, baseChannel] of base) {
    if (!head.has(address)) {
      // A dead-letter topic is internal to the consumer that owns it (ADR-019), not a contract others read.
      if (/\.dlq\.v\d+$/.test(address)) continue;
      add('removed-channel', address, `channel ${address} was removed`);
      continue;
    }
    const headChannel = head.get(address);
    for (const [field, b] of Object.entries(baseChannel.binding)) {
      const h = headChannel.binding[field];
      if (b === h) continue;
      // Longer retention only keeps records longer; shorter retention can drop what consumers replay.
      if (field === 'retention.ms' && (b === undefined || (typeof h === 'number' && typeof b === 'number' && h >= b))) continue;
      add('changed-binding', `${address} ${field}`, `Kafka binding ${field} changed from ${b} to ${h}`);
    }
    const headMessages = headChannel.messages;
    for (const [mKey, baseMessage] of baseChannel.messages) {
      if (!headMessages.has(mKey)) {
        add('removed-message', `${address} ${mKey}`, `message ${mKey} was removed from ${address}`);
        continue;
      }
      const baseProps = baseMessage.props;
      const headMessage = headMessages.get(mKey);
      const headProps = headMessage.props;
      if (baseMessage.key !== headMessage.key) {
        add('changed-message-key', `${address} ${mKey}`, `Kafka message key changed from ${baseMessage.key} to ${headMessage.key}`);
      }
      for (const [p, b] of baseProps) {
        const h = headProps.get(p);
        const subject = `${address} ${mKey} ${p}`;
        if (!h) {
          add('removed-property', subject, `property ${p} was removed`);
          continue;
        }
        if (h.required && !b.required) add('newly-required', subject, `property ${p} is now required`);
        if (b.required && !h.required) add('no-longer-required', subject, `property ${p} is no longer required`);
        if (b.const !== h.const) add('changed-const', subject, `const changed from ${b.const} to ${h.const}`);
        for (const k of new Set([...Object.keys(b.constraints ?? {}), ...Object.keys(h.constraints ?? {})])) {
          const bv = b.constraints?.[k];
          const hv = h.constraints?.[k];
          if (bv !== hv) add('changed-constraint', `${subject} ${k}`, `${k} changed from ${bv} to ${hv}`);
        }
        if (b.types && h.types && b.types !== h.types) add('changed-type', subject, `type changed from ${b.types} to ${h.types}`);
        // Closing a value set or an object rejects records that validated before (replay, older producers).
        if (!b.enum && h.enum) add('changed-constraint', `${subject} enum`, `enum ${h.enum.join(',')} was added to ${p}`);
        const opened = h.additionalProperties === undefined || h.additionalProperties === 'true';
        if (b.additionalProperties !== h.additionalProperties && !opened) {
          add('changed-constraint', `${subject} additionalProperties`, `additionalProperties changed from ${b.additionalProperties} to ${h.additionalProperties}`);
        }
        if (b.enum && h.enum) {
          for (const v of b.enum.filter((x) => !h.enum.includes(x))) {
            add('removed-enum-value', `${subject} ${v}`, `enum value ${v} was removed from ${p}`);
          }
        }
      }
      for (const [p, h] of headProps) {
        if (!baseProps.has(p) && h.required && p !== '$') {
          // A new property is only "newly required" if its parent existed at the base;
          // a required child of a new optional object is not a consumer-visible break.
          const parent = p.replace(/(\.[^.[\]]+|\[\])$/, '');
          if (baseProps.has(parent)) add('newly-required', `${address} ${mKey} ${p}`, `new property ${p} is required`);
        }
      }
    }
  }
  return findings;
}

export function readAccepted(text) {
  return new Set((text ?? '').split('\n').map((l) => l.replace(/#.*$/, '').trim()).filter(Boolean));
}

const git = (args) => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const gitShow = (rev, file) => {
  try {
    return git(['show', `${rev}:${file}`]);
  } catch {
    return null;
  }
};

function main() {
  const baseRef = process.env.BASE_REF || 'origin/main';
  try {
    git(['rev-parse', '--verify', '--quiet', `${baseRef}^{commit}`]);
  } catch {
    console.error(`BASE_REF ${baseRef} is not available. Fetch full history (actions/checkout fetch-depth: 0) or set BASE_REF.`);
    process.exit(2);
  }
  let base = baseRef;
  try {
    base = git(['merge-base', baseRef, 'HEAD']).trim();
  } catch {
    // unrelated histories: compare with the ref itself
  }
  const isSpec = (f) => /^asyncapi\/[^/]+\.ya?ml$/.test(f);
  const baseSpecs = git(['ls-tree', '--name-only', `${base}`, 'asyncapi/']).split('\n').filter(isSpec);
  const headSpecs = fs.existsSync('asyncapi')
    ? fs.readdirSync('asyncapi').map((n) => `asyncapi/${n}`).filter((f) => isSpec(f) && fs.statSync(f).isFile())
    : [];
  console.log(`asyncapi breaking check: base ${baseRef} (merge base ${base.slice(0, 12)})`);
  const readHead = (f) => (fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null);
  const readBase = (f) => gitShow(base, f);
  let failed = 0;
  for (const f of headSpecs.filter((x) => !baseSpecs.includes(x)).sort()) {
    console.log(`skip ${f}: new file (not at base)`);
  }
  for (const f of baseSpecs.sort()) {
    const acceptedFile = f.replace(/\.ya?ml$/, '.accepted-breaking.txt');
    const accepted = readAccepted(readHead(acceptedFile));
    let findings;
    if (!headSpecs.includes(f)) {
      findings = [{ rule: 'removed-spec', key: `removed-spec ${f}`, detail: `spec ${f} was removed` }];
    } else {
      try {
        findings = compareSpecs(f, readBase, readHead);
      } catch (e) {
        console.error(`ERR ${f}: ${e.message}`);
        failed++;
        continue;
      }
    }
    const open = findings.filter((x) => !accepted.has(x.key));
    findings.filter((x) => accepted.has(x.key)).forEach((x) => console.log(`accepted ${f}: ${x.key}`));
    if (open.length === 0) {
      console.log(`ok   ${f}: no breaking changes`);
    } else {
      open.forEach((x) => console.error(`BREAKING ${f}: ${x.key}  (${x.detail})`));
      failed += open.length;
    }
  }
  if (failed > 0) {
    console.error(`asyncapi breaking check failed: ${failed} finding(s). Publish a new major version on a new topic (.v2) with dual-publish, or list accepted findings in asyncapi/<spec-name>.accepted-breaking.txt.`);
    process.exit(1);
  }
  console.log('asyncapi breaking check passed');
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main();
}
