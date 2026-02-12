import { z } from "zod/v4";

export const CoverIngestResponseSchema = z.object({
  bookId: z.string(),
  storedCoverUrl: z.string(),
  storageKey: z.string(),
  source: z.string(),
  width: z.number().int().positive().nullable().optional(),
  height: z.number().int().positive().nullable().optional(),
  highResolution: z.boolean().nullable().optional(),
});

export type CoverIngestResponse = z.infer<typeof CoverIngestResponseSchema>;
