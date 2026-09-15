import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { OfficeBuilding, HomeFilled, Key, Collection, SetUp } from '@element-plus/icons-vue';
import { $t } from '../../locales';
export const useAppStore = defineStore('app', () => {
  const siderCollapse = ref(false);
  const navigation = computed(() => [
    { path: '/', label: $t('navigationHome'), icon: HomeFilled },
    { path: '/tenants', label: $t('tenantsTitle'), icon: OfficeBuilding },
    { path: '/quota-definitions', label: $t('quotaDefinitionsTitle'), icon: SetUp },
    { path: '/plans', label: $t('planDefinitionsTitle'), icon: Collection },
    { path: '/oauth-clients', label: 'OAuth Client', icon: Key },
  ]);
  function toggleSiderCollapse() {
    siderCollapse.value = !siderCollapse.value;
  }
  return { siderCollapse, navigation, toggleSiderCollapse };
});
