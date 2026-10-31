<script lang="ts">
  import {
    ChevronLeft,
    ChevronRight,
    Download,
    ExternalLink,
    EyeOff,
    FileText,
    Moon,
    Search,
    Shield,
    SquarePen,
    Sun,
    SunMoon,
    UserX,
    Volume2,
    WifiOff
  } from '@lucide/svelte';
  import type { Component } from 'svelte';

  const iconMap: Record<string, Component<Record<string, unknown>>> = {
    ChevronLeft,
    ChevronRight,
    Download,
    ExternalLink,
    EyeOff,
    FileText,
    Moon,
    Search,
    Shield,
    SquarePen,
    Sun,
    SunMoon,
    UserX,
    Volume2,
    WifiOff
  };

  interface Props {
    name?: string;
    size?: number | string;
    class?: string;
    strokeWidth?: number | string;
    ariaLabel?: string;
    'aria-hidden'?: boolean | 'true' | 'false';
    [key: string]: unknown;
  }

  let {
    name = 'FileText',
    size = 24,
    class: classProp = '',
    strokeWidth = 2,
    ariaLabel = '',
    'aria-hidden': ariaHidden = 'true',
    ...rest
  }: Props = $props();

  const IconComponent = $derived(iconMap[name] || FileText);
</script>

<IconComponent
  {size}
  {strokeWidth}
  class={classProp}
  aria-label={ariaLabel}
  aria-hidden={ariaHidden}
  {...rest}
/>
