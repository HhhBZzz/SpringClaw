<script setup lang="ts">
import { computed } from 'vue';
import type { ChatMessage } from '../types';

const props = defineProps<{ message: ChatMessage }>();

type RenderBlock =
  | { type: 'heading'; text: string; level: number }
  | { type: 'paragraph'; text: string }
  | { type: 'list'; ordered: boolean; items: string[] }
  | { type: 'code'; text: string };

const blocks = computed(() => renderBlocks(props.message.content));

function formatTime(value: number) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour12: false });
}

function renderBlocks(content: string): RenderBlock[] {
  if (!content.trim()) {
    return [{ type: 'paragraph', text: '正在生成回答...' }];
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
    const heading = trimmed.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      result.push({ type: 'heading', level: heading[1].length, text: cleanInline(heading[2]) });
      index += 1;
      continue;
    }
    const unordered = trimmed.match(/^[-*]\s+(.+)$/);
    if (unordered) {
      const items: string[] = [];
      while (index < lines.length) {
        const item = lines[index].trim().match(/^[-*]\s+(.+)$/);
        if (!item) break;
        items.push(cleanInline(item[1]));
        index += 1;
      }
      result.push({ type: 'list', ordered: false, items });
      continue;
    }
    const ordered = trimmed.match(/^\d+[.)]\s+(.+)$/);
    if (ordered) {
      const items: string[] = [];
      while (index < lines.length) {
        const item = lines[index].trim().match(/^\d+[.)]\s+(.+)$/);
        if (!item) break;
        items.push(cleanInline(item[1]));
        index += 1;
      }
      result.push({ type: 'list', ordered: true, items });
      continue;
    }
    const paragraph: string[] = [];
    while (index < lines.length) {
      const next = lines[index].trim();
      if (!next || next.startsWith('```') || /^(#{1,4})\s+/.test(next) || /^[-*]\s+/.test(next) || /^\d+[.)]\s+/.test(next)) {
        break;
      }
      paragraph.push(cleanInline(next));
      index += 1;
    }
    result.push({ type: 'paragraph', text: paragraph.join('\n') });
  }
  return result;
}

function cleanInline(text: string) {
  return text
    .replace(/\*\*(.*?)\*\*/g, '$1')
    .replace(/__(.*?)__/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\*/g, '')
    .trim();
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
        <h2 v-if="block.type === 'heading'" :class="`level-${block.level}`">{{ block.text }}</h2>
        <p v-else-if="block.type === 'paragraph'">{{ block.text }}</p>
        <ol v-else-if="block.type === 'list' && block.ordered">
          <li v-for="item in block.items" :key="item">{{ item }}</li>
        </ol>
        <ul v-else-if="block.type === 'list'">
          <li v-for="item in block.items" :key="item">{{ item }}</li>
        </ul>
        <pre v-else-if="block.type === 'code'"><code>{{ block.text }}</code></pre>
      </template>
    </div>
  </article>
</template>
