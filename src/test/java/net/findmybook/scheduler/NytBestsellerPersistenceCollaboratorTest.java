package net.findmybook.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import net.findmybook.service.BookSupplementalPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class NytBestsellerPersistenceCollaboratorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BookSupplementalPersistenceService supplementalPersistenceService;

    private NytBestsellerPersistenceCollaborator collaborator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        NytBestsellerPayloadMapper payloadMapper = new NytBestsellerPayloadMapper(objectMapper);
        collaborator = new NytBestsellerPersistenceCollaborator(
            jdbcTemplate,
            supplementalPersistenceService,
            payloadMapper
        );
    }

    @Test
    void should_UseSourceExternalIdConflictTarget_When_UpsertingNytExternalIdentifiers() {
        ObjectNode bookNode = objectMapper.createObjectNode();
        bookNode.put("book_uri", "https://www.nytimes.com/books/sample-book");
        String canonicalId = "8c7d129c-fcd0-42dc-8f04-43ecb4e303f4";

        collaborator.upsertNytExternalIdentifiers(
            canonicalId,
            bookNode,
            "9781234567897",
            "1234567890"
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
            sqlCaptor.capture(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        );

        assertThat(sqlCaptor.getValue()).contains("ON CONFLICT (source, external_id)");
        assertThat(sqlCaptor.getValue()).doesNotContain("ON CONFLICT (book_id, source)");
    }

    @Test
    void should_FallbackToCanonicalId_When_IsbnAndBookUriAreMissingForNytUpsert() {
        ObjectNode bookNode = objectMapper.createObjectNode();
        String canonicalId = "10e0a7b5-0d0c-4f67-84ea-6f7ef8bcaf57";

        collaborator.upsertNytExternalIdentifiers(canonicalId, bookNode, null, null);

        ArgumentCaptor<Object> externalIdCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(
            any(),
            any(),
            any(),
            externalIdCaptor.capture(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        );

        assertThat(externalIdCaptor.getValue()).isEqualTo(canonicalId);
    }
}
