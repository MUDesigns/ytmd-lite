<script lang="ts">
  import "../app.css";
  import "../code-theme.css";

  let { children } = $props();
</script>

<div class="root">
  {@render children()}
</div>

<style>
  .root {
    width: 100%;
    height: 100%;
    min-height: 100%;
    display: flex;
    flex-direction: column;
  }
  .root > :global(*) {
    flex: 1 1 auto;
    min-height: 0;
    width: 100%;
  }
</style>
