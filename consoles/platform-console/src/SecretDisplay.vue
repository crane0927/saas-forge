<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue';
import { ElButton, ElCard } from 'element-plus';
import { usePlatform } from './use-platform';
const props = defineProps<{ secret: string }>();
const emit = defineEmits<{ close: [] }>();
const { t } = usePlatform();
const copied = ref<'ready' | 'done' | 'failed'>('ready');
const close = () => emit('close');
onMounted(() => window.addEventListener('pagehide', close));
onScopeDispose(() => window.removeEventListener('pagehide', close));
async function copy() {
  try {
    await navigator.clipboard.writeText(props.secret);
    copied.value = 'done';
  } catch {
    copied.value = 'failed';
  }
}
</script>
<template>
  <ElCard shadow="never"
    ><template #header
      ><h2>{{ t('oauthSecretTitle') }}</h2></template
    >
    <p>{{ t('oauthSecretWarning') }}</p>
    <output :aria-label="t('oauthSecretTitle')" style="overflow-wrap: anywhere">{{
      secret
    }}</output>
    <div class="console-actions">
      <ElButton @click="copy">{{ t('oauthCopy') }}</ElButton
      ><ElButton @click="close">{{ t('oauthCloseSecret') }}</ElButton>
    </div>
    <p v-if="copied !== 'ready'" role="status">
      {{ t(copied === 'done' ? 'oauthCopied' : 'oauthCopyFailed') }}
    </p></ElCard
  >
</template>
