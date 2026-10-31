<script lang="ts">
  import type { Snippet } from 'svelte';
  import { cn } from '$lib/utils/cn';

  type CardVariant = 'default' | 'feature' | 'screenshot';

  interface Props {
    variant?: CardVariant;
    hover?: boolean;
    class?: string;
    padded?: boolean;
    role?: string;
    children?: Snippet;
    [key: string]: unknown;
  }

  let {
    variant = 'default',
    hover = true,
    class: classProp = '',
    padded = true,
    role,
    children,
    ...rest
  }: Props = $props();

  const variantClasses: Record<CardVariant, string> = {
    default:
      'rounded-2xl bg-white shadow-sm transition-all duration-200 dark:bg-neutral-900 dark:border dark:border-neutral-800',
    feature:
      'rounded-2xl bg-white p-6 shadow-sm transition-all duration-200 dark:bg-neutral-900 dark:border dark:border-neutral-800',
    screenshot:
      'rounded-xl bg-neutral-100 overflow-hidden transition-all duration-200 dark:bg-neutral-800',
  };

  const hoverClasses: Record<CardVariant, string> = {
    default: 'hover:shadow-lg hover:-translate-y-1',
    feature: 'hover:shadow-lg',
    screenshot: 'hover:shadow-xl hover:-translate-y-1',
  };

  const paddingClasses: Record<CardVariant, string> = {
    default: '',
    feature: '',
    screenshot: 'p-0',
  };
</script>

<div
  class={cn(
    variantClasses[variant],
    hover && hoverClasses[variant],
    padded && paddingClasses[variant],
    classProp
  )}
  {role}
  {...rest}
>
  {#if children}
    {@render children()}
  {/if}
</div>
