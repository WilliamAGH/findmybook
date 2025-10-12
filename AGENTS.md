# Agent Operations Manual

## 🚨 CRITICAL MANDATES

**ALL markdown files MUST BE CREATED IN tmp/ UNLESS EXPLICITLY REQUESTED BY THE USER, AND MUST BE DELETED AFTER THEY'RE NO LONGER REQUIRED/COMPLETED.**

### Technology Stack Requirements

- **Framework**: Spring Boot 3.3.x with Java 21+ idioms
- **Styling**: Tailwind CSS (utility-first, no custom CSS unless absolutely necessary)
- **Database**: MySQL (primary schema) and PostgreSQL (see memories for schema differences)
- **Code Philosophy**: DRY (Don't Repeat Yourself), light, lean, modern - no unnecessary boilerplate

### Development Environment

- **Default Port**: `8095` (configurable via `SERVER_PORT` environment variable)
- **Local Access**: `http://localhost:8095`
- **Start Command**: `mvn spring-boot:run -P dev`
- **Production Site**: `https://findmybook.net`

### Absolute Prohibitions

- ❌ **NEVER** use Flyway, Liquibase, or any automated migration tools
- ❌ **NEVER** enable automatic migrations on boot
- ❌ **NEVER** use `@SuppressWarnings` annotations (fix the underlying issue)
- ❌ **NEVER** use CSS `!important` declarations
- ❌ **NEVER** create inline styles in templates
- ❌ **NEVER** exceed 500 lines per file (split immediately at ~400 lines)
- ❌ **NEVER** create new files without explicit user permission

### Migration & Database Rules

- Manual SQL queries ONLY, executed by the agent
- Temporary `.sql` migration files are allowed for planning but NEVER executed by LLMs
- All database schema changes must reference immutable MySQL and PostgreSQL schema definitions [[memory:2639761]]

## 1. Purpose & Scope

- Provide a single, current reference for anyone (human or AI) working in this repository.
- Eliminate ambiguity by defining how work is planned, executed, documented, and verified.

## 2. Actors & Responsibility

- **User**: Defines requirements, prioritises work, approves changes, and ultimately owns all outcomes.
- **AI Agent**: Executes only the work the User authorises and keeps all artefacts aligned with this manual.

## 3. Core Operating Principles

1. **User-Directed Tasks**: Treat the User's request as the source of truth. Capture additional context only when the User asks for it.
2. **Scope Confirmation**: Clarify uncertainties before modifying code. If requirements shift mid-task, confirm the new scope with the User.
3. **Traceability**: Reference the current request in code comments, commits, and conversation summaries so decisions stay attributable to the User.
4. **Minimal Bureaucracy**: Backlog and task files are optional. Maintain or create documentation only when the User explicitly requests it.
5. **Single Source of Truth**: Keep information in one place. If you create notes or docs, link to them instead of duplicating.
6. **Controlled File Creation**: Create new files (including documentation) only when the User explicitly approves the specific file.
7. **Named Constants**: Replace repeated literal values with descriptive constants in generated code.
8. **Sense Check Data**: Validate data, requirements, and results for consistency before acting on them.
9. **Zero Assumptions**: ALWAYS verify library APIs, framework patterns, and dependency versions before implementing. Never rely on memory or outdated patterns.
10. **Comprehensive Updates**: When changing any code, find and update ALL usages throughout the entire codebase. Partial updates = broken features.

## 4. Workflow & Scope Control

- Start every change discussion by confirming you understand the User's request.
- Keep the conversation focused on the agreed scope; call out scope creep as soon as you see it.
- If you discover adjacent issues, surface them as optional follow-up ideas rather than expanding the active task.
- When access or tooling limitations appear, pause and ask the User how to proceed instead of guessing.

## 5. Work Tracking

- Dedicated backlog/task artifacts are not required for day-to-day work.
- When the User asks for work tracking, follow their preferred format (e.g., ad-hoc checklist, markdown file, issue link).
- If historical docs already exist for a feature, update them only when you touch the feature and the User confirms they still matter.

## 6. Testing Strategy

- Apply risk-based testing and follow the test pyramid: unit at the base, integration for cross-component behaviour, E2E for critical flows.
- Provide a lightweight test plan in your response when implementing code. Scale detail with risk.
- Prefer automated tests; document any manual verification you perform or that remains outstanding.

### Testing Requirements

1. **Unit Tests** (Base Layer)
   - Test business logic in isolation
   - Mock external dependencies using Mockito
   - Clear, descriptive test names following `should_ExpectedBehavior_When_StateUnderTest` pattern
   - Arrange-Act-Assert structure
   - Cover edge cases and error scenarios

2. **Integration Tests** (Middle Layer)
   - Test component interactions (Service + Repository)
   - Use `@SpringBootTest` or `@DataJpaTest` appropriately
   - Test actual database interactions where relevant
   - Cover critical business flows

3. **E2E Tests** (Top Layer - Minimal)
   - Cover main user journeys only
   - Test across realistic scenarios
   - Include error scenarios and edge cases

### Test Organization

- Test files mirror source structure: `src/test/java/com/williamcallahan/...`
- Test fixtures in `src/test/resources/fixtures/`
- Mock responses in `src/test/resources/mock-responses/`
- One test class per production class (e.g., `BookService.java` → `BookServiceTest.java`)

## 7. Change Management

- Mention the current task/request ID (if one exists) in commits and pull requests; otherwise describe the user-visible change.
- Summaries should highlight impacted components, testing performed, and follow-up considerations.
- Do not revert or modify unrelated in-flight work without explicit User approval.

### 7.1 Git Operations & Safety

**CRITICAL GIT PROHIBITIONS:**

- ❌ **NEVER change git branches** without explicit User permission
- ❌ **NEVER commit changes** unless the User explicitly asks you to commit
- ❌ **NEVER update git config** (user.name, user.email, etc.)
- ❌ **NEVER run destructive/irreversible commands** without explicit User request:
  - `git push --force` (or `-f`)
  - `git reset --hard`
  - `git clean -fd`
  - `git rebase` (unless explicitly requested)
  - `git branch -D` (force delete)
- ❌ **NEVER skip git hooks** without explicit User permission:
  - `--no-verify`
  - `--no-gpg-sign`
  - `-n` (shorthand for --no-verify)
- ❌ **NEVER force push to main/master** - Warn the User if they request this

**REQUIRED BEHAVIORS:**

- Always work on the current branch unless the User explicitly instructs you to switch
- Before any branch operation (checkout, merge, rebase), confirm the User's intent
- If the User asks to commit or merge, perform the operation on the current branch unless they specify otherwise
- When the User asks to commit, use descriptive commit messages that reference the task/change
- If destructive operations are requested, explain the consequences and confirm before proceeding

## 8. CSS & Styling Guidelines (Tailwind CSS)

### 8.1 Styling Philosophy - Utility-First Approach

**PRIMARY**: Tailwind CSS utility classes
**SECONDARY**: CSS variables for theming (see `src/main/resources/static/css/variables.css`)
**LAST RESORT**: Custom CSS only when Tailwind cannot achieve the design

### 8.2 Absolute CSS Prohibitions

❌ **NEVER use `!important` declarations** - Fix specificity issues properly
❌ **NEVER use inline styles** in HTML/Thymeleaf templates - Use Tailwind classes
❌ **NEVER duplicate CSS** - Use Tailwind utilities or extract to components
❌ **NEVER use arbitrary values** without justification - Use theme values first
❌ **NEVER mix styling approaches** in the same component - Pick one strategy

### 8.3 Tailwind CSS Best Practices

**Class Ordering (Consistent Pattern):**

```html
<!-- Layout → Spacing → Sizing → Typography → Colors → States -->
<div class="flex flex-col gap-4 p-6 w-full max-w-2xl text-lg font-semibold text-gray-900 bg-white hover:bg-gray-50">
```

**Recommended Class Order:**

1. **Layout**: `flex`, `grid`, `block`, `inline`, `hidden`
2. **Positioning**: `relative`, `absolute`, `fixed`, `sticky`
3. **Spacing**: `p-*`, `m-*`, `gap-*`, `space-*`
4. **Sizing**: `w-*`, `h-*`, `min-*`, `max-*`
5. **Typography**: `text-*`, `font-*`, `leading-*`, `tracking-*`
6. **Colors**: `text-*`, `bg-*`, `border-*`
7. **Borders**: `border`, `border-*`, `rounded-*`
8. **Effects**: `shadow-*`, `opacity-*`
9. **Transitions**: `transition-*`, `duration-*`
10. **States**: `hover:*`, `focus:*`, `active:*`, `disabled:*`
11. **Responsive**: `sm:*`, `md:*`, `lg:*`, `xl:*`, `2xl:*`

**Responsive Design (Mobile-First):**

```html
<!-- Base (mobile) → sm → md → lg → xl → 2xl -->
<div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
```

**Color Consistency:**

- Use design system colors from `variables.css`
- Never hardcode color values like `#FF0000`
- Use semantic naming: `text-primary`, `bg-secondary`, `border-accent`

### 8.4 When Custom CSS is Acceptable

**Only use custom CSS for:**

1. Complex animations not possible with Tailwind
2. Print styles (`@media print`)
3. Global resets (already in `main.css`)
4. CSS variables for theming (in `variables.css`)
5. Third-party library overrides (minimize and document)

**If Custom CSS Required:**

- Add to `src/main/resources/static/css/main.css`
- Use BEM naming convention: `block__element--modifier`
- Scope styles as narrowly as possible
- Document why Tailwind couldn't be used
- Never use element selectors without class scoping

```css
/* ✅ GOOD: Scoped with BEM */
.book-card__cover--loading {
    animation: shimmer 1.5s infinite;
}

/* ❌ BAD: Global element selector */
img {
    border-radius: 8px;
}
```

### 8.5 Specificity Rules

**Problem-Solving Hierarchy:**

1. **First**: Use more specific Tailwind classes
2. **Second**: Restructure HTML to avoid conflicts
3. **Third**: Use CSS cascade (order matters)
4. **Last Resort**: Custom CSS with proper scoping
5. **NEVER**: Use `!important`

**Example - Fixing Specificity:**

```html
<!-- ❌ BAD: Using !important in custom CSS -->
<style>.override { color: red !important; }</style>

<!-- ✅ GOOD: Use more specific Tailwind class -->
<div class="[&>p]:text-red-600">
    <p>Text will be red</p>
</div>

<!-- ✅ BETTER: Restructure to avoid conflict -->
<div class="text-red-600">
    <p>Text will be red</p>
</div>
```

### 8.6 Thymeleaf + Tailwind Integration

```html
<!-- ✅ GOOD: Conditional classes with Thymeleaf -->
<div th:classappend="${book.isFeatured} ? 'bg-yellow-50 border-2 border-yellow-400' : 'bg-white border border-gray-200'">

<!-- ❌ BAD: Inline styles -->
<div th:style="${book.isFeatured} ? 'background: yellow' : ''">
```

## 9. File Size Management & Code Organization

### 9.1 File Size Limits

**MANDATORY LIMIT: 500 lines maximum per file**

**WARNING THRESHOLD: 400 lines** - Start planning refactoring

**IMMEDIATE ACTION REQUIRED when approaching 400 lines:**

- Extract utility methods to separate classes
- Split large classes into focused components
- Move constants to dedicated constant classes
- Extract inner classes to separate files (with User permission)

### 9.2 How to Split Large Files

**For Java Classes (Services, Controllers, Repositories):**

```java
// ❌ BAD: 600-line service doing everything
@Service
public class BookService {
    // Book CRUD
    // Cover image processing
    // Search functionality
    // Recommendation logic
    // Analytics
}

// ✅ GOOD: Focused services with single responsibilities
@Service
public class BookCrudService { /* Create, Read, Update, Delete */ }

@Service
public class BookSearchService { /* Search & filtering */ }

@Service
public class BookRecommendationService { /* Recommendation algorithms */ }

@Service
public class BookAnalyticsService { /* Analytics & reporting */ }
```

**For Utility Classes:**

```java
// ❌ BAD: Giant utility class
public class BookUtils {
    // ISBN validation
    // Title formatting
    // Author parsing
    // Date conversions
    // String manipulations
}

// ✅ GOOD: Focused utility classes
public class IsbnValidator { /* ISBN-specific validation */ }
public class BookTitleFormatter { /* Title formatting logic */ }
public class AuthorParser { /* Author name parsing */ }
```

**For Template Files (HTML/Thymeleaf):**

```html
<!-- ❌ BAD: 800-line monolithic template -->
<!-- book.html with everything -->

<!-- ✅ GOOD: Modular fragments -->
<!-- book.html (main structure) -->
<!-- fragments/book-header.html -->
<!-- fragments/book-details.html -->
<!-- fragments/book-reviews.html -->
<!-- fragments/book-recommendations.html -->
```

### 9.3 Circular Dependency Prevention

**PROHIBITED PATTERNS:**

- Class A imports Class B, which imports Class A
- Service layer importing from Controller layer
- Utility classes importing from Services
- Cross-domain service dependencies without clear hierarchy

**PREVENTION STRATEGIES:**

1. **Unidirectional Flow (Enforce Strict Layering):**

   ```markdown
   Controller → Service → Repository → Entity
   (Never backwards: Entity ❌→ Repository ❌→ Service ❌→ Controller)
   ```

2. **Dependency Injection:**

   ```java
   // ✅ GOOD: Interface-based dependency
   @Service
   public class BookService {
       private final BookRepository bookRepository;
       private final CoverImageService coverImageService;
       
       public BookService(BookRepository bookRepository, 
                          CoverImageService coverImageService) {
           this.bookRepository = bookRepository;
           this.coverImageService = coverImageService;
       }
   }
   ```

3. **Event-Driven for Cross-Domain:**

   ```java
   // ✅ GOOD: Use Spring Events for loose coupling
   @Service
   public class BookService {
       private final ApplicationEventPublisher eventPublisher;
       
       public void updateBook(Book book) {
           // Update logic
           eventPublisher.publishEvent(new BookUpdatedEvent(book));
       }
   }
   ```

4. **Extract Shared Logic:**

   ```java
   // ✅ GOOD: Shared utility avoids circular dependency
   // util/ImageDimensionUtils.java
   public class ImageDimensionUtils {
       public static Dimension calculateAspectRatio(int width, int height) {
           // Shared logic used by multiple services
       }
   }
   ```

**Detection:**

- Maven build will fail with circular dependency errors
- Use `mvn dependency:tree` to visualize dependencies
- Review imports carefully during code review

## 10. Comprehensive Code Update Protocol

### 10.1 The Update Verification Mandate

**ABSOLUTE REQUIREMENT:** When editing or updating ANY code, you MUST find and update ALL usages throughout the entire codebase. Missing even one usage creates inconsistencies that break features and introduce bugs.

### 10.2 Three-Phase Update Protocol

#### Phase 1: Pre-Update Planning

**BEFORE making any code changes:**

1. **Map All Usages:**

   ```bash
   # Find all imports of the class
   grep -r "import.*ClassName" src/main/java/ src/test/java/
   
   # Find all method calls
   grep -r "methodName(" src/main/java/ src/test/java/
   
   # Find all references in templates
   grep -r "objectName" src/main/resources/templates/
   ```

2. **Create Update Checklist:**

   ```java
   // TODO: Update Plan for BookService.findByIsbn()
   // [ ] BookController.java - line 45, 78
   // [ ] BookSearchService.java - line 123
   // [ ] BookRepository.java - method signature
   // [ ] BookServiceTest.java - all test methods
   // [ ] book.html - Thymeleaf calls
   ```

3. **Identify Ripple Effects:**
   - Method signature changes → Update all call sites
   - Class renames → Update imports, Spring bean references, template references
   - Return type changes → Update all consuming code
   - Parameter additions → Update all invocations

#### Phase 2: During Updates

**WHILE making changes:**

1. **Track Every Change:**

   ```java
   // CHANGE LOG:
   // ✓ Updated method signature in BookService.java
   // ✓ Updated call in BookController.java line 45
   // ✓ Updated call in BookController.java line 78
   // ✓ Updated test mocks in BookServiceTest.java
   // ⚠️ PENDING: Update BookSearchService.java
   // ⚠️ PENDING: Update Thymeleaf templates
   ```

2. **Verify Parameter Agreement:**
   - Method signatures match across all calls
   - Spring bean names are consistent
   - Template variable names align with controller model attributes
   - DTOs match between layers

3. **Check Adjacent Functionality:**
   - Related methods in the same class
   - Overloaded method variants
   - Interface implementations
   - Abstract class extensions

#### Phase 3: Post-Update Audit

**AFTER completing updates:**

1. **Comprehensive Verification:**

   ```bash
   # Compile to catch errors
   ./mvnw clean compile
   
   # Run all tests
   ./mvnw test
   
   # Check for remaining old patterns
   grep -r "oldMethodName" src/
   ```

2. **Expanded Search for Missed Updates:**
   - Check XML/YAML configuration files
   - Review application.yml for bean references
   - Scan SQL queries in resources
   - Review JavaScript files that may call endpoints

3. **Integration Verification:**
   - Controllers → Services → Repositories chain intact
   - DTOs properly mapped in all layers
   - Templates receive expected model attributes
   - API endpoints return expected response shapes

### 10.3 Common Update Failures to Prevent

**❌ CRITICAL FAILURES:**

1. **Method Signature Mismatch:**

   ```java
   // Service updated to 3 parameters
   public Book findBook(String isbn, String format, boolean includeReviews) {}
   
   // But controller still uses 2 parameters
   Book book = bookService.findBook(isbn, format); // 💥 Compilation error!
   ```

2. **Spring Bean Name Inconsistency:**

   ```java
   // Service renamed
   @Service("bookSearchService")
   public class BookSearchService {}
   
   // But old qualifier still used
   @Qualifier("bookFindService") // 💥 Bean not found!
   private BookSearchService searchService;
   ```

3. **Template-Controller Mismatch:**

   ```java
   // Controller changed attribute name
   model.addAttribute("bookDetails", book);
   
   // But template still uses old name
   <div th:text="${bookInfo.title}"></div> <!-- 💥 Property not found! -->
   ```

### 10.4 Search Commands for Finding All Usages

```bash
# 1. Find all Java class references
grep -r "ClassName" --include="*.java" src/

# 2. Find all method calls
grep -r "\.methodName(" --include="*.java" src/

# 3. Find all template references
grep -r "variableName" --include="*.html" src/main/resources/templates/

# 4. Find all configuration references
grep -r "beanName" --include="*.yml" --include="*.xml" --include="*.properties" src/main/resources/

# 5. Find all SQL references
grep -r "table_name\|column_name" --include="*.sql" src/main/resources/

# 6. Find all test mocks
grep -r "mock.*ClassName\|when.*methodName" --include="*Test.java" src/test/

# 7. Find all JavaScript/frontend references
grep -r "endpoint\|apiPath" --include="*.js" src/main/resources/static/js/
```

## 11. Java & Spring Boot Best Practices

### 11.1 Code Style

- **Java Version**: Java 21+ features preferred (records, pattern matching, sealed classes)
- **Spring Boot Version**: 3.3.x idioms and conventions
- **Dependency Injection**: Constructor injection (required), never field injection
- **Null Safety**: Use `Optional<T>` for return types that may be absent
- **Immutability**: Prefer immutable objects (records, final fields)
- **Naming**:
  - Classes: `PascalCase`
  - Methods/Variables: `camelCase`
  - Constants: `UPPER_SNAKE_CASE`
  - Packages: `lowercase.dot.notation`

### 11.2 Spring Boot Patterns

**Controller Layer:**

```java
@RestController
@RequestMapping("/api/books")
public class BookController {
    
    private final BookService bookService;
    
    // ✅ GOOD: Constructor injection
    public BookController(BookService bookService) {
        this.bookService = bookService;
    }
    
    @GetMapping("/{isbn}")
    public ResponseEntity<BookDto> getBook(@PathVariable String isbn) {
        return bookService.findByIsbn(isbn)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
```

**Service Layer:**

```java
@Service
public class BookService {
    
    private final BookRepository bookRepository;
    private final CoverImageService coverImageService;
    
    public BookService(BookRepository bookRepository,
                       CoverImageService coverImageService) {
        this.bookRepository = bookRepository;
        this.coverImageService = coverImageService;
    }
    
    @Transactional(readOnly = true)
    public Optional<Book> findByIsbn(String isbn) {
        // Business logic
    }
}
```

### 11.3 Error Handling

```java
// ✅ GOOD: Specific exception handling
@ExceptionHandler(BookNotFoundException.class)
public ResponseEntity<ErrorResponse> handleBookNotFound(BookNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse(ex.getMessage()));
}

// ❌ BAD: Generic exception swallowing
catch (Exception e) {
    // Silent failure
}
```

### 11.4 Logging

```java
@Slf4j // Lombok annotation
@Service
public class BookService {
    
    public Book saveBook(Book book) {
        log.debug("Saving book: {}", book.getIsbn());
        try {
            Book saved = bookRepository.save(book);
            log.info("Successfully saved book: {}", saved.getIsbn());
            return saved;
        } catch (DataAccessException e) {
            log.error("Failed to save book: {}", book.getIsbn(), e);
            throw new BookSaveException("Could not save book", e);
        }
    }
}
```

## 12. Documentation Requirements

### 12.1 Javadoc Standards

**Required for:**

- All public classes, interfaces, and enums
- All public methods
- Complex private methods
- DTOs and data models

**Format:**

```java
/**
 * Searches for books matching the given criteria.
 * 
 * <p>This method performs a full-text search across book titles, authors,
 * and descriptions. Results are paginated and sorted by relevance.</p>
 * 
 * @param searchTerm the search query (must not be null or empty)
 * @param page the page number (zero-based)
 * @param size the page size (must be between 1 and 100)
 * @return a page of matching books
 * @throws IllegalArgumentException if searchTerm is null or empty
 * @see BookRepository#searchBooks(String, Pageable)
 */
@Transactional(readOnly = true)
public Page<Book> searchBooks(String searchTerm, int page, int size) {
    // Implementation
}
```

### 12.2 Code Comments

**When to Comment:**

- Complex algorithms or business logic
- Non-obvious workarounds
- Performance optimizations
- Security considerations
- External API integration details

**When NOT to Comment:**

```java
// ❌ BAD: Stating the obvious
// Get the book by ID
Book book = bookRepository.findById(id);

// ✅ GOOD: Explaining why
// Using findById instead of getReferenceById to trigger immediate fetch
// and validation before proceeding with cover image processing
Book book = bookRepository.findById(id)
    .orElseThrow(() -> new BookNotFoundException(id));
```

---
This manual replaces prior `.cursorrules` and `claude.md` content; refer to `AGENTS.md` as the authoritative guide going forward.
