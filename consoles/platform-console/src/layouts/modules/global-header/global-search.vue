<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { ElButton, ElDialog, ElEmpty, ElIcon, ElInput, ElScrollbar } from 'element-plus';
import type { InputInstance } from 'element-plus';
import { useRouter } from 'vue-router';
import { useAppStore } from '../../../store/modules/app';
import { $t } from '../../../locales';
import SaasIcon from '../../../components/common/SaasIcon.vue';

const appStore = useAppStore();
const router = useRouter();
const visible = ref(false);
const keyword = ref('');
const input = ref<InputInstance>();
const results = computed(() => {
  const normalized = keyword.value.trim().toLocaleLowerCase();
  return appStore.navigation.filter((item) => {
    if (!normalized) return true;
    return item.label.toLocaleLowerCase().includes(normalized);
  });
});

function open() {
  visible.value = true;
}

function close() {
  visible.value = false;
}

async function navigate(path: string) {
  await router.push(path);
  close();
}

watch(visible, (value) => {
  if (value) {
    keyword.value = '';
    void nextTick(() => input.value?.focus());
  }
});
</script>

<template>
  <ElButton text circle :aria-label="$t('globalSearch')" @click="open"
    ><SaasIcon name="search"
  /></ElButton>
  <ElDialog
    v-model="visible"
    :title="$t('globalSearch')"
    width="480px"
    append-to-body
    destroy-on-close
  >
    <ElInput
      ref="input"
      v-model="keyword"
      :placeholder="$t('globalSearchPlaceholder')"
      :aria-label="$t('globalSearchPlaceholder')"
      clearable
      @keydown.esc="close"
    >
      <template #prefix><SaasIcon name="search" /></template>
    </ElInput>
    <ElScrollbar v-if="results.length" max-height="280px" class="mt-12px">
      <button
        v-for="item in results"
        :key="item.path"
        type="button"
        class="global-search-result"
        @click="navigate(item.path)"
      >
        <ElIcon><component :is="item.icon" /></ElIcon>
        <span>{{ item.label }}</span>
      </button>
    </ElScrollbar>
    <ElEmpty v-else :description="$t('globalSearchNoResults')" />
  </ElDialog>
</template>
