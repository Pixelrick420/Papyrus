<script lang="ts">
  import type { Snippet } from 'svelte';
  import { cn } from '$lib/utils/cn';
  import Icon from '$lib/components/Icon.svelte';

  type ButtonVariant = 'primary' | 'secondary' | 'ghost';
  type ButtonSize = 'sm' | 'md' | 'lg';
  type ButtonType = 'button' | 'submit' | 'reset';

  interface Props {
    variant?: ButtonVariant;
    size?: ButtonSize;
    loading?: boolean;
    disabled?: boolean;
    fullWidth?: boolean;
    type?: ButtonType;
    href?: string;
    target?: string;
    rel?: string;
    leftIcon?: string;
    rightIcon?: string;
    class?: string;
    'aria-label'?: string;
    'aria-describedby'?: string;
    children?: Snippet;
    [key: string]: unknown;
  }

  let {
    variant = 'primary',
    size = 'md',
    loading = false,
    disabled = false,
    fullWidth = false,
    type = 'button',
    href,
    target,
    rel,
    leftIcon,
    rightIcon,
    class: classProp,
    'aria-label': ariaLabel,
    'aria-describedby': ariaDescribedby,
    children,
    ...rest
  }: Props = $props();
</script>

{#if href}
  <a
    {href}
    {target}
    {rel}
    class={cn(
      'inline-flex items-center justify-center gap-2 font-medium transition-all duration-150 focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none disabled:pointer-events-none disabled:opacity-50',
      variant === 'primary'
        ? 'bg-primary-600 hover:bg-primary-700 focus-visible:ring-primary-500 dark:bg-primary-300 dark:text-primary-950 dark:hover:bg-primary-200 dark:focus-visible:ring-primary-300 text-white'
        : variant === 'secondary'
          ? 'bg-neutral-100 text-neutral-900 hover:bg-neutral-200 focus-visible:ring-neutral-500 dark:bg-neutral-800 dark:text-neutral-100 dark:hover:bg-neutral-700'
          : 'bg-transparent text-neutral-700 hover:bg-neutral-100 focus-visible:ring-neutral-500 dark:text-neutral-300 dark:hover:bg-neutral-800',
      size === 'sm'
        ? 'rounded-full px-4 py-2 text-xs'
        : size === 'lg'
          ? 'rounded-full px-8 py-4 text-base'
          : 'rounded-full px-6 py-3 text-sm',
      fullWidth ? 'w-full' : '',
      classProp
    )}
    aria-label={ariaLabel}
    aria-describedby={ariaDescribedby}
    aria-disabled={disabled || loading}
    tabindex={disabled || loading ? -1 : 0}
    {...rest}
  >
    {#if loading}
      <svg class="h-4 w-4 animate-spin" viewBox="0 0 24 24" aria-hidden="true">
        <circle
          class="opacity-25"
          cx="12"
          cy="12"
          r="10"
          stroke="currentColor"
          stroke-width="4"
          fill="none"
        />
        <path
          class="opacity-75"
          fill="currentColor"
          d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
        />
      </svg>
    {:else if leftIcon}
      <Icon name={leftIcon} size={16} />
    {/if}

    {#if children}
      {@render children()}
    {/if}

    {#if rightIcon && !loading}
      <Icon name={rightIcon} size={16} />
    {/if}
  </a>
{:else}
  <button
    {type}
    {disabled}
    aria-disabled={disabled || loading}
    aria-label={ariaLabel}
    aria-describedby={ariaDescribedby}
    class={cn(
      'inline-flex items-center justify-center gap-2 font-medium transition-all duration-150 focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none disabled:pointer-events-none disabled:opacity-50',
      variant === 'primary'
        ? 'bg-primary-600 hover:bg-primary-700 focus-visible:ring-primary-500 dark:bg-primary-300 dark:text-primary-950 dark:hover:bg-primary-200 dark:focus-visible:ring-primary-300 text-white'
        : variant === 'secondary'
          ? 'bg-neutral-100 text-neutral-900 hover:bg-neutral-200 focus-visible:ring-neutral-500 dark:bg-neutral-800 dark:text-neutral-100 dark:hover:bg-neutral-700'
          : 'bg-transparent text-neutral-700 hover:bg-neutral-100 focus-visible:ring-neutral-500 dark:text-neutral-300 dark:hover:bg-neutral-800',
      size === 'sm'
        ? 'rounded-full px-4 py-2 text-xs'
        : size === 'lg'
          ? 'rounded-full px-8 py-4 text-base'
          : 'rounded-full px-6 py-3 text-sm',
      fullWidth ? 'w-full' : '',
      classProp
    )}
    {...rest}
  >
    {#if loading}
      <svg class="h-4 w-4 animate-spin" viewBox="0 0 24 24" aria-hidden="true">
        <circle
          class="opacity-25"
          cx="12"
          cy="12"
          r="10"
          stroke="currentColor"
          stroke-width="4"
          fill="none"
        />
        <path
          class="opacity-75"
          fill="currentColor"
          d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
        />
      </svg>
    {:else if leftIcon}
      <Icon name={leftIcon} size={16} />
    {/if}

    {#if children}
      {@render children()}
    {/if}

    {#if rightIcon && !loading}
      <Icon name={rightIcon} size={16} />
    {/if}
  </button>
{/if}
