<script setup lang="ts">
import { applyGenerationChunk, applyTerminalSnapshot, isTerminalGeneration } from '@/utils/chat-generation';
const chatStore = useChatStore();
const {
  connectionStatus,
  contextLoading,
  contextStatus,
  input,
  isRateLimited,
  list,
  rateLimitRemainingSeconds,
  wsData
} = storeToRefs(chatStore);

const contextPercent = computed(() => Math.min(100, Math.round((contextStatus.value?.contextUsageRatio || 0) * 100)));
const contextBreakdown = computed(() => Object.entries(contextStatus.value?.breakdown || {}));

async function compactContext() {
  if (await chatStore.compactContext()) window.$message?.success('上下文压缩完成');
}

async function resetContext() {
  if (await chatStore.resetContext()) window.$message?.success('线程上下文已重置');
}

function buildWsErrorMessage(data: Record<string, any>) {
  if (data.code === 429) {
    const retryAfterSeconds = Number(data.retryAfterSeconds || 0);
    const baseMessage = data.message || '聊天请求过于频繁';

    if (retryAfterSeconds > 0) {
      return `${baseMessage}，请在 ${retryAfterSeconds} 秒后重试`;
    }

    return `${baseMessage}，请稍后再试`;
  }

  if (typeof data.error === 'string' && data.error.trim()) {
    return data.error.trim();
  }

  if (typeof data.message === 'string' && data.message.trim()) {
    return data.message.trim();
  }

  return '服务器繁忙，请稍后再试';
}

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

let generationStatusTimer: number | null = null;
let lastStreamContentLength = 0;
let lastStreamContentChangedAt = 0;

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendDisabled = computed(() => {
  if (isSending.value) {
    return false;
  }
  if (isRateLimited.value) {
    return true;
  }
  return !input.value.message || ['CLOSED', 'CONNECTING'].includes(connectionStatus.value);
});

const connectionText = computed(() => {
  if (connectionStatus.value === 'OPEN') {
    return '已连接';
  }
  if (connectionStatus.value === 'RECONNECTING') {
    return '重连中';
  }
  if (connectionStatus.value === 'CONNECTING') {
    return '连接中';
  }
  return '未连接';
});

const cooldownText = computed(() => {
  if (!isRateLimited.value) {
    return '';
  }
  return `${rateLimitRemainingSeconds} 秒后可重新发送`;
});

function findAssistantMessage(generationId?: string) {
  if (generationId) {
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && item.generationId === generationId) {
        return item;
      }
    }
  }

  const latest = list.value[list.value.length - 1];
  if (generationId && latest?.generationId) return null;
  if (latest?.role === 'assistant') {
    return latest;
  }

  return null;
}

function handleStartPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  chatStore.updateContextStatus(payload);
  assistant.generationId = payload.generationId || assistant.generationId;
  assistant.conversationId = payload.conversationId || assistant.conversationId;
  if (!assistant.timestamp && payload.timestamp) {
    assistant.timestamp = new Date(payload.timestamp).toISOString();
  }
}

function handleCompletionPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (!applyTerminalSnapshot(assistant, payload)) return;
  chatStore.updateContextStatus(payload);
  if (payload.status === 'finished' && assistant.status !== 'error') {
    assistant.status = 'finished';
  } else if (payload.status === 'failed') {
    assistant.status = 'error';
  }

  if (payload.referenceMappings) {
    assistant.referenceMappings = payload.referenceMappings;
  }
  assistant.finishReason = payload.finishReason || assistant.finishReason;
  assistant.truncated = Boolean(payload.truncated);
  assistant.continuationAttempts = Number(payload.continuationAttempts || 0);
  if (assistant.generationStatus !== 'COMPLETED') {
    markExecutingToolsAsFailed(assistant);
  } else {
    markExecutingToolsAsSuccess(assistant);
  }
  stopGenerationStatusMonitor();
}

function handleStopPayload(assistant: Api.Chat.Message) {
  if (assistant.status !== 'error') {
    assistant.status = 'finished';
  }
  markExecutingToolsAsSuccess(assistant);
  stopGenerationStatusMonitor();
}

function handleErrorPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (Number(payload.code) === 429) {
    chatStore.startRateLimitCountdown(Number(payload.retryAfterSeconds || 0));
  }

  const message = buildWsErrorMessage(payload);
  assistant.status = 'error';
  assistant.content = message;
  markExecutingToolsAsFailed(assistant);
  stopGenerationStatusMonitor();

  if (Number(payload.code) === 429) {
    window.$message?.warning(message);
  } else {
    window.$message?.error(message);
  }
}

function handleChunkPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (!applyGenerationChunk(assistant, payload)) return;
  lastStreamContentLength = assistant.content.length;
  lastStreamContentChangedAt = Date.now();
}

function stopGenerationStatusMonitor() {
  if (generationStatusTimer !== null) {
    window.clearInterval(generationStatusTimer);
    generationStatusTimer = null;
  }
}

function startGenerationStatusMonitor() {
  stopGenerationStatusMonitor();
  const startedAt = Date.now();
  lastStreamContentLength = 0;
  lastStreamContentChangedAt = startedAt;
  generationStatusTimer = window.setInterval(async () => {
    const assistant = findAssistantMessage(latestMessage.value?.generationId);
    if (!assistant || assistant.role !== 'assistant') {
      stopGenerationStatusMonitor();
      return;
    }
    if (!['pending', 'loading'].includes(assistant.status || '')) {
      stopGenerationStatusMonitor();
      return;
    }
    if (Date.now() - startedAt > 130_000 && assistant.generationStatus !== 'FINALIZING') {
      stopGenerationStatusMonitor();
      return;
    }
    if (assistant.content.length !== lastStreamContentLength) {
      lastStreamContentLength = assistant.content.length;
      lastStreamContentChangedAt = Date.now();
      return;
    }
    if (Date.now() - lastStreamContentChangedAt < 8000) {
      return;
    }

    const snapshot = await chatStore.fetchGenerationSnapshot(assistant.generationId || '');
    if (!snapshot || snapshot.status === 'STREAMING') {
      return;
    }
    chatStore.upsertGenerationSnapshot(snapshot);
    const refreshedAssistant = findAssistantMessage(snapshot.generationId);
    if (refreshedAssistant?.status === 'finished') {
      if (refreshedAssistant.generationStatus === 'COMPLETED') markExecutingToolsAsSuccess(refreshedAssistant);
      else markExecutingToolsAsFailed(refreshedAssistant);
      stopGenerationStatusMonitor();
    } else if (refreshedAssistant?.status === 'error') {
      markExecutingToolsAsFailed(refreshedAssistant);
      stopGenerationStatusMonitor();
    }
  }, 2000);
}

function markExecutingToolsAsSuccess(assistant: Api.Chat.Message) {
  updateExecutingToolStatus(assistant, 'success');
}

function markExecutingToolsAsFailed(assistant: Api.Chat.Message) {
  updateExecutingToolStatus(assistant, 'failed');
}

function updateExecutingToolStatus(assistant: Api.Chat.Message, status: Api.Chat.AgentToolEvent['status']) {
  if (!assistant.toolEvents?.length) {
    return;
  }

  let changed = false;
  const timestamp = Date.now();
  const toolEvents = assistant.toolEvents.map(event => {
    if (event.status !== 'executing') {
      return event;
    }
    changed = true;
    return {
      ...event,
      status,
      timestamp
    };
  });

  if (changed) {
    assistant.toolEvents = toolEvents;
  }
}

function handleToolCallPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  const id = typeof payload.toolCallId === 'string' ? payload.toolCallId : '';
  const tool = typeof payload.tool === 'string' ? payload.tool : '';
  const status = typeof payload.status === 'string' ? payload.status : 'executing';
  if (!tool || !['executing', 'success', 'failed'].includes(status)) {
    return;
  }

  assistant.status = 'loading';
  assistant.toolEvents ||= [];
  const event = {
    id,
    tool,
    status: status as Api.Chat.AgentToolEvent['status'],
    timestamp: Number(payload.timestamp || Date.now())
  };

  const matchEvent = (item: Api.Chat.AgentToolEvent) => {
    if (id && item.id) {
      return item.id === id;
    }
    if (!id && !item.id) {
      return item.tool === tool;
    }
    return false;
  };

  if (assistant.toolEvents.some(matchEvent)) {
    assistant.toolEvents = assistant.toolEvents.map(item => (matchEvent(item) ? event : item));
  } else {
    assistant.toolEvents = [...assistant.toolEvents, event];
  }
}

watch(wsData, val => {
  if (!val) return;

  let payload: Record<string, any>;

  try {
    payload = JSON.parse(val);
  } catch {
    return;
  }

  const assistant = findAssistantMessage(payload.generationId);

  if (!assistant) return;
  if (assistant.terminalToken || isTerminalGeneration(assistant.generationStatus)) return;
  if (payload.type === 'persistence_status') {
    assistant.generationStatus = 'FINALIZING';
    assistant.persistenceStatus = payload.persistenceStatus;
    assistant.status = 'loading';
    return;
  }

  if (payload.type === 'start') {
    handleStartPayload(assistant, payload);
    return;
  }

  if (payload.type === 'completion') {
    handleCompletionPayload(assistant, payload);
    return;
  }

  if (payload.type === 'tool_call') {
    handleToolCallPayload(assistant, payload);
    return;
  }

  if (payload.type === 'stop') {
    handleStopPayload(assistant);
    return;
  }

  if (payload.error || Number(payload.code) >= 400) {
    handleErrorPayload(assistant, payload);
    return;
  }

  if (payload.chunk) {
    handleChunkPayload(assistant, payload);
  }
});

const handleSend = async () => {
  if (isRateLimited.value) {
    window.$message?.warning(`当前发送受限，${cooldownText.value}`);
    return;
  }

  if (isSending.value) {
    const { error, data: tokenData } = await request<Api.Chat.Token>({
      url: 'chat/websocket-token'
    });
    if (error) return;

    chatStore.wsSend(
      JSON.stringify({
        type: 'stop',
        generationId: latestMessage.value.generationId,
        _internal_cmd_token: tokenData.cmdToken
      })
    );

    latestMessage.value.generationStatus = 'FINALIZING';
    latestMessage.value.persistenceStatus = 'PENDING';
    return;
  }

  list.value.push({
    content: input.value.message,
    role: 'user'
  });
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending',
    toolEvents: []
  });
  chatStore.wsSend(input.value.message);
  input.value.message = '';
  startGenerationStatusMonitor();
};

const inputRef = ref();
watch(() => chatStore.regenerationRequest, async request => {
  if (!request || isSending.value) return;
  input.value.message = request.question;
  await handleSend();
});
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus();
  });
};

const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};

onUnmounted(() => {
  stopGenerationStatusMonitor();
});
</script>

<template>
  <div class="relative shrink-0 bg-white px-4 pb-3 pt-2 dark:bg-[#1c1c1c]">
    <div
      class="pointer-events-none absolute inset-x-0 h-6 from-white/95 to-transparent bg-gradient-to-t -top-6 dark:from-[#1c1c1c]/95"
    />
    <div
      class="chat-input-shell mx-auto max-w-[960px] w-full flex items-end gap-2 rounded-2xl bg-white px-3.5 py-2.5 dark:bg-[#1f1f1f]"
    >
      <textarea
        ref="inputRef"
        v-model.trim="input.message"
        placeholder="给 派聪明 发送消息，Enter 发送，Shift+Enter 换行"
        class="max-h-32 min-h-6 w-full flex-1 resize-none border-none bg-transparent py-1 text-14px color-#333 caret-[rgb(var(--primary-color))] outline-none placeholder:text-#bbb dark:color-#e1e1e1 dark:placeholder:text-#555"
        @keydown="handShortcut"
      />
      <NButton
        :disabled="sendDisabled"
        class="shrink-0 self-end"
        size="small"
        circle
        :type="isSending ? 'warning' : 'primary'"
        @click="handleSend"
      >
        <template #icon>
          <icon-material-symbols:stop-rounded v-if="isSending" class="text-16px" />
          <icon-material-symbols:arrow-upward-rounded v-else class="text-16px" />
        </template>
      </NButton>
    </div>
    <div class="mx-auto mt-1.5 max-w-[960px] w-full flex items-center justify-between px-1">
      <div class="flex items-center gap-2">
        <div class="flex items-center gap-1">
          <span
            class="inline-block h-1.5 w-1.5 rounded-full"
            :class="{
              'bg-green-500': connectionStatus === 'OPEN',
              'bg-yellow-500 animate-pulse': connectionStatus === 'CONNECTING' || connectionStatus === 'RECONNECTING',
              'bg-red-400': connectionStatus === 'CLOSED'
            }"
          />
          <span class="text-11px color-#aaa">{{ connectionText }}</span>
        </div>
        <span v-if="isRateLimited" class="text-11px text-[rgb(var(--primary-color))]">
          {{ cooldownText }}
        </span>
        <NPopover trigger="click" placement="top-start" :width="300" @update:show="show => show && chatStore.loadContextStatus()">
          <template #trigger>
            <NTooltip>
              <template #trigger>
                <button
                  class="h-6 inline-flex items-center gap-1 border-0 bg-transparent px-1 text-11px color-#999 hover:color-[rgb(var(--primary-color))]"
                  aria-label="上下文状态"
                >
                  <icon-material-symbols:data-usage-rounded class="text-15px" />
                  <span>{{ contextPercent }}%</span>
                </button>
              </template>
              上下文状态
            </NTooltip>
          </template>
          <div class="space-y-3 text-12px">
            <div class="flex items-center justify-between">
              <strong class="text-13px">上下文</strong>
              <span :class="contextStatus?.contextDegraded ? 'text-orange-500' : 'color-#999'">
                {{ contextStatus?.contextDegraded ? '已裁剪' : `摘要 v${contextStatus?.snapshotVersion || 0}` }}
              </span>
            </div>
            <NProgress
              type="line"
              :percentage="contextPercent"
              :height="7"
              :show-indicator="false"
              :status="contextPercent >= 85 ? 'warning' : 'default'"
            />
            <div class="flex justify-between color-#777 dark:color-#aaa">
              <span>{{ (contextStatus?.contextUsedTokens || 0).toLocaleString() }} tokens</span>
              <span>{{ (contextStatus?.contextWindowTokens || 0).toLocaleString() }} 窗口</span>
            </div>
            <div v-if="contextBreakdown.length" class="grid grid-cols-2 gap-x-4 gap-y-1 color-#777 dark:color-#aaa">
              <template v-for="[name, tokens] in contextBreakdown" :key="name">
                <span>{{ name }}</span><span class="text-right">{{ tokens.toLocaleString() }}</span>
              </template>
            </div>
            <div class="flex justify-end gap-2 border-t border-#eee pt-2 dark:border-#333">
              <NButton size="tiny" :loading="contextLoading" @click="compactContext">
                <template #icon><icon-material-symbols:compress-rounded /></template>
                压缩
              </NButton>
              <NPopconfirm @positive-click="resetContext">
                <template #trigger>
                  <NButton size="tiny" type="warning" ghost>
                    <template #icon><icon-material-symbols:restart-alt-rounded /></template>
                    重置
                  </NButton>
                </template>
                后续请求将忽略当前边界前的线程上下文。
              </NPopconfirm>
            </div>
          </div>
        </NPopover>
      </div>
      <span class="text-11px color-#bbb">Shift+Enter 换行</span>
    </div>
  </div>
</template>

<style scoped>
.chat-input-shell {
  border: 1px solid rgba(15, 23, 42, 0.12);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 6px 18px rgba(15, 23, 42, 0.04);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    background-color 0.18s ease;
}

.chat-input-shell:focus-within {
  border-color: rgb(var(--primary-color) / 0.38);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 8px 24px rgba(15, 23, 42, 0.06);
}

.dark .chat-input-shell {
  border-color: rgba(255, 255, 255, 0.1);
  box-shadow: none;
}
</style>
