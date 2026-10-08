// Shared helpers for the AsyncAPI catalog checks.
// Status: Proposed (API Governance Guild review required).
//
// - loadYaml: parse one YAML document, failing loudly on syntax errors.
// - createResolver: resolve local (#/...) and relative file (./x.yaml#/...) $refs
//   through a reader function, so the same code works on the working tree and on
//   a git revision (git show <rev>:<path>).
// - flattenPayload: turn a message payload (allOf, $ref, properties, items) into a
//   map of property paths -> { types, required, enum, const, constraints, additionalProperties } used by the breaking check.
import path from 'node:path';
import { parse } from 'yaml';

export const TOPIC_RE = /^evt\.[a-z]+\.[a-z0-9-]+\.[a-z0-9-]+\.v[0-9]+$/;
export const NAMESPACE_RE = /^evt\.[a-z]+\.[a-z0-9-]+$/;
export const ENVELOPE_REF_RE = /(^|\/)common\/event-envelope\.yaml#\/EventEnvelope$/;

export function loadYaml(text, name) {
  try {
    return parse(text);
  } catch (err) {
    throw new Error(`${name}: YAML parse error: ${err.message}`);
  }
}

export function resolvePointer(doc, pointer, name = 'document') {
  const raw = pointer.startsWith('#') ? pointer.slice(1) : pointer;
  if (raw === '' || raw === '/') return doc;
  let node = doc;
  for (const part of raw.replace(/^\//, '').split('/')) {
    const key = decodeURIComponent(part).replace(/~1/g, '/').replace(/~0/g, '~');
    if (node === null || typeof node !== 'object' || !(key in node)) {
      throw new Error(`${name}: cannot resolve $ref pointer ${pointer}`);
    }
    node = node[key];
  }
  return node;
}

/**
 * Returns true when the payload (directly or through allOf / local $refs) uses
 * the shared envelope common/event-envelope.yaml#/EventEnvelope.
 */
export function usesEnvelope(node, doc, depth = 0) {
  if (depth > 20 || node === null || typeof node !== 'object') return false;
  if (typeof node.$ref === 'string') {
    if (ENVELOPE_REF_RE.test(node.$ref)) return true;
    if (node.$ref.startsWith('#/')) {
      return usesEnvelope(resolvePointer(doc, node.$ref), doc, depth + 1);
    }
    return false;
  }
  if (Array.isArray(node.allOf)) {
    return node.allOf.some((part) => usesEnvelope(part, doc, depth + 1));
  }
  return false;
}

/**
 * readFile(relPath) returns file text or null. relPath is relative to the repo root.
 * The resolver caches parsed documents per path.
 */
export function createResolver(readFile) {
  const cache = new Map();
  const load = (file) => {
    if (!cache.has(file)) {
      const text = readFile(file);
      if (text === null || text === undefined) throw new Error(`cannot read ${file}`);
      cache.set(file, loadYaml(text, file));
    }
    return cache.get(file);
  };
  // ctx = { file } ; returns { node, ctx } with all leading $refs followed.
  const deref = (node, ctx, depth = 0) => {
    if (depth > 30) throw new Error(`${ctx.file}: $ref chain too deep`);
    if (node === null || typeof node !== 'object' || typeof node.$ref !== 'string') {
      return { node, ctx };
    }
    const ref = node.$ref;
    const hash = ref.indexOf('#');
    const filePart = hash === -1 ? ref : ref.slice(0, hash);
    const pointer = hash === -1 ? '#' : ref.slice(hash);
    const target = filePart === '' ? ctx.file : path.posix.normalize(path.posix.join(path.posix.dirname(ctx.file), filePart));
    const doc = load(target);
    return deref(resolvePointer(doc, pointer, target), { file: target }, depth + 1);
  };
  return { load, deref };
}

/** Validation keywords whose change can reject records that validated before (or the reverse). */
export const CONSTRAINT_KEYWORDS = [
  'pattern', 'format', 'minLength', 'maxLength', 'minimum', 'maximum', 'exclusiveMinimum', 'exclusiveMaximum',
  'multipleOf', 'minItems', 'maxItems', 'uniqueItems',
];

const typesOf = (schema) => {
  if (schema.type === undefined) return [];
  return Array.isArray(schema.type) ? schema.type : [schema.type];
};

/**
 * Builds a merged view of a list of { node, ctx } schemas (allOf semantics):
 * union of declared types, merged properties, union of required, items list.
 */
function mergedView(entries, resolver, depth) {
  const view = {
    types: new Set(), properties: new Map(), required: new Set(), items: [], enum: null, additionalProperties: undefined,
    const: undefined, constraints: {},
  };
  const visit = (entry, d) => {
    if (d > 30) throw new Error('allOf nesting too deep');
    const { node, ctx } = resolver.deref(entry.node, entry.ctx);
    if (node === null || typeof node !== 'object') return;
    for (const t of typesOf(node)) view.types.add(t);
    if (Array.isArray(node.enum)) view.enum = [...(view.enum ?? []), ...node.enum];
    if (node.const !== undefined) view.const = JSON.stringify(node.const);
    for (const k of CONSTRAINT_KEYWORDS) if (node[k] !== undefined) view.constraints[k] = JSON.stringify(node[k]);
    if (node.additionalProperties !== undefined) view.additionalProperties = node.additionalProperties;
    if (Array.isArray(node.required)) node.required.forEach((r) => view.required.add(r));
    if (node.properties && typeof node.properties === 'object') {
      for (const [name, sub] of Object.entries(node.properties)) {
        if (!view.properties.has(name)) view.properties.set(name, []);
        view.properties.get(name).push({ node: sub, ctx });
      }
    }
    if (node.items) view.items.push({ node: node.items, ctx });
    if (Array.isArray(node.allOf)) node.allOf.forEach((part) => visit({ node: part, ctx }, d + 1));
  };
  entries.forEach((e) => visit(e, depth));
  return view;
}

/**
 * Flattens a payload schema into Map<path, { types, required, enum, const, constraints, additionalProperties }>.
 * const is the JSON text of the declared const (undefined when none); constraints maps each
 * CONSTRAINT_KEYWORDS keyword present to its JSON text; additionalProperties is the JSON text of
 * that keyword (undefined when absent).
 * Root path is '$'; properties are dotted ('$.data.amount'); array items are '[]'.
 */
export function flattenPayload(payload, ctx, resolver) {
  const out = new Map();
  const walk = (entries, p, required, depth) => {
    if (depth > 30) return;
    const view = mergedView(entries, resolver, depth);
    out.set(p, {
      types: [...view.types].sort().join('|') || null,
      required,
      enum: view.enum ? [...new Set(view.enum.map((v) => JSON.stringify(v)))].sort() : null,
      const: view.const,
      constraints: view.constraints,
      additionalProperties: view.additionalProperties === undefined ? undefined : JSON.stringify(view.additionalProperties),
    });
    for (const [name, subs] of view.properties) {
      walk(subs, `${p}.${name}`, view.required.has(name), depth + 1);
    }
    if (view.items.length > 0) walk(view.items, `${p}[]`, true, depth + 1);
  };
  walk([{ node: payload, ctx }], '$', true, 0);
  return out;
}

/** Lists channels as [{ key, address, channel, messages: [{ key, message }] }]. */
export function listChannels(doc, file, resolver) {
  const result = [];
  for (const [key, channel] of Object.entries(doc?.channels ?? {})) {
    const messages = [];
    for (const [mKey, mRef] of Object.entries(channel?.messages ?? {})) {
      const { node, ctx } = resolver ? resolver.deref(mRef, { file }) : { node: mRef, ctx: { file } };
      messages.push({ key: mKey, message: node, ctx });
    }
    result.push({ key, address: channel?.address ?? null, channel, messages });
  }
  return result;
}
