<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { NButton, NPopover, NSpace, NTag } from 'naive-ui';
import { request } from '@/service/request';

const props = defineProps<{ documentId: number; canManage: boolean }>();
interface IndexState {
  index_type: 'CHUNK' | 'GRAPH';
  phase: string;
  last_error?: string;
  attempts?: number;
}
const states = ref<IndexState[]>([]);
const loading = ref(false);
const errorText = ref('');
let timer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;
const labels: Record<string, string> = {
  PENDING: '待构建',
  BUILDING: '构建中',
  SYNCING: '同步中',
  READY: '已就绪',
  NOT_READY: '需要重建',
  FAILED: '失败',
  REVOKED: '已撤回'
};

async function load() {
  if (disposed) return;
  clearTimeout(timer);
  const result = await request<IndexState[]>({ url: `/knowledge/documents/${props.documentId}/indexes` });
  if (disposed) return;
  if (result.error) {
    errorText.value = '索引状态暂不可用';
    return;
  }
  errorText.value = '';
  states.value = result.data ?? [];
  if (states.value.some(state => ['PENDING', 'BUILDING', 'SYNCING'].includes(state.phase))) {
    timer = setTimeout(load, 5000);
  }
}

async function rebuild(type?: 'CHUNK' | 'GRAPH') {
  loading.value = true;
  try {
    const result = await request({
      url: `/knowledge/documents/${props.documentId}/indexes/${type ? 'rebuild' : 'backfill'}`,
      method: 'post',
      data: type ? { indexTypes: [type] } : undefined
    });
    if (!result.error) {
      window.$message?.success('构建任务已提交，索引就绪后可检索');
      await load();
    }
  } finally {
    loading.value = false;
  }
}
onMounted(load);
onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(timer);
});
</script>

<template>
  <NSpace vertical :size="4">
    <span v-if="errorText" class="text-error">{{ errorText }}</span>
    <template v-else-if="states.length">
      <NSpace v-for="state in states" :key="state.index_type" align="center" :size="6">
        <NPopover trigger="hover">
          <template #trigger>
            <NTag
              size="small"
              :type="state.phase === 'READY' ? 'success' : state.phase === 'FAILED' ? 'error' : 'warning'"
            >
              {{ state.index_type === 'CHUNK' ? '原文/图片' : '图谱' }} · {{ labels[state.phase] ?? state.phase }}
            </NTag>
          </template>
          <span>{{ state.last_error || '原文与图谱独立构建；已就绪的索引可先用于检索。' }}</span>
        </NPopover>
        <NButton
          v-if="canManage && ['FAILED', 'READY', 'NOT_READY'].includes(state.phase)"
          size="tiny"
          text
          :loading="loading"
          @click="rebuild(state.index_type)"
        >
          {{ state.phase === 'FAILED' ? '重试' : '重建' }}
        </NButton>
      </NSpace>
    </template>
    <template v-else>
      <span class="text-secondary">尚未创建多索引</span>
      <NButton v-if="canManage" size="tiny" text :loading="loading" @click="rebuild()">创建索引</NButton>
    </template>
  </NSpace>
</template>
