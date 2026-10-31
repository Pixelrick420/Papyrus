<script lang="ts">
  import type { Snippet } from 'svelte';
  import Navbar from '$lib/components/Navbar.svelte';
  import Footer from '$lib/components/Footer.svelte';
  import '../app.css';

  let { children }: { children?: Snippet } = $props();
</script>

<!-- Skip to main content link for accessibility -->
<a href="#main-content" class="skip-link">
  Skip to main content
</a>

<div class="flex min-h-screen flex-col">
  <Navbar />

  <main id="main-content" class="flex-1">
    {#if children}
      {@render children()}
    {/if}
  </main>

  <Footer />
</div>
