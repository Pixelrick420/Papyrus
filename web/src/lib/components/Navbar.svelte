<script lang="ts">
  import { onMount } from 'svelte';
  import { cn } from '$lib/utils/cn';
  import Icon from '$lib/components/Icon.svelte';

  let scrolled = $state(false);
  let darkMode = $state(false);

  function toggleTheme() {
    if (typeof document !== 'undefined') {
      const isDark = document.documentElement.classList.toggle('dark');
      darkMode = isDark;
      localStorage.setItem('theme', isDark ? 'dark' : 'light');
    }
  }

  onMount(() => {
    if (typeof window !== 'undefined') {
      darkMode = document.documentElement.classList.contains('dark');

      window.addEventListener('scroll', handleScroll, { passive: true });
      return () => window.removeEventListener('scroll', handleScroll);
    }
  });

  function handleScroll() {
    if (typeof window !== 'undefined') {
      scrolled = window.scrollY > 20;
    }
  }
</script>

<header
  class={cn(
    'z-sticky fixed top-0 right-0 left-0 h-16 transition-all duration-200',
    'border-b border-neutral-200 bg-white/80 backdrop-blur-md',
    'dark:border-neutral-800 dark:bg-neutral-950/80',
    scrolled && 'shadow-sm'
  )}
>
  <nav class="relative h-full w-full" aria-label="Main navigation">
    <a
      href="/"
      class="absolute top-1/2 left-4 flex -translate-y-1/2 items-center sm:left-6"
      aria-label="Papyrus Home"
    >
      <span class="text-2xl font-bold text-neutral-900 dark:text-neutral-100">Papyrus</span>
    </a>

    <button
      class="absolute top-1/2 right-4 -translate-y-1/2 rounded-lg p-2 text-neutral-600 transition-colors hover:bg-neutral-100 sm:right-6 dark:text-neutral-300 dark:hover:bg-neutral-800"
      onclick={toggleTheme}
      aria-label={darkMode ? 'Switch to light mode' : 'Switch to dark mode'}
      aria-pressed={darkMode}
    >
      {#if darkMode}
        <Icon name="Sun" size={20} />
      {:else}
        <Icon name="Moon" size={20} />
      {/if}
    </button>
  </nav>
</header>
