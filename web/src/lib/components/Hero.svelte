<script lang="ts">
  import Button from '$lib/components/Button.svelte';
  import Icon from '$lib/components/Icon.svelte';

  const releasesUrl = 'https://github.com/Pixelrick420/Papyrus/releases/latest';

  const promises = [
    { icon: 'WifiOff', label: 'No internet permission' },
    { icon: 'Shield', label: 'No ads' },
    { icon: 'UserX', label: 'No accounts' },
    { icon: 'EyeOff', label: 'No analytics' }
  ];
</script>

<section
  class="relative flex min-h-svh items-center justify-center overflow-hidden pt-16"
  aria-labelledby="hero-heading"
>
  <div class="container relative z-10 py-16 lg:py-24">
    <div class="mx-auto max-w-3xl text-center animate-fade-in-up">
      <svg
        class="mx-auto mb-10 h-44 w-44 text-neutral-900 sm:h-56 sm:w-56 lg:h-64 lg:w-64 dark:text-neutral-100"
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 108 108"
        aria-hidden="true"
        focusable="false"
      >
        <g
          transform="translate(54 54) scale(2.5) translate(-54 -54)"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
        >
          <path
            stroke-opacity="1"
            d="M41.06,37.125L48.94,37.125M50.06,40.5L65.81,40.5M42.19,43.875L48.94,43.875M41.06,47.25L44.44,47.25M55.69,50.625L64.69,50.625M39.94,57.375L53.44,57.375M43.31,70.875L50.06,70.875"
          />
          <path
            stroke-opacity="0.8"
            d="M53.44,37.125L62.44,37.125M59.44,43.875L64.69,43.875M41.06,54L47.81,54M41.06,64.125L51.19,64.125"
          />
          <path
            stroke-opacity="0.7"
            d="M39.94,40.5L46.69,40.5M53.44,43.875L55.69,43.875M47.81,47.25L68.06,47.25M39.94,50.625L50.06,50.625M52.31,54L61.31,54M42.19,60.75L48.94,60.75M39.94,67.5L45.56,67.5"
          />
        </g>
      </svg>

      <h1 id="hero-heading" class="h1 mb-6">
        Papyrus
      </h1>

      <p class="text-body mb-10 mx-auto max-w-2xl">
        Offline document viewer for Android: PDF, Word, OpenDocument, EPUB, Markdown, text and code.
      </p>

      <div class="mb-10 flex flex-col items-center justify-center gap-4 sm:flex-row">
        <Button size="lg" class="w-full sm:w-auto" href={releasesUrl} target="_blank" rel="noopener noreferrer" aria-label="Download Papyrus for Android">
          <Icon name="Download" size={20} aria-hidden="true" />
          Download for Android
        </Button>
        <Button variant="secondary" size="lg" class="w-full sm:w-auto" href="https://github.com/Pixelrick420/Papyrus" target="_blank" rel="noopener noreferrer" aria-label="View Papyrus source on GitHub">
          <Icon name="ExternalLink" size={20} aria-hidden="true" />
          View on GitHub
        </Button>
      </div>

      <div class="flex flex-wrap items-center justify-center gap-x-8 gap-y-3 text-sm text-neutral-500 dark:text-neutral-400">
        {#each promises as promise}
          <span class="flex items-center gap-2">
            <Icon name={promise.icon} size={16} aria-hidden="true" class="text-primary-600 dark:text-primary-300" />
            <span>{promise.label}</span>
          </span>
        {/each}
      </div>
    </div>
  </div>
</section>
