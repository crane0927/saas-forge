import { createApp, h, ref } from 'vue';
import { ElConfigProvider, ElSelect, ElOption } from 'element-plus';
import zhCN from 'element-plus/es/locale/lang/zh-cn';
import enUS from 'element-plus/es/locale/lang/en';
import type { SupportedLocale } from '@saas-forge/i18n';
import '@saas-forge/admin/styles.css';
import Remote from '../src/Remote.vue';
createApp({
  setup() {
    const locale = ref<SupportedLocale>('zh-CN');
    return () =>
      h(ElConfigProvider, { locale: locale.value === 'zh-CN' ? zhCN : enUS }, () => [
        h(
          ElSelect,
          {
            modelValue: locale.value,
            'aria-label': 'Language / 语言',
            'onUpdate:modelValue': (value: SupportedLocale) => {
              locale.value = value;
            },
          },
          () => [
            h(ElOption, { value: 'zh-CN', label: '简体中文' }),
            h(ElOption, { value: 'en-US', label: 'English' }),
          ],
        ),
        h(Remote, { locale: locale.value }),
      ]);
  },
}).mount('#app');
