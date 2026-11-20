<script lang="ts">
  import { onMount } from 'svelte';

  let currentYear = $state(new Date().getFullYear());

  onMount(() => {
    // The prerendered year is only a fallback until hydration completes.
    currentYear = new Date().getFullYear();
  });
</script>

<footer class="border-t border-neutral-200 dark:border-neutral-800">
  <div class="container py-8">
    <p class="text-center text-sm text-neutral-500 dark:text-neutral-400">
      &copy; {currentYear} Papyrus
    </p>
  </div>
</footer>
