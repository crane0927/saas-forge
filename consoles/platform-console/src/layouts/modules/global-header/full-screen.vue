<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue';
import { ElButton } from 'element-plus';
import { $t } from '../../../locales';
import SaasIcon from '../../../components/common/SaasIcon.vue';

const isFullscreen = ref(false);

function sync() {
  isFullscreen.value = Boolean(document.fullscreenElement);
}

async function toggle() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.documentElement.requestFullscreen();
    sync();
  } catch {
    // Fullscreen can be unavailable in embedded or restricted browser contexts.
    sync();
  }
}

onMounted(() => document.addEventListener('fullscreenchange', sync));
onScopeDispose(() => document.removeEventListener('fullscreenchange', sync));
</script>

<template>
  <ElButton
    text
    circle
    :aria-label="$t(isFullscreen ? 'exitFullscreen' : 'fullscreen')"
    @click="toggle"
    ><SaasIcon :name="isFullscreen ? 'fullscreen-exit' : 'fullscreen'"
  /></ElButton>
</template>
