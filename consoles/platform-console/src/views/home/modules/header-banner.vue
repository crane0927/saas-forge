<script setup lang="ts">
import { ElCard, ElRow, ElCol } from 'element-plus';
import { $t } from '../../../locales';
import SystemLogo from '../../../components/custom/SystemLogo.vue';
defineProps<{ name?: string }>();
</script>
<template>
  <ElCard class="card-wrapper"
    ><ElRow :gutter="16" class="px-8px"
      ><ElCol :span="24"
        ><div class="flex-y-center">
          <div class="size-72px shrink-0 overflow-hidden rd-1/2">
            <SystemLogo class="size-full" />
          </div>
          <div class="pl-12px">
            <h2 class="text-18px font-semibold">
              {{ $t('greeting', { userName: name || 'SaaS Forge' }) }}
            </h2>
            <p class="text-secondary leading-30px">{{ $t('platformOverviewTitle') }}</p>
          </div>
        </div></ElCol
      ></ElRow
    ></ElCard
  >
</template>
