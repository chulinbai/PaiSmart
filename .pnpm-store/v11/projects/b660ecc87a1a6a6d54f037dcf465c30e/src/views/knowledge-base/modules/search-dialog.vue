<script setup lang="ts">
defineOptions({
  name: 'SearchDialog'
});

const loading = ref(false);
const visible = defineModel<boolean>('visible', { default: false });

const { formRef, restoreValidation } = useNaiveForm();

const store = useAuthStore();
const model = ref<Api.KnowledgeBase.SearchParams>(createDefaultModel());

function createDefaultModel(): Api.KnowledgeBase.SearchParams {
  return {
    userId: `${store.userInfo.id}`,
    query: '',
    topK: 10
  };
}

const list = ref<Api.KnowledgeBase.SearchResult[]>([]);

const patterns = ref<string[]>([]);
function highlight(text: string) {
  if (!model.value.query) return false;
  if (text.includes(model.value.query)) return true;
  return false;
}

async function search() {
  loading.value = true;
  const { error, data } = await request<Api.KnowledgeBase.SearchResult[]>({
    url: 'search/multimodal',
    params: model.value
  });
  if (!error) {
    list.value = data;
    patterns.value = [model.value.query];
  }
  loading.value = false;
}

function reset() {
  model.value = createDefaultModel();
  patterns.value = [];
  list.value = [];
  restoreValidation();
}
watch(visible, () => {
  if (visible.value) {
    reset();
  }
});

function getKnowledgeTypeLabel(item: Api.KnowledgeBase.SearchResult) {
  if (item.knowledgeType === 'DOCUMENT_IMAGE') return 'PDF 图片';
  if (item.knowledgeType === 'STANDALONE_IMAGE') return '独立图片';
  if (item.knowledgeType === 'TEXT_CHUNK') return '文本片段';
  return '知识片段';
}

function getDisplayText(item: Api.KnowledgeBase.SearchResult) {
  return item.textContent || item.imageText || item.searchText || item.matchedChunkText || '';
}

function getMetaLine(item: Api.KnowledgeBase.SearchResult) {
  const parts = [];
  if (item.pageNumber) parts.push(`第 ${item.pageNumber} 页`);
  if (item.imageSequence) parts.push(`第 ${item.imageSequence} 张图片`);
  if (item.width && item.height) parts.push(`${item.width}×${item.height}`);
  return parts.join(' / ');
}
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="知识库检索"
    :show-icon="false"
    :mask-closable="false"
    class="w-1000px!"
  >
    <NForm
      ref="formRef"
      :model="model"
      label-placement="left"
      :label-width="60"
      inline
      class="pb-2"
      :show-feedback="false"
    >
      <NGrid>
        <NFormItemGi label="topK" path="topK" class="pr-24px" span="6">
          <NInputNumber
            v-model:value="model.topK"
            placeholder="请输入topK"
            clearable
            :min="1"
            :precision="0"
            :step="10"
          />
        </NFormItemGi>
        <NFormItemGi label="关键字" path="query" class="pr-24px" span="12">
          <NInput v-model:value="model.query" placeholder="请输入关键字" clearable />
        </NFormItemGi>
        <NFormItemGi span="6">
          <NSpace class="w-full" justify="end">
            <NButton @click="reset">
              <template #icon>
                <icon-ic-round-refresh class="text-icon" />
              </template>
              重置
            </NButton>
            <NButton type="primary" ghost @click="search">
              <template #icon>
                <icon-ic-round-search class="text-icon" />
              </template>
              搜索
            </NButton>
          </NSpace>
        </NFormItemGi>
      </NGrid>
    </NForm>
    <NSpin :show="loading">
      <NEmpty v-if="list.length === 0" description="暂无数据" class="py-100px" />
      <NScrollbar v-else class="max-h-500px">
        <NCard
          v-for="(item, index) in list"
          :key="index"
          class="my-8"
          embedded
          :segmented="{
            content: true,
            footer: 'soft'
          }"
        >
          <div class="relative">
            <div class="mb-2 flex flex-wrap items-center gap-2 pr-28">
              <NTag size="small" :bordered="false" type="info">
                {{ getKnowledgeTypeLabel(item) }}
              </NTag>
              <span v-if="getMetaLine(item)" class="text-xs text-gray-500">{{ getMetaLine(item) }}</span>
            </div>
            <img
              v-if="item.knowledgeType === 'STANDALONE_IMAGE' && item.imageUrl"
              :src="item.imageUrl"
              :alt="item.fileName"
              class="mb-3 h-120px max-w-220px rounded-8px object-contain"
            />
            <NHighlight
              v-if="highlight(getDisplayText(item))"
              highlight-class="bg-[rgb(var(--primary-400-color))] color-white px-2 mx-1 rd-sm"
              :text="getDisplayText(item)"
              :patterns="patterns"
            />
            <span v-else>{{ getDisplayText(item) }}</span>
            <NTag
              :bordered="false"
              draggable
              class="absolute right-0 top-0 bg-[rgb(var(--primary-color)/.9)] color-white hover:bg-transparent hover:color-transparent"
            >
              Score: {{ item.score }}
            </NTag>
          </div>
          <template #footer>
            <span>来源：{{ item.fileName }}</span>
          </template>
        </NCard>
      </NScrollbar>
    </NSpin>
  </NModal>
</template>

<style scoped></style>
