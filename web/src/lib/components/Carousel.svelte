<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import { cn } from '$lib/utils/cn';
  import Icon from '$lib/components/Icon.svelte';

  export interface CarouselItem {
    src: string;
    alt: string;
  }

  interface Props {
    items?: CarouselItem[];
    perPage?: number;
    autoPlay?: boolean;
    autoPlayInterval?: number;
    showArrows?: boolean;
    showDots?: boolean;
    class?: string;
  }

  let {
    items = [],
    perPage = 2,
    autoPlay = false,
    autoPlayInterval = 5000,
    showArrows = true,
    showDots = true,
    class: classProp = ''
  }: Props = $props();

  let isSmScreen = $state(false);
  const effectivePerPage = $derived(isSmScreen ? perPage : 1);

  let currentIndex = $state(0);
  let isDragging = $state(false);
  let startX = 0;
  let scrollLeft = 0;
  let carouselRef = $state<HTMLDivElement | null>(null);
  let autoPlayTimer: ReturnType<typeof setInterval> | null = null;
  let prefersReducedMotion = $state(false);

  const pageCount = $derived(Math.max(1, Math.ceil(items.length / effectivePerPage)));
  const currentPage = $derived(Math.min(pageCount - 1, Math.floor(currentIndex / effectivePerPage)));

  function getSlideTargetLeft(index: number): number {
    if (!carouselRef || items.length === 0) return 0;
    const clampedIndex = Math.max(0, Math.min(index, items.length - 1));
    const el1 = carouselRef.children[clampedIndex] as HTMLElement | undefined;
    if (!el1) return 0;

    const containerWidth = carouselRef.clientWidth;

    if (effectivePerPage === 2 && clampedIndex + 1 < carouselRef.children.length) {
      const el2 = carouselRef.children[clampedIndex + 1] as HTMLElement | undefined;
      if (el2) {
        // Midpoint between el1 and el2
        const start = el1.offsetLeft;
        const end = el2.offsetLeft + el2.clientWidth;
        const midpoint = (start + end) / 2;
        return midpoint - containerWidth / 2;
      }
    }

    // 1 item in focus: center el1
    const itemCenter = el1.offsetLeft + el1.clientWidth / 2;
    return itemCenter - containerWidth / 2;
  }

  function goToSlide(index: number) {
    if (carouselRef && items.length > 0) {
      const clampedIndex = Math.max(0, Math.min(index, items.length - 1));
      currentIndex = clampedIndex;
      const targetLeft = getSlideTargetLeft(clampedIndex);
      carouselRef.scrollTo({
        left: targetLeft,
        behavior: prefersReducedMotion ? 'auto' : 'smooth'
      });
    }
  }

  function goToPage(page: number) {
    const clampedPage = Math.max(0, Math.min(page, pageCount - 1));
    goToSlide(clampedPage * effectivePerPage);
  }

  function nextSlide() {
    goToPage(currentPage + 1);
  }

  function prevSlide() {
    goToPage(currentPage - 1);
  }

  function handleMouseDown(e: MouseEvent) {
    if (!carouselRef) return;
    isDragging = true;
    startX = e.pageX - carouselRef.offsetLeft;
    scrollLeft = carouselRef.scrollLeft;
    carouselRef.style.cursor = 'grabbing';
    stopAutoPlay();
  }

  function handleMouseMove(e: MouseEvent) {
    if (!isDragging || !carouselRef) return;
    e.preventDefault();
    const x = e.pageX - carouselRef.offsetLeft;
    const walk = (x - startX) * 2;
    carouselRef.scrollLeft = scrollLeft - walk;
  }

  function handleMouseUp() {
    if (!carouselRef) return;
    isDragging = false;
    carouselRef.style.cursor = 'grab';
    snapToNearest();
    startAutoPlay();
  }

  function handleTouchStart(e: TouchEvent) {
    if (!carouselRef || !e.touches[0]) return;
    isDragging = true;
    startX = e.touches[0].pageX - carouselRef.offsetLeft;
    scrollLeft = carouselRef.scrollLeft;
    stopAutoPlay();
  }

  function handleTouchMove(e: TouchEvent) {
    if (!isDragging || !carouselRef || !e.touches[0]) return;
    const x = e.touches[0].pageX - carouselRef.offsetLeft;
    const walk = (x - startX) * 2;
    carouselRef.scrollLeft = scrollLeft - walk;
  }

  function handleTouchEnd() {
    if (!carouselRef) return;
    isDragging = false;
    snapToNearest();
    startAutoPlay();
  }

  function snapToNearest() {
    if (!carouselRef || items.length === 0) return;
    goToSlide(nearestIndex());
  }

  function nearestIndex(): number {
    if (!carouselRef || items.length === 0) return 0;
    const containerCenter = carouselRef.scrollLeft + carouselRef.clientWidth / 2;
    let nearest = 0;
    let smallestDistance = Number.POSITIVE_INFINITY;

    for (let i = 0; i < items.length; i += effectivePerPage) {
      const el1 = carouselRef.children[i] as HTMLElement | undefined;
      if (!el1) continue;

      let slideCenter: number;
      if (effectivePerPage === 2 && i + 1 < carouselRef.children.length) {
        const el2 = carouselRef.children[i + 1] as HTMLElement | undefined;
        if (el2) {
          slideCenter = (el1.offsetLeft + el2.offsetLeft + el2.clientWidth) / 2;
        } else {
          slideCenter = el1.offsetLeft + el1.clientWidth / 2;
        }
      } else {
        slideCenter = el1.offsetLeft + el1.clientWidth / 2;
      }

      const distance = Math.abs(slideCenter - containerCenter);
      if (distance < smallestDistance) {
        smallestDistance = distance;
        nearest = i;
      }
    }
    return nearest;
  }

  function startAutoPlay() {
    if (autoPlay && items.length > 1 && !prefersReducedMotion) {
      autoPlayTimer = setInterval(nextSlide, autoPlayInterval);
    }
  }

  function stopAutoPlay() {
    if (autoPlayTimer) {
      clearInterval(autoPlayTimer);
      autoPlayTimer = null;
    }
  }

  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      prevSlide();
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      nextSlide();
    }
  }

  onMount(() => {
    if (typeof window !== 'undefined') {
      const smQuery = window.matchMedia('(min-width: 640px)');
      isSmScreen = smQuery.matches;

      const handleSmChange = (e: MediaQueryListEvent) => {
        isSmScreen = e.matches;
      };

      smQuery.addEventListener('change', handleSmChange);

      const mediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
      prefersReducedMotion = mediaQuery.matches;

      const handleChange = (e: MediaQueryListEvent) => {
        prefersReducedMotion = e.matches;
        if (prefersReducedMotion) stopAutoPlay();
        else startAutoPlay();
      };

      mediaQuery.addEventListener('change', handleChange);
      startAutoPlay();

      return () => {
        smQuery.removeEventListener('change', handleSmChange);
        mediaQuery.removeEventListener('change', handleChange);
        stopAutoPlay();
      };
    }
  });

  onDestroy(() => {
    stopAutoPlay();
  });

  function handleScroll() {
    if (!carouselRef || items.length === 0) return;
    const index = nearestIndex();
    if (index !== currentIndex && index >= 0 && index < items.length) {
      currentIndex = index;
    }
  }
</script>

<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div class={cn('relative', classProp)} onkeydown={handleKeyDown} role="region" aria-label="Screenshots carousel">
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div
    bind:this={carouselRef}
    class="carousel-snap flex gap-4 overflow-x-auto pb-4 scrollbar-hide px-[10%] sm:px-0"
    onscroll={handleScroll}
    onmousedown={handleMouseDown}
    onmousemove={handleMouseMove}
    onmouseup={handleMouseUp}
    onmouseleave={handleMouseUp}
    ontouchstart={handleTouchStart}
    ontouchmove={handleTouchMove}
    ontouchend={handleTouchEnd}
  >
    {#each items as item, index}
      <div class="carousel-item w-[calc(80%-0.5rem)] sm:w-[calc(50%-0.5rem)] shrink-0">
        <article class="overflow-hidden rounded-xl border border-neutral-200 bg-neutral-100 shadow-sm transition-shadow hover:shadow-lg dark:border-neutral-800 dark:bg-neutral-900">
          <img
            src={item.src}
            alt={item.alt}
            class="h-auto w-full object-contain"
            loading={index < 3 ? 'eager' : 'lazy'}
            decoding="async"
          />
        </article>
      </div>
    {/each}
  </div>

  {#if showArrows && pageCount > 1}
    <button
      class="absolute left-0 top-1/2 -translate-y-1/2 -translate-x-3 md:-translate-x-4 z-10 p-2 rounded-full bg-white/90 dark:bg-neutral-800/90 shadow-lg text-neutral-700 dark:text-neutral-200 hover:bg-white dark:hover:bg-neutral-700 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-white/90 dark:disabled:hover:bg-neutral-800/90 transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-500"
      onclick={prevSlide}
      disabled={currentPage === 0}
      aria-label="Previous screenshot"
    >
      <Icon name="ChevronLeft" size={20} />
    </button>
    <button
      class="absolute right-0 top-1/2 -translate-y-1/2 translate-x-3 md:translate-x-4 z-10 p-2 rounded-full bg-white/90 dark:bg-neutral-800/90 shadow-lg text-neutral-700 dark:text-neutral-200 hover:bg-white dark:hover:bg-neutral-700 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-white/90 dark:disabled:hover:bg-neutral-800/90 transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-500"
      onclick={nextSlide}
      disabled={currentPage === pageCount - 1}
      aria-label="Next screenshot"
    >
      <Icon name="ChevronRight" size={20} />
    </button>
  {/if}

  {#if showDots && pageCount > 1}
    <div class="flex justify-center gap-2 mt-6" role="tablist" aria-label="Screenshot pages">
      {#each Array(pageCount) as _, page}
        <button
          class={cn(
            'w-2 h-2 rounded-full transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-500',
            page === currentPage
              ? 'bg-primary-600 w-6'
              : 'bg-neutral-300 dark:bg-neutral-600 hover:bg-neutral-400 dark:hover:bg-neutral-500'
          )}
          onclick={() => goToPage(page)}
          role="tab"
          aria-selected={page === currentPage}
          aria-label={`Go to screenshot ${page * effectivePerPage + 1}${effectivePerPage > 1 ? ` to ${Math.min((page + 1) * effectivePerPage, items.length)}` : ''}`}
        ></button>
      {/each}
    </div>
  {/if}

  <style>
    .scrollbar-hide {
      -ms-overflow-style: none;
      scrollbar-width: none;
    }
    .scrollbar-hide::-webkit-scrollbar {
      display: none;
    }
    .carousel-item {
      scroll-snap-align: center;
    }
    @media (min-width: 640px) {
      .carousel-item {
        scroll-snap-align: start;
      }
    }
  </style>
</div>
