const UPSERT_BOOK_AUTHORS_SQL = "CALL public.upsert_book_authors($1::uuid, $2::text[])";

/**
 * Delegates all author normalization, ordering, and persistence policy to PostgreSQL.
 */
export async function upsertBookAuthors(client, bookId, rawAuthorNames) {
  await client.query(UPSERT_BOOK_AUTHORS_SQL, [bookId, rawAuthorNames]);
}
