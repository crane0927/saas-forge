<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElButton } from 'element-plus';
const selection = ref<'v1' | 'v2'>();
const status = ref('Select a version');
const container = ref<HTMLDivElement>();
const setStatus = (value: string) => {
  status.value = value;
};
watch(selection, (selected, _, cleanup) => {
  if (!selected || !container.value) return;
  const version = selected;
  const target = container.value!;
  let active = true;
  const remoteHost = window.location.hostname.replace(/^console\./u, 'remote.');
  const base = `https://${remoteHost}/static-acceptance/${version}/`;
  const stylesheet = document.createElement('link');
  stylesheet.rel = 'stylesheet';
  stylesheet.crossOrigin = 'anonymous';
  stylesheet.href = `${base}styles.css`;
  const image = new Image();
  image.crossOrigin = 'anonymous';
  image.alt = `Remote ${version} sample`;
  const styled = new Promise<void>((resolve, reject) => {
    stylesheet.onload = () => {
      resolve();
    };
    stylesheet.onerror = () => {
      reject(new Error('Stylesheet unavailable'));
    };
  });
  document.head.append(stylesheet);
  image.src = `${base}image.svg`;
  // 跨 Origin ES Module 的默认 same-origin 模式不附带目标域凭据；
  // CSS/图片显式 anonymous。这里只读取固定静态资源，不绕过共享业务 HTTP Client。
  void Promise.all([
    import(/* @vite-ignore */ `${base}remote.js`) as Promise<{
      mount: (target: HTMLElement) => void;
    }>,
    styled,
    image.decode(),
  ])
    .then(([module]) => {
      if (!active) return;
      module.mount(target);
      target.append(image);
      setStatus(`${version} ready`);
    })
    .catch(() => {
      if (active) setStatus(`${version} failed; check trusted HTTPS and built artifacts`);
    });
  cleanup(() => {
    active = false;
    stylesheet.remove();
    target.replaceChildren();
  });
});
</script>
<template>
  <main>
    <h1>Static Remote acceptance</h1>
    <div class="console-actions">
      <ElButton
        v-for="version in ['v1', 'v2'] as const"
        :key="version"
        :disabled="status.endsWith('loading')"
        @click="
          status = version + ' loading';
          selection = version;
        "
        >Load {{ version }}</ElButton
      >
    </div>
    <p role="status">{{ status }}</p>
    <div ref="container" />
  </main>
</template>
