<script lang="ts">
  import Card from '$lib/components/Card.svelte';
  import Icon from '$lib/components/Icon.svelte';

  const features = [
    {
      icon: 'FileText',
      title: 'Every format',
      description: 'PDF, Word, ODT, EPUB, MD, Text, CSV and Code',
    },
    {
      icon: 'Volume2',
      title: 'Read aloud',
      description: 'Text-to-speech in every format.',
    },
    {
      icon: 'Search',
      title: 'Search',
      description: 'Within every file and globally.',
    },
    {
      icon: 'SquarePen',
      title: 'Highlights',
      description: 'PDF highlight and bookmark support',
    },
    {
      icon: 'SunMoon',
      title: 'Themes',
      description: 'Light, Dark, Colourblind and night modes.',
    },
    {
      icon: 'WifiOff',
      title: 'Private and offline',
      description: 'Nothing leaves your device.',
    },
  ];
</script>

<section
  id="features"
  class="section bg-neutral-50 dark:bg-neutral-900/40"
  aria-labelledby="features-heading"
>
  <div class="container text-center">
    <header class="animate-fade-in-up mx-auto mb-16 max-w-3xl">
      <span
        class="bg-primary-50 text-primary-600 dark:bg-primary-400/10 dark:text-primary-300 mb-4 inline-block rounded-full px-3 py-1 text-sm font-medium"
      >
        Features
      </span>
      <h2 id="features-heading" class="h2 mb-4">Everything You Need</h2>
    </header>

    <div class="grid grid-cols-1 justify-center gap-6 md:grid-cols-2 lg:grid-cols-3" role="list">
      {#each features as feature (feature.title)}
        <Card variant="feature" class="animate-fade-in-up text-center" role="listitem">
          <div
            class="bg-primary-50 dark:bg-primary-400/10 mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-xl"
            aria-hidden="true"
          >
            <Icon name={feature.icon} size={24} class="text-primary-600 dark:text-primary-300" />
          </div>
          <h3 class="h4 mb-2 text-center">{feature.title}</h3>
          <p class="text-body-sm text-center">{feature.description}</p>
        </Card>
      {/each}
    </div>
  </div>
</section>
