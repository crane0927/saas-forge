<script setup lang="ts">
import { useConsole } from '@saas-forge/admin';
import Remote from '../../business-remotes/admin-consumer-fixture/src/Remote.vue';
const { locale } = useConsole();
</script>
<template><Remote :locale="locale" /></template>
