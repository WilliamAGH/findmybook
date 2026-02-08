<script lang="ts">
  /**
   * Branded affiliate link buttons for book purchase/preview.
   * Each retailer has a distinct color and icon.
   */
  import {
    ShoppingCart,
    Store,
    ShoppingBag,
    Headphones,
    ExternalLink,
  } from "@lucide/svelte";

  let { links = {}, loadFailed = false }: { links?: Record<string, string>; loadFailed?: boolean } = $props();

  type AffiliateConfig = {
    label: string;
    icon: typeof ShoppingCart;
    bgClass: string;
    hoverClass: string;
  };

  const AFFILIATE_BRAND_CONFIG: Record<string, AffiliateConfig> = {
    amazon: { label: "Amazon", icon: ShoppingCart, bgClass: "bg-canvas-400", hoverClass: "hover:bg-canvas-500" },
    barnesAndNoble: { label: "Barnes & Noble", icon: Store, bgClass: "bg-[#00563F]", hoverClass: "hover:bg-[#004832]" },
    bookshop: { label: "Bookshop.org", icon: ShoppingBag, bgClass: "bg-[#4C32C0]", hoverClass: "hover:bg-[#3D28A0]" },
    audible: { label: "Audible", icon: Headphones, bgClass: "bg-[#FF9900]", hoverClass: "hover:bg-[#E68A00]" },
  };

  function affiliateConfig(key: string): AffiliateConfig {
    return AFFILIATE_BRAND_CONFIG[key] ?? { label: key, icon: ExternalLink, bgClass: "bg-canvas-400", hoverClass: "hover:bg-canvas-500" };
  }

  function sortedEntries(): Array<[string, string]> {
    return Object.entries(links).sort(([left], [right]) => left.localeCompare(right));
  }
</script>

{#if sortedEntries().length > 0}
  <div class="space-y-3">
    <p class="text-sm font-medium text-anthracite-800 dark:text-slate-200">Buy or Preview</p>
    <div class="flex flex-wrap gap-2">
      {#each sortedEntries() as [label, url]}
        {@const config = affiliateConfig(label)}
        {@const AffIcon = config.icon}
        <a
          href={url}
          data-no-spa="true"
          target="_blank"
          rel="noopener noreferrer sponsored"
          class={`inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium text-white shadow-sm transition-all duration-200 ${config.bgClass} ${config.hoverClass} hover:shadow-canvas focus:outline-none focus:ring-2 focus:ring-canvas-500 focus:ring-offset-2`}
        >
          <AffIcon size={16} />
          {config.label}
        </a>
      {/each}
    </div>
  </div>
{:else if loadFailed}
  <div class="space-y-3">
    <p class="text-sm font-medium text-anthracite-800 dark:text-slate-200">Buy or Preview</p>
    <p class="text-sm text-anthracite-500 dark:text-slate-400">Unable to load purchase links.</p>
  </div>
{/if}
