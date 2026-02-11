<script lang="ts">
  import BookCard from "$lib/components/BookCard.svelte";
  import SearchPagination from "$lib/components/SearchPagination.svelte";
  import type { BookCardDisplay } from "$lib/components/BookCard.svelte";
  import type { SearchHit, SearchResponse } from "$lib/validation/schemas";

  let {
    searchResult,
    currentPage,
    pageSize,
    viewMode,
    mapHitToCard,
    bookDetailHref,
    onNavigate,
  }: {
    searchResult: SearchResponse;
    currentPage: number;
    pageSize: number;
    viewMode: "grid" | "list";
    mapHitToCard: (hit: SearchHit) => BookCardDisplay;
    bookDetailHref: (hit: SearchHit) => string;
    onNavigate: (page: number) => void;
  } = $props();
</script>

<div class={viewMode === "grid" ? "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4" : "grid grid-cols-1 gap-4"}>
  {#each searchResult.results as hit (hit.id)}
    <BookCard layout={viewMode} book={mapHitToCard(hit)} href={bookDetailHref(hit)} />
  {/each}
</div>

<SearchPagination
  {currentPage}
  totalResults={searchResult.totalResults}
  hasMore={searchResult.hasMore}
  {pageSize}
  onnavigate={onNavigate}
/>
