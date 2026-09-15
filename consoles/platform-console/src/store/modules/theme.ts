import { computed, onScopeDispose, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import favicon from '../../assets/platform-favicon.svg?no-inline';
export const useThemeStore = defineStore('theme', () => {
  const media = matchMedia('(prefers-color-scheme: dark)');
  const systemDark = ref(media.matches);
  const themeScheme = ref<'light' | 'dark' | 'auto'>('auto');
  try {
    const saved = localStorage.getItem('sf:ui:theme');
    if (saved === 'light' || saved === 'dark' || saved === 'auto') themeScheme.value = saved;
  } catch {
    /* 本页主题仍可用。 */
  }
  const darkMode = computed(() =>
    themeScheme.value === 'auto' ? systemDark.value : themeScheme.value === 'dark',
  );
  const changed = () => {
    systemDark.value = media.matches;
  };
  media.addEventListener('change', changed);
  onScopeDispose(() => media.removeEventListener('change', changed));
  function toggleThemeScheme() {
    setThemeScheme(darkMode.value ? 'light' : 'dark');
  }
  function setThemeScheme(value: 'light' | 'dark' | 'auto') {
    themeScheme.value = value;
    try {
      localStorage.setItem('sf:ui:theme', value);
    } catch {
      /* 不影响本页切换。 */
    }
  }
  watch(
    darkMode,
    (value) => {
      document.documentElement.classList.toggle('dark', value);
      document.documentElement.dataset.colorScheme = value ? 'dark' : 'light';
      document.documentElement.dataset.brand = 'platform';
    },
    { immediate: true },
  );
  document.title = 'SaaS Forge Platform Console';
  let icon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]');
  if (!icon) {
    icon = document.createElement('link');
    icon.rel = 'icon';
    document.head.append(icon);
  }
  icon.href = favicon;
  return {
    darkMode,
    themeScheme,
    toggleThemeScheme,
    setThemeScheme,
    themeColor: '#2563EB',
    header: { height: 56 },
    sider: { width: 220, collapsedWidth: 64 },
    tab: { height: 44 },
    footer: { height: 48 },
  };
});
