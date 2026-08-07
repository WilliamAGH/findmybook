import { describe, expect, it, vi } from "vitest";
import { upsertBookAuthors } from "./upsert-book-authors.js";

describe("upsertBookAuthors", () => {
  it("should_PassRawAuthorArrayUnchanged_When_CallingCanonicalDatabaseRoutine", async () => {
    // Arrange
    const query = vi.fn().mockResolvedValue({ rowCount: null, rows: [] });
    const client = { query };
    const bookId = "019fd3f2-9a6d-7fe2-a089-c5470d9c7254";
    const rawAuthorNames = [" Zora Neale Hurston ", "", null, "Alice Walker", "Alice Walker"];

    // Act
    await upsertBookAuthors(client, bookId, rawAuthorNames);

    // Assert
    expect(query).toHaveBeenCalledOnce();
    expect(query).toHaveBeenCalledWith(
      "CALL public.upsert_book_authors($1::uuid, $2::text[])",
      [bookId, rawAuthorNames],
    );
    expect(query.mock.calls[0][1][1]).toBe(rawAuthorNames);
  });

  it("should_PropagateDatabaseFailure_When_CanonicalRoutineRejectsInput", async () => {
    // Arrange
    const databaseFailure = new Error("author upsert failed");
    const client = { query: vi.fn().mockRejectedValue(databaseFailure) };

    // Act and assert
    await expect(upsertBookAuthors(client, "019fd3f2-9a6d-7fe2-a089-c5470d9c7254", []))
      .rejects.toBe(databaseFailure);
  });
});
