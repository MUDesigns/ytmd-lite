export type PaletteCommand = {
  id: string;
  label: string;
  detail?: string;
  run: () => unknown | Promise<unknown>;
};
