<script setup lang="ts">
import { computed } from 'vue';
import type { ChatMessage } from '../types';

const props = defineProps<{ message: ChatMessage }>();

type InlineSegment = { type: 'text' | 'strong' | 'code'; text: string };
type RenderBlock =
  | { type: 'heading'; segments: InlineSegment[]; level: number }
  | { type: 'paragraph'; segments: InlineSegment[] }
  | { type: 'quote'; segments: InlineSegment[] }
  | { type: 'list'; ordered: boolean; items: InlineSegment[][] }
  | { type: 'table'; headers: InlineSegment[][]; rows: InlineSegment[][][] }
  | { type: 'code'; text: string };

const blocks = computed(() => renderBlocks(props.message.content));

function formatTime(value: number) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour12: false });
}

function renderBlocks(content: string): RenderBlock[] {
  if (!content.trim()) {
    return [{ type: 'paragraph', segments: [{ type: 'text', text: '正在生成回答...' }] }];
  }
  const lines = content.replace(/\r\n/g, '\n').split('\n');
  const result: RenderBlock[] = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    const trimmed = line.trim();
    if (!trimmed || /^-{3,}$/.test(trimmed)) {
      index += 1;
      continue;
    }
    if (trimmed.startsWith('```')) {
      const codeLines: string[] = [];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        codeLines.push(lines[index]);
        index += 1;
      }
      result.push({ type: 'code', text: codeLines.join('\n') });
      index += 1;
      continue;
    }
    if (isTableStart(lines, index)) {
      const headers = splitTableRow(lines[index]).map(parseInline);
      index += 2;
      const rows: InlineSegment[][][] = [];
      while (index < lines.length && isTableRow(lines[index])) {
        rows.push(splitTableRow(lines[index]).map(parseInline));
        index += 1;
      }
      result.push({ type: 'table', headers, rows });
      continue;
    }
    const heading = trimmed.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      result.push({ type: 'heading', level: heading[1].length, segments: parseInline(heading[2]) });
      index += 1;
      continue;
    }
    if (trimmed.startsWith('>')) {
      const quoteLines: string[] = [];
      while (index < lines.length && lines[index].trim().startsWith('>')) {
        quoteLines.push(lines[index].trim().replace(/^>\s?/, ''));
        index += 1;
      }
      result.push({ type: 'quote', segments: parseInline(quoteLines.join('\n')) });
      continue;
    }
    const unordered = trimmed.match(/^[-*]\s+(.+)$/);
    if (unordered) {
      const items: InlineSegment[][] = [];
      while (index < lines.length) {
        const item = lines[index].trim().match(/^[-*]\s+(.+)$/);
        if (!item) break;
        items.push(parseInline(item[1]));
        index += 1;
      }
      result.push({ type: 'list', ordered: false, items });
      continue;
    }
    const ordered = trimmed.match(/^\d+[.)]\s+(.+)$/);
    if (ordered) {
      const items: InlineSegment[][] = [];
      while (index < lines.length) {
        const item = lines[index].trim().match(/^\d+[.)]\s+(.+)$/);
        if (!item) break;
        items.push(parseInline(item[1]));
        index += 1;
      }
      result.push({ type: 'list', ordered: true, items });
      continue;
    }
    const paragraph: string[] = [];
    while (index < lines.length) {
      const next = lines[index].trim();
      if (!next || next.startsWith('```') || /^(#{1,4})\s+/.test(next) || /^[-*]\s+/.test(next) || /^\d+[.)]\s+/.test(next) || next.startsWith('>') || isTableStart(lines, index)) {
        break;
      }
      paragraph.push(next);
      index += 1;
    }
    result.push({ type: 'paragraph', segments: parseInline(paragraph.join('\n')) });
  }
  return result;
}

function parseInline(text: string): InlineSegment[] {
  const segments: InlineSegment[] = [];
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*|__[^_]+__)/g;
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    const start = match.index || 0;
    if (start > cursor) {
      segments.push({ type: 'text', text: text.slice(cursor, start) });
    }
    const raw = match[0];
    if (raw.startsWith('`')) {
      segments.push({ type: 'code', text: raw.slice(1, -1) });
    } else {
      segments.push({ type: 'strong', text: raw.slice(2, -2) });
    }
    cursor = start + raw.length;
  }
  if (cursor < text.length) {
    segments.push({ type: 'text', text: text.slice(cursor) });
  }
  return segments.length ? segments : [{ type: 'text', text }];
}

function isTableStart(lines: string[], index: number) {
  return isTableRow(lines[index]) && index + 1 < lines.length && /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(lines[index + 1]);
}

function isTableRow(line: string) {
  return Boolean(line && line.includes('|') && splitTableRow(line).length >= 2);
}

function splitTableRow(line: string) {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim());
}
</script>

<template>
  <article class="message" :class="message.role">
    <div class="message-meta">
      <span>{{ message.role === 'user' ? '你' : message.role === 'agent' ? 'SpringClaw Agent' : 'System' }}</span>
      <span>{{ formatTime(message.createdAt) }}</span>
      <span v-if="message.model">{{ message.model }}</span>
    </div>
    <div class="bubble rich-message">
      <template v-for="(block, index) in blocks" :key="index">
        <h2 v-if="block.type === 'heading'" :class="`level-${block.level}`">
          <template v-for="(segment, segmentIndex) in block.segments" :key="segmentIndex">
            <strong v-if="segment.type === 'strong'">{{ segment.text }}</strong>
            <code v-else-if="segment.type === 'code'">{{ segment.text }}</code>
            <span v-else>{{ segment.text }}</span>
          </template>
        </h2>
        <p v-else-if="block.type === 'paragraph'">
          <template v-for="(segment, segmentIndex) in block.segments" :key="segmentIndex">
            <strong v-if="segment.type === 'strong'">{{ segment.text }}</strong>
            <code v-else-if="segment.type === 'code'">{{ segment.text }}</code>
            <span v-else>{{ segment.text }}</span>
          </template>
        </p>
        <blockquote v-else-if="block.type === 'quote'">
          <template v-for="(segment, segmentIndex) in block.segments" :key="segmentIndex">
            <strong v-if="segment.type === 'strong'">{{ segment.text }}</strong>
            <code v-else-if="segment.type === 'code'">{{ segment.text }}</code>
            <span v-else>{{ segment.text }}</span>
          </template>
        </blockquote>
        <ol v-else-if="block.type === 'list' && block.ordered">
          <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
            <template v-for="(segment, segmentIndex) in item" :key="segmentIndex">
              <strong v-if="segment.type === 'strong'">{{ segment.text }}</strong>
              <code v-else-if="segment.type === 'code'">{{ segment.text }}</code>
              <span v-else>{{ segment.text }}</span>
            </template>
          </li>
        </ol>
        <ul v-else-if="block.type === 'list'">
          <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
            <template v-for="(segment, segmentIndex) in item" :key="segmentIndex">
              <strong v-if="segment.type === 'strong'">{{ segment.text }}</strong>
              <code v-else-if="segment.type === 'code'">{{ segment.text }}</code>
              <span v-else>{{ segment.text }}</span>
            </template>
          </li>
        </ul>
        <div v-else-if="block.type === 'table'" class="message-table-wrap">
          <table>
            <thead>
              <tr>
                <th v-for="(cell, cellIndex) in block.headers" :key="cellIndex">
                  <template v-for="(segment, segmentIndex) in cell" :key="segmentIndex">
                    <strong v-if="segment.type === 'strong'">{{ segment.text }}</strong>
                    <code v-else-if="segment.type === 'code'">{{ segment.text }}</code>
                    <span v-else>{{ segment.text }}</span>
                  </template>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
                <td v-for="(cell, cellIndex) in row" :key="cellIndex">
                  <template v-for="(segment, segmentIndex) in cell" :key="segmentIndex">
                    <strong v-if="segment.type === 'strong'">{{ segment.text }}</strong>
                    <code v-else-if="segment.type === 'code'">{{ segment.text }}</code>
                    <span v-else>{{ segment.text }}</span>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <pre v-else-if="block.type === 'code'"><code>{{ block.text }}</code></pre>
      </template>
    </div>
  </article>
</template>
