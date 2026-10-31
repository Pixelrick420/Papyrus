<script lang="ts">
  import Carousel from '$lib/components/Carousel.svelte';

  const screenshots = [
    {
      src: '/screenshots/1-library.webp',
      alt: 'The Papyrus library screen with search, sort and filter, and starred files'
    },
    {
      src: '/screenshots/2-pdf-viewer.webp',
      alt: 'A PDF open in the viewer with zoom, links, outline and go to page'
    },
    {
      src: '/screenshots/3-epub-reader.webp',
      alt: 'An EPUB book open as a reflowed page with its outline'
    },
    {
      src: '/screenshots/4-tts-controls.webp',
      alt: 'Read aloud controls with sentence and word highlighting'
    },
    {
      src: '/screenshots/5-annotations.webp',
      alt: 'PDF text highlighted with a note attached'
    },
    {
      src: '/screenshots/6-search.webp',
      alt: 'Find in file with the current match highlighted'
    },
    {
      src: '/screenshots/7-settings.webp',
      alt: 'The Papyrus settings screen'
    },
    {
      src: '/screenshots/8-office-viewer.webp',
      alt: 'A Word document open with headings, lists, tables and images'
    }
  ];
</script>

<section id="screenshots" class="section" aria-labelledby="screenshots-heading">
  <div class="container text-center">
    <header class="mx-auto mb-16 max-w-3xl animate-fade-in-up">
      <span class="mb-4 inline-block rounded-full bg-primary-50 px-3 py-1 text-sm font-medium text-primary-600 dark:bg-primary-400/10 dark:text-primary-300">
        Screenshots
      </span>
      <h2 id="screenshots-heading" class="h2 mb-4">See Papyrus in Action</h2>
    </header>

    <Carousel
      items={screenshots}
      autoPlay={false}
      showArrows={true}
      showDots={true}
      class="animate-fade-in-up delay-1 mx-auto max-w-[614px]"
    />
  </div>
</section>
