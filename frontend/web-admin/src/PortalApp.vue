<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <RouterView v-slot="{ Component, route }">
    <component
      :is="Component"
      v-if="route.name === 'dev-console'"
    />
    <component
      :is="Component"
      v-else
      @navigate="router.push($event)"
    />
  </RouterView>
</template>
