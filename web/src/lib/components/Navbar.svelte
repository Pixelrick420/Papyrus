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
      const stored = localStorage.getItem('theme');
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      const shouldBeDark = stored === 'dark' || (!stored && prefersDark);
      document.documentElement.classList.toggle('dark', shouldBeDark);
      darkMode = shouldBeDark;

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
    'fixed top-0 left-0 right-0 z-sticky transition-all duration-200 h-16',
    'bg-white/80 backdrop-blur-md border-b border-neutral-200',
    'dark:bg-neutral-950/80 dark:border-neutral-800',
    scrolled && 'shadow-sm'
  )}
>
  <nav class="relative h-full w-full" aria-label="Main navigation">
    <a
      href="/"
      class="absolute left-4 sm:left-6 top-1/2 -translate-y-1/2 flex items-center"
      aria-label="Papyrus Home"
    >
      <span class="text-2xl font-bold text-neutral-900 dark:text-neutral-100">Papyrus</span>
    </a>

    <button
      class="absolute right-4 sm:right-6 top-1/2 -translate-y-1/2 p-2 rounded-lg text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-neutral-800 transition-colors"
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
