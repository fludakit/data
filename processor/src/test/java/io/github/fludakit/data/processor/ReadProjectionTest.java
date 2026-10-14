package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.fludakit.data.processor.ProcessorHarness.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * everything a READ adds on top of the base row: paging and its sort/seek wiring, how an
 * association is materialised (id-only partial, grouped loader, or a JOIN-projected full
 * instance), and the {@code <Repository>Queries} holder.
 *
 * <p>Split out of {@code RepositoryProcessorTest}; the shared compile-and-assert plumbing
 * lives in {@link ProcessorHarness}.</p>
 */
class ReadProjectionTest {

    @TempDir
    Path workspace;

    @Test
    void bareEntityFieldAndEntityCollectionClassifyAsManyToOneAndOneToManyWithResolvedInverse() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rel"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rel"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rel"));

        Files.writeString(sourceDir.resolve("Child.java"), """
                package rel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Child {
                    @Identity Long id;
                    Parent owner;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Parent getOwner() { return owner; }
                    public void setOwner(Parent owner) { this.owner = owner; }
                }
                """);
        Files.writeString(sourceDir.resolve("Parent.java"), """
                package rel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Parent {
                    @Identity Long id;
                    String name;
                    Child primary;
                    List<Child> children = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Child getPrimary() { return primary; }
                    public void setPrimary(Child primary) { this.primary = primary; }
                    public List<Child> getChildren() { return children; }
                    public void setChildren(List<Child> children) { this.children = children; }
                }
                """);
        Files.writeString(sourceDir.resolve("ParentRepository.java"), """
                package rel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ParentRepository extends CrudRepository<Parent, Long> { }
                """);
        // Child must be a MANAGED target (has its own @Repository) or the managed-target gate leaves
        // Parent.primary/Parent.children unmapped. ChildRepository also makes rel.Parent a managed
        // target so Child.owner hydrates on the child side.
        Files.writeString(sourceDir.resolve("ChildRepository.java"), """
                package rel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ChildRepository extends CrudRepository<Child, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Child.java", "Parent.java", "ParentRepository.java", "ChildRepository.java");

        // Flatten JavaPoet's soft wraps so the positional pins hold verbatim: relations are now
        // NAVIGABLE member classes whose leaf fields carry the owner-rooted dot paths while the fk
        // column and the resolved inverse ride the descriptor's columnRefs() literals, so ONLY
        // an exact assertion on either surface detects a swapped argument.
        String pm = Files.readString(generatedDir.resolve("rel/_Parent.java")).replaceAll("\\s+", "");
        assertTrue(pm.contains("_ParentPrimaryprimary=new_ParentPrimary();"),
                "the FK member must instantiate its nav class:" + System.lineSeparator() + pm);
        assertTrue(pm.contains("_ParentChildrenchildren=new_ParentChildren();"),
                "the one-to-many member must instantiate its nav class:" + System.lineSeparator() + pm);

        String fkNav = Files.readString(generatedDir.resolve("rel/_ParentPrimary.java")).replaceAll("\\s+", "");
        assertTrue(fkNav.contains("publicfinalclass_ParentPrimaryimplementsEntityAttribute<Parent,Child>"), fkNav);
        assertTrue(fkNav.contains("publicStringpath(){return\"primary\";}"), fkNav);
        assertTrue(fkNav.contains("IdAttribute<Parent>id=newIdAttributeRecord<>(\"id\",\"primary.id\");"),
                "the managed target's id must surface as an owner-typed leaf field:" + System.lineSeparator() + fkNav);
        assertTrue(fkNav.contains("ScalarAttribute<Parent>owner=newScalarAttributeRecord<>(\"owner\",\"primary.owner\");"),
                "the target's own FK column must surface as a plain leaf:" + System.lineSeparator() + fkNav);
        assertTrue(!fkNav.contains("targetDescriptor") && !fkNav.contains("publicStringcolumn()"),
                "the nav classes must not emit the removed column()/targetDescriptor() plumbing:"
                        + System.lineSeparator() + fkNav);

        String otmNav = Files.readString(generatedDir.resolve("rel/_ParentChildren.java")).replaceAll("\\s+", "");
        // The one-to-many rides the unified ListAttribute; the inverseName()/childFkColumn()
        // contract is gone — the resolved inverse fk is pinned through columnRefs below.
        assertTrue(otmNav.contains("publicfinalclass_ParentChildrenimplementsListAttribute<Parent,Child>"), otmNav);
        assertTrue(otmNav.contains("publicStringpath(){return\"children\";}"), otmNav);
        assertTrue(otmNav.contains("IdAttribute<Parent>id=newIdAttributeRecord<>(\"id\",\"children.id\");"), otmNav);
        String pmd = Files.readString(generatedDir.resolve("rel/ParentMetadata.java")).replaceAll("\\s+", "");
        assertTrue(pmd.contains("privatestaticfinalJoinEdgeEDGE_PRIMARY=newJoinEdge.ToOne(newColumnRef.ForeignKey(\"primary_id\",\"parent\"),newColumnRef.Simple(\"id\",\"child\"))"),
                "the to-one head must pin its FK edge (owner fk -> target id):" + System.lineSeparator() + pmd);
        assertTrue(pmd.contains("case\"primary.id\":returnnewColumnPlacement(newColumnRef.Simple(\"id\",\"child\"),List.of(EDGE_PRIMARY))"),
                "resolve must pin the FK traversal verbatim:" + System.lineSeparator() + pmd);
        assertTrue(pmd.contains("privatestaticfinalJoinEdgeEDGE_CHILDREN=newJoinEdge.ToMany(newColumnRef.Simple(\"id\",\"parent\"),newColumnRef.ForeignKey(\"owner_id\",\"child\"))"),
                "the to-many head must pin its inverse-fk edge:" + System.lineSeparator() + pmd);
        assertTrue(pmd.contains("case\"children.id\":returnnewColumnPlacement(newColumnRef.Simple(\"id\",\"child\"),List.of(EDGE_CHILDREN))"),
                "resolve must pin the one-to-many inverse join verbatim:" + System.lineSeparator() + pmd);

        String md = Files.readString(generatedDir.resolve("rel/ParentMetadata.java")).replaceAll("\\s+", " ");
        assertTrue(md.contains("public List<TypeMapping.Prop> props()"),
                "props() must list the root members:" + System.lineSeparator() + md);
        String mflat = Files.readString(generatedDir.resolve("rel/ParentMetadata.java")).replaceAll("\\s+", "");
        assertTrue(mflat.contains("mappings.put(\"name\",newColumnRef.Simple(\"name\",\"parent\"))")
                        && mflat.contains("mappings.put(\"primary\",newColumnRef.ForeignKey(\"primary_id\",\"parent\"))")
                        && mflat.contains("columns(){"),
                "typed mappings list the local table's columns; the one-to-many contributes none:"
                        + System.lineSeparator() + mflat);
        assertTrue(md.contains("public static final String PRIMARY = \"primary_id\";"),
                "the fk column constant must be emitted from the Property list:" + System.lineSeparator() + md);
        assertTrue(!md.contains("public static final String CHILDREN"),
                "ONE_TO_MANY contributes no column constant:" + System.lineSeparator() + md);

        // Association properties are skipped by the mapper gate, so the owner still generates.
        assertTrue(Files.exists(generatedDir.resolve("rel/ParentRowMapper.java")),
                "entity-typed fields are association-backed and must not abort the owner's gate");

        // ---- relationship hydration loaders (managed targets rel.Child / rel.Parent) ----
        // Normalise JavaPoet's escaped quotes and soft wraps so the SQL string literals pin verbatim.
        String parentImpl = Files.readString(generatedDir.resolve("rel/ParentRepositoryImpl.java"))
                .replace("\\\"", "\"");
        // ONE_TO_MANY (Parent.children, inverse "owner" → fk "owner_id" on the child's own table):
        // single-phase, group by the inverse fk, delegate to the child's generated entity RowMapper.
        assertTrue(parentImpl.contains("private void hydrateChildren(Collection<Parent> parents)"),
                "missing one-to-many loader:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("SELECT \"id\", \"owner_id\" FROM \"child\" WHERE \"owner_id\" IN ("),
                "one-to-many SELECT must list child columns incl. the inverse fk:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("new ChildRowMapper().mapRow(rs, rowNum)"),
                "one-to-many loader must pair child rows through the child entity row mapper:"
                        + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("parent.setChildren(grouped.getOrDefault(parent.getId(), new ArrayList<>()))"),
                "one-to-many loader must group children onto each parent:" + System.lineSeparator() + parentImpl);
        // MANY_TO_ONE (Parent.primary, target Child, fk "primary_id" on the PARENT table) is COMPOSING,
        // so it is JOIN-projected onto the parent's own row instead of being re-read by a loader:
        // no hydratePrimary is generated at all. A to-one join adds at most one row per parent, so it
        // cannot fan out and cannot move LIMIT/COUNT onto join rows.
        assertTrue(!parentImpl.contains("hydratePrimary"),
                "a composing many-to-one must not get a loader:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("private static final String SQL_FIND_BY_ID_JOINED = "
                        + "\"SELECT \"parent\".\"id\", \"parent\".\"name\", \"parent\".\"primary_id\", "
                        + "\"child\".\"id\" AS \"primary_join_id\" FROM \"parent\" LEFT JOIN \"child\" AS \"child\" "
                        + "ON \"parent\".\"primary_id\" = \"child\".\"id\" WHERE \"parent\".\"id\" = :id\""),
                "findById must JOIN-project the composing target:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("private static final String SQL_FIND_ALL_JOINED = "
                        + "\"SELECT \"parent\".\"id\", \"parent\".\"name\", \"parent\".\"primary_id\", "
                        + "\"child\".\"id\" AS \"primary_join_id\" FROM \"parent\" LEFT JOIN \"child\" AS \"child\" "
                        + "ON \"parent\".\"primary_id\" = \"child\".\"id\""),
                "findAll must JOIN-project the composing target:" + System.lineSeparator() + parentImpl);
        // The join is aliased by the TARGET TABLE while the LABEL keeps the property name: pinning the
        // qualified column on "child" (not "primary") is what keeps the projection resolvable.
        assertTrue(parentImpl.contains("\"child\".\"id\" AS \"primary_join_id\"")
                        && !parentImpl.contains("\"primary\".\"id\" AS \"primary_join_id\""),
                "the projection must be qualified by the join's table alias, not the property name:"
                        + System.lineSeparator() + parentImpl);
        // One shared joined mapper per repository: it delegates the root columns to the generated mapper
        // and overwrites the traversed association from the <field>_join_<column> labels.
        assertTrue(parentImpl.contains("private static Parent mapRow_full_crud(ResultSet rs, int rowNum)"),
                "missing the JOIN-projection row mapper:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("Parent entity = new ParentRowMapper().mapRow(rs, rowNum);"),
                "the joined mapper must delegate the root columns to the generated row mapper:"
                        + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("Long primary_join_id = rs.getObject(\"primary_join_id\", Long.class);"),
                "the joined mapper must read the target id from its label:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("entity.setPrimary(primaryFull);"),
                "the joined mapper must overwrite the association with the fully-populated instance:"
                        + System.lineSeparator() + parentImpl);
        // Every CRUD read shape uses BOTH the joined constant and the joined mapper — including the
        // built-ins, which Jakarta Data annotates with @Find and which would otherwise fall through to
        // the generic query-by-names emitter and emit the root-only constant.
        assertTrue(parentImpl.contains("jdbcClient.sql(SQL_FIND_BY_ID_JOINED).param(\"id\", id)"
                                + ".query((rs, rowNum) -> mapRow_full_crud(rs, rowNum)).optional()")
                        && parentImpl.contains("jdbcClient.sql(SQL_FIND_ALL_JOINED)"
                                + ".query((rs, rowNum) -> mapRow_full_crud(rs, rowNum)).list()"),
                "findById/findAll must use the joined constant AND the joined mapper:"
                        + System.lineSeparator() + parentImpl);
        // The umbrella now covers the one-to-many only.
        assertTrue(parentImpl.contains("private void hydrateAll(Collection<Parent> parents)"),
                "missing umbrella hydrateAll:" + System.lineSeparator() + parentImpl);
        assertTrue(parentImpl.contains("hydrateChildren(parents)"),
                "umbrella must call each relationship loader:" + System.lineSeparator() + parentImpl);
        // Write path: the MANY_TO_ONE fk binding flows through boundValue, asserted not rebuilt.
        assertTrue(parentImpl.contains(".param(\"primary\", entity.getPrimary() == null ? null : entity.getPrimary().getId())"),
                "many-to-one write must bind the target id through boundValue:" + System.lineSeparator() + parentImpl);

        // Child.owner is a composing MANY_TO_ONE into the managed rel.Parent, so the CHILD side is
        // JOIN-projected too: no hydrateOwner loader, a joined constant carrying the owner's leaves,
        // and its own joined row mapper.
        String childImpl = Files.readString(generatedDir.resolve("rel/ChildRepositoryImpl.java"))
                .replace("\\\"", "\"");
        assertTrue(!childImpl.contains("hydrateOwner"),
                "a composing many-to-one must not get a loader on the child side:"
                        + System.lineSeparator() + childImpl);
        assertTrue(childImpl.contains("\"parent\".\"id\" AS \"owner_join_id\""),
                "the child read must project the owner's id under a property-named label:"
                        + System.lineSeparator() + childImpl);
        assertTrue(childImpl.contains("private static Child mapRow_full_crud(ResultSet rs, int rowNum)"),
                "missing the JOIN-projection row mapper on the child side:" + System.lineSeparator() + childImpl);
        assertTrue(childImpl.contains("entity.setOwner(ownerFull);"),
                "the child joined mapper must overwrite owner with the fully-populated instance:"
                        + System.lineSeparator() + childImpl);
        assertTrue(!childImpl.contains("java.lang.reflect"),
                "generated loaders must stay reflection-free:" + System.lineSeparator() + childImpl);
    }

    @Test
    void pagedReadSuppressesATableTheJoinedBaseAlreadyJoined() throws Exception {
        // The paged read splices a runtime sort's join chain onto a base constant. When composing
        // to-one targets are JOIN-projected that base ALREADY carries LEFT JOIN "<target>", so the
        // emission must hand the runtime the tables to suppress — splicing the alias a second time
        // makes every reference to it ambiguous (proved end-to-end by ComposingToOneProjectionTest).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/splice"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-splice"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-splice"));

        Files.writeString(sourceDir.resolve("Author.java"), """
                package splice;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Author {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Book.java"), """
                package splice;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Book {
                    @Identity Long id;
                    String title;
                    Author author;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public Author getAuthor() { return author; }
                    public void setAuthor(Author author) { this.author = author; }
                }
                """);
        Files.writeString(sourceDir.resolve("AuthorRepository.java"), """
                package splice;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface AuthorRepository extends CrudRepository<Author, Long> { }
                """);
        Files.writeString(sourceDir.resolve("BookRepository.java"), """
                package splice;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface BookRepository extends CrudRepository<Book, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Author.java", "Book.java", "AuthorRepository.java", "BookRepository.java");

        String impl = Files.readString(generatedDir.resolve("splice/BookRepositoryImpl.java"))
                .replace("\\\"", "\"");
        // The base IS the joined constant in BOTH branches (it is already the qualified shape), and
        // the splice is told which table that constant already carries.
        assertTrue(impl.contains("(joins.isEmpty() && !keyed ? SQL_FIND_ALL_JOINED : SQL_FIND_ALL_JOINED)"),
                "the paged read must build its SQL on the JOIN-projected base:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("String joins = sql.joinClause(joinRefs, List.of(\"author\"));"),
                "the splice must suppress the table the joined base already joins:"
                        + System.lineSeparator() + impl);
        // A descriptor with no composing to-one has nothing to suppress: the legacy call is verbatim.
        String authorImpl = Files.readString(generatedDir.resolve("splice/AuthorRepositoryImpl.java"))
                .replace("\\\"", "\"");
        assertTrue(authorImpl.contains("String joins = sql.joinClause(joinRefs);"),
                "a descriptor with no projected join keeps the single-arg splice verbatim:"
                        + System.lineSeparator() + authorImpl);
    }

    @Test
    void unmanagedManyToOneTargetIsLeftUnmappedWithNote() throws Exception {
        // Managed-target gate: a relationship whose TARGET has no @Repository in the round
        // must NOT get a loader and must record a NOTE, so hydration is skipped rather than generating
        // a call to a row mapper that was never emitted.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mg"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mg"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mg"));

        Files.writeString(sourceDir.resolve("Sibling.java"), """
                package mg;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Sibling {
                    @Identity Long id;
                    String label;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package mg;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Holder {
                    @Identity Long id;
                    Sibling peer;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Sibling getPeer() { return peer; }
                    public void setPeer(Sibling peer) { this.peer = peer; }
                }
                """);
        // ONLY Holder is managed; Sibling has no repository.
        Files.writeString(sourceDir.resolve("Holders.java"), """
                package mg;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Holders extends CrudRepository<Holder, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir, "Sibling.java", "Holder.java", "Holders.java");

        String impl = Files.readString(generatedDir.resolve("mg/HoldersImpl.java")).replace("\\\"", "\"");
        assertTrue(!impl.contains("hydratePeer"),
                "unmanaged many-to-one target must not get a loader:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("hydrateAll"),
                "no umbrella when nothing hydrates:" + System.lineSeparator() + impl);
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("relationship 'peer' on mg.Holder: target mg.Sibling has no generated row mapper"),
                "managed-target gate NOTE must be recorded:" + System.lineSeparator() + notes);
    }

    @Test
    void pagedSortThroughEntityCollectionOrdersByAggregateSortTermAndIdTiebreak() throws Exception {
        // The findAllPage runtime sort through a to-many head must NOT splice a
        // fanning LEFT JOIN. Its ORDER BY term renders through the descriptor-driven aggregate
        // subquery helper, the query picks the ROOT-QUALIFIED plain select (no join), and the keyset
        // grows with the root-qualified "id" ASC tiebreak in ORDER BY, cols/dirs/props and the refs
        // the seek-terms loop walks. Cursor keys move to the descriptor-driven cursorFor overload.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/t6rt"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-t6rt"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-t6rt"));
        Files.writeString(sourceDir.resolve("WorkExperience.java"), """
                package t6rt;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class WorkExperience {
                    @Identity Long id;
                    String company;
                    Profile profile;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getCompany() { return company; }
                    public void setCompany(String company) { this.company = company; }
                    public Profile getProfile() { return profile; }
                    public void setProfile(Profile profile) { this.profile = profile; }
                }
                """);
        Files.writeString(sourceDir.resolve("Profile.java"), """
                package t6rt;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Profile {
                    @Identity Long id;
                    String fullName;
                    List<WorkExperience> workHistory = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getFullName() { return fullName; }
                    public void setFullName(String fullName) { this.fullName = fullName; }
                    public List<WorkExperience> getWorkHistory() { return workHistory; }
                    public void setWorkHistory(List<WorkExperience> workHistory) {
                        this.workHistory = workHistory;
                    }
                }
                """);
        Files.writeString(sourceDir.resolve("Profiles.java"), """
                package t6rt;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Profiles extends CrudRepository<Profile, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "WorkExperience.java", "Profile.java", "Profiles.java");

        String impl = sqlFlat(generatedDir.resolve("t6rt/ProfilesImpl.java"));
        assertTrue(impl.contains("orderBy.append(sql.isToManyPath(s.property())"
                        + " ? sql.aggregateSortTerm(r, s.isAscending())"
                        + " : sql.qualify(r.leaf()))"),
                "to-many sort heads must switch the ORDER BY term to the aggregate sort term:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("boolean keyed = false;"),
                "the keyset flag must exist:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(((joins.isEmpty() && !keyed ? SQL_FIND_ALL"
                        + " : SQL_FIND_ALL_QUALIFIED) + joins + (orderBy.length() > 0"),
                "OFFSET must take the qualified plain select when keyed even with empty joins:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("String joins = sql.joinClause(joinRefs)"),
                "the joinClause call must stay as emitted (to-many refs are skipped by the clause itself):"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN \"work_experience\""),
                "the generated file must not splice the to-many table as a join:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void pagedFinderSortThroughElementCollectionDescendsToMaxSubqueryWithIdTiebreak() throws Exception {
        // The compile-time sort side (a CursoredPage finder under @OrderBy) resolves a to-many
        // head to the SAME pre-rendered aggregate term used verbatim in ORDER BY and seekWhereTerms,
        // grows props/dirs/cols with the id tiebreak, and switches the cursor keys to the descriptor
        // overload over the grown lists.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/t6el"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-t6el"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-t6el"));
        Files.writeString(sourceDir.resolve("Contact.java"), """
                package t6el;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Contact {
                    String kind;
                    String value;
                    public String getKind() { return kind; }
                    public void setKind(String kind) { this.kind = kind; }
                    public String getValue() { return value; }
                    public void setValue(String value) { this.value = value; }
                }
                """);
        Files.writeString(sourceDir.resolve("Profile.java"), """
                package t6el;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Profile {
                    @Identity Long id;
                    String fullName;
                    List<Contact> contacts = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getFullName() { return fullName; }
                    public void setFullName(String fullName) { this.fullName = fullName; }
                    public List<Contact> getContacts() { return contacts; }
                    public void setContacts(List<Contact> contacts) { this.contacts = contacts; }
                }
                """);
        Files.writeString(sourceDir.resolve("Profiles.java"), """
                package t6el;
                import jakarta.data.page.CursoredPage;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Profiles extends CrudRepository<Profile, Long> {
                    @OrderBy(value = "contacts.value", descending = true)
                    CursoredPage<Profile> findBy(PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Contact.java", "Profile.java", "Profiles.java");

        String impl = sqlFlat(generatedDir.resolve("t6el/ProfilesImpl.java"));
        assertTrue(impl.contains("ORDER BY (SELECT MAX(\"profile_contacts\".\"value\") FROM"
                        + " \"profile_contacts\" \"profile_contacts\" WHERE \"profile_contacts\".\"profile_id\" ="
                        + " \"profile\".\"id\") DESC, \"profile\".\"id\" ASC"),
                "the paged finder ORDER BY must be the MAX subquery term plus the id tiebreak:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_FIND_BY_SEEK_NEXT = \"SELECT \"id\", \"full_name\" FROM \"profile\""
                        + " WHERE (((SELECT MAX("),
                "the forward seek constant must bake the aggregate term into its WHERE clause:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("IS NULL AND :cursor0 IS NULL)) AND \"profile\".\"id\" > :cursor1))"),
                "the composite seek must pin the preceding key with NULL-safe equality before the id tiebreak:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("CursorKeys.forEntity(java.util.List.of(\"contacts.value\", \"id\")"
                        + ", java.util.List.of(false, true), ProfileMetadata.INSTANCE, e)"),
                "cursor keys must ride the grown props/dirs lists through the descriptor overload:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "no to-many sort join may be spliced anywhere in this emission:" + System.lineSeparator() + impl);
    }

    @Test
    void pagedSortThroughReferenceStillJoinsByteIdentical() throws Exception {
        // Byte-parity pin: a to-one sort must NOT gain the aggregate switch or the id tiebreak at the
        // compile-time paged emission - the legacy resolveColumn/orderBy/seekWhere strings stay
        // exactly as they were before the to-many aggregate switch (the runtime to-one parity lives
        // in dynamicSortResolvesThroughDescriptorAndContributesJoins, which this pin protects).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/t6one"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-t6one"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-t6one"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("ParityOrd.java"), """
                package relpath;
                import jakarta.data.page.CursoredPage;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ParityOrd extends CrudRepository<Order, Long> {
                    @OrderBy(value = "customer")
                    CursoredPage<Order> findBy(PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java",
                "ParityOrd.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/ParityOrdImpl.java"));
        assertTrue(impl.contains("ORDER BY \"customer_id\" ASC"),
                "the to-one fk sort must keep the legacy orderBy term:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("customer_id\" ASC, \"") && !impl.contains("customer_id\" ASC, "),
                "no term may follow the legacy to-one ORDER BY (no tiebreak gained):"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_FIND_BY_SEEK_NEXT = \"SELECT \"id\", \"status\", \"customer_id\""
                        + " FROM \"order\" WHERE ((\"customer_id\" > :cursor0)) ORDER BY \"customer_id\" ASC LIMIT \""),
                "the forward seek constant must bake the legacy bare-column seek clause:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("ORDER BY (SELECT"),
                "no aggregate sort term may appear for a to-one sort:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("CursorKeys.forEntity(java.util.List.of(\"customer\"),"
                        + " java.util.List.of(true), OrderMetadata.INSTANCE, e)"),
                "cursor keys keep the UNGROWN props list on the descriptor overload:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void localSortStaysLegacyByteIdentical() throws Exception {
        // Byte-parity pin: with NO traversable prop at all the whole paged findAll stays the
        // single-constant legacy emission - no qualified constant, no keyed machinery, no terms.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/t6loc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-t6loc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-t6loc"));
        Files.writeString(sourceDir.resolve("Memo.java"), """
                package t6loc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Memo {
                    @Identity Long id;
                    String text;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        Files.writeString(sourceDir.resolve("Memos.java"), """
                package t6loc;
                import jakarta.data.page.CursoredPage;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Memos extends CrudRepository<Memo, Long> {
                    @OrderBy(value = "text")
                    CursoredPage<Memo> findBy(PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Memo.java", "Memos.java");

        String impl = sqlFlat(generatedDir.resolve("t6loc/MemosImpl.java"));
        assertTrue(!impl.contains("SQL_FIND_ALL_QUALIFIED") && !impl.contains("keyed"),
                "a traversal-impossible descriptor must keep the single-constant legacy shape:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_FIND_ALL + joins + (orderBy.length() > 0"),
                "the legacy OFFSET splice must stay:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("(SELECT") && !impl.contains("CursorSupport"),
                "no aggregate term and no runtime cursor helper for local sorts:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("ORDER BY \"text\" ASC"),
                "legacy orderBy term for a local sort:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SQL_FIND_BY_SEEK_NEXT = \"SELECT \"id\", \"text\" FROM \"memo\""
                        + " WHERE ((\"text\" > :cursor0)) ORDER BY \"text\" ASC LIMIT \""),
                "the forward seek constant must bake the legacy local-sort seek clause:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("CursorKeys.forEntity(java.util.List.of(\"text\"),"
                        + " java.util.List.of(true), MemoMetadata.INSTANCE, e)"),
                "cursored-finder cursor keys ride the descriptor overload:" + System.lineSeparator() + impl);
    }

    @Test
    void relationFreePagedFindAllKeepsSingleConstantShape() throws Exception {
        // Legacy-parity guard: a descriptor WITHOUT traversable props can never produce a
        // join, so its paged findAll keeps the byte-identical single-constant emission — no
        // SQL_FIND_ALL_QUALIFIED field, no seekWhereTerms loop.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/navplain"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-navplain"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-navplain"));

        Files.writeString(sourceDir.resolve("Note.java"), """
                package navplain;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Note {
                    @Identity Long id;
                    String text;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        Files.writeString(sourceDir.resolve("NoteRepository.java"), """
                package navplain;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface NoteRepository extends CrudRepository<Note, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Note.java", "NoteRepository.java");

        String impl = Files.readString(generatedDir.resolve("navplain/NoteRepositoryImpl.java"))
                .replace("\\\"", "\"").replaceAll("\\s+", " ");

        assertTrue(impl.contains("jdbcClient.sql(SQL_FIND_ALL + joins + (orderBy.length() > 0"),
                "relation-free OFFSET emission must keep the single-constant splice:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("SQL_FIND_ALL_QUALIFIED"),
                "no qualified constant for a join-impossible descriptor:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("CursorSupport") && !impl.contains("seekWhere"),
                "a Page-returning findAll must not emit any cursor/seek machinery:" + System.lineSeparator() + impl);
    }

    @Test
    void dynamicSortResolvesThroughDescriptorAndContributesJoins() throws Exception {
        // A paginated findAll(PageRequest, Order) builds its ORDER BY at RUNTIME from the caller's
        // Sort criteria. The compile-time string-switch column() helper is replaced by a
        // descriptor-backed resolveRef(...) -> <Entity>Metadata.columnRef(path), so each sort
        // resolves through the descriptor: relation-traversing paths contribute LEFT JOINs, while
        // embedded leaves stay on the root table (no join). This pins the emitted wiring; the
        // runtime relation-sort join behaviour is asserted by the integration tests.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/dsort"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-dsort"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-dsort"));

        Files.writeString(sourceDir.resolve("GeoPoint.java"), """
                package dsort;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class GeoPoint {
                    double lat;
                    double lng;
                    public double getLat() { return lat; }
                    public void setLat(double lat) { this.lat = lat; }
                    public double getLng() { return lng; }
                    public void setLng(double lng) { this.lng = lng; }
                }
                """);
        Files.writeString(sourceDir.resolve("Address.java"), """
                package dsort;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Address {
                    String city;
                    GeoPoint point;
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public GeoPoint getPoint() { return point; }
                    public void setPoint(GeoPoint point) { this.point = point; }
                }
                """);
        Files.writeString(sourceDir.resolve("Author.java"), """
                package dsort;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Author {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Book.java"), """
                package dsort;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Book {
                    @Identity Long id;
                    String title;
                    Address address;
                    @Association Author author;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public Address getAddress() { return address; }
                    public void setAddress(Address address) { this.address = address; }
                    public Author getAuthor() { return author; }
                    public void setAuthor(Author author) { this.author = author; }
                }
                """);
        Files.writeString(sourceDir.resolve("Books.java"), """
                package dsort;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Books extends CrudRepository<Book, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "GeoPoint.java", "Address.java", "Author.java", "Book.java", "Books.java");

        String impl = Files.readString(generatedDir.resolve("dsort/BooksImpl.java"))
                .replaceAll("\\s+", " ");

        // (1) The compile-time string-switch helper is gone; the generated per-entity resolve()
        //     switch on the descriptor replaces it — there is no per-repository resolver left.
        assertTrue(!impl.contains("String column(String property)"),
                "the old column(String) switch helper must be removed:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("resolveRef"),
                "the per-repository resolveRef helper must be gone:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("BookMetadata.INSTANCE.resolve(s.property())"),
                "the sort must resolve through the entity descriptor's generated resolve switch:"
                        + System.lineSeparator() + impl);

        // (2) The runtime loop resolves each property ONCE and reuses the local for the join chain
        //     and ORDER BY, so descriptor work is not repeated.
        assertTrue(impl.contains("ColumnPlacement r = BookMetadata.INSTANCE.resolve(s.property())"),
                "the loop must resolve each property once into a placement:" + System.lineSeparator() + impl);

        // (3) ORDER BY is qualified through the descriptor-backed builder against the root table with
        //     an EXPLICIT direction, and the LEFT JOIN chain is computed once from the refs.
        assertTrue(impl.contains("sql.qualify(r.leaf())"),
                "ORDER BY must qualify the resolved leaf against the root table:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("s.isAscending() ? \" ASC\" : \" DESC\""),
                "every ORDER BY term must carry an explicit ASC/DESC:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("String joins = sql.joinClause(joinRefs)"),
                "the join clause must be computed from the descriptor and refs:" + System.lineSeparator() + impl);

        // (4) joins is spliced immediately after the FROM-bound find constant in the OFFSET read.
        //     Book declares a traversable prop (@Association author), so the
        //     emission picks between the plain SQL_FIND_ALL and the root-table-QUALIFIED
        //     SQL_FIND_ALL_QUALIFIED at runtime — the join is spliced onto whichever matches.
        assertTrue(impl.contains("jdbcClient.sql(((joins.isEmpty() && !keyed ? SQL_FIND_ALL : SQL_FIND_ALL_QUALIFIED) + joins + (orderBy.length() > 0"),
                "OFFSET statement must pick the qualified constant when joins are present:" + System.lineSeparator() + impl);
        // The qualified constant exists and qualifies EVERY root column (labels stay bare, so the
        // existing row mapper reads both variants).
        assertTrue(impl.contains("SELECT \\\"book\\\".\\\"id\\\", \\\"book\\\".\\\"title\\\", \\\"book\\\".\\\"address_city\\\","
                        + " \\\"book\\\".\\\"address_point_lat\\\", \\\"book\\\".\\\"address_point_lng\\\","
                        + " \\\"book\\\".\\\"author_id\\\" FROM \\\"book\\\""),
                "SQL_FIND_ALL_QUALIFIED must list root-table-qualified columns:" + System.lineSeparator() + impl);
    }

    @Test
    void nonPagedSortThroughElementCollectionRendersAggregateAndNoTiebreak() throws Exception {
        // C-4.1/C-4.2 (plan B parked minor): the NON-paged SqlBuilder.build emission of a to-many
        // sort head renders the same aggregate term verbatim in ORDER BY, splices NO LEFT JOIN for
        // the collection, and gains NO root-id tiebreak — tiebreaking is a paged-cursor concern (seek
        // keys need a total order); findTop… through a collection stays order-nondeterministic among
        // ties like any non-paged ORDER BY.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/c4sort"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-c4sort"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-c4sort"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("SortOrd.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.OrderBy;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface SortOrd extends CrudRepository<Order, Long> {
                    @OrderBy(value = "orderItems.quantity", descending = true)
                    List<Order> findByStatus(String status);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "SortOrd.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/SortOrdImpl.java"));
        assertTrue(impl.contains("ORDER BY (SELECT MAX(\"order_order_items\".\"quantity\") FROM \"order_order_items\" \"order_order_items\" WHERE \"order_order_items\".\"order_id\" = \"order\".\"id\") DESC"),
                "the non-paged ORDER BY must carry the aggregate term verbatim:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("LEFT JOIN"),
                "no to-many join may be spliced by the non-paged emission:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains(") DESC, \"order\".\"id\"") && !impl.contains(" DESC, \"order\".\"id\" ASC"),
                "non-paged finders gain no root-id tiebreak (policy: tiebreaks exist for cursor seek keys only):"
                        + System.lineSeparator() + impl);
    }

    @Test
    void generatesRepositoryQueriesWithNestedQueryDefinitions() throws Exception {
        // Query-by-names methods publish their logical structure as a generated
        // <Repo>Queries. Assert the shape, the logical predicate, the paging mode and the cursor
        // tiebreak, plus the overload suffix. The <Repo>Impl bytes are unchanged by this emission.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/qmd"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-qmd"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-qmd"));

        Files.writeString(sourceDir.resolve("Widget.java"), """
                package qmd;

                public class Widget {
                    public Long id;
                    public String status;

                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                }
                """);
        Files.writeString(sourceDir.resolve("Widgets.java"), """
                package qmd;

                import jakarta.data.page.Page;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;

                @Repository
                public interface Widgets extends CrudRepository<Widget, Long> {
                    List<Widget> findByStatus(String status);
                    long countByStatus(String status);
                    Page<Widget> findByStatus(String status, PageRequest pageRequest);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Widget.java", "Widgets.java");

        Path queriesFile = generatedDir.resolve("qmd/WidgetsQueries.java");
        assertTrue(Files.exists(queriesFile), "expected generated " + queriesFile);
        String queries = Files.readString(queriesFile).replaceAll("\\s+", " ");

        assertTrue(queries.contains("public final class WidgetsQueries"),
                "holder:" + System.lineSeparator() + queries);
        // one query-definition field per query-by-names method, in declaration order, overload suffixed
        assertTrue(queries.contains("public static final QueryDefinition FIND_BY_STATUS_QUERY = new QueryDefinition.Read("),
                "find publishes a Read:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("public static final QueryDefinition FIND_BY_STATUS_QUERY2 = new QueryDefinition.Read("),
                "paged overload must be suffixed and still a Read:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("public static final QueryDefinition COUNT_BY_STATUS_QUERY = new QueryDefinition.Count("),
                "count publishes a Count:" + System.lineSeparator() + queries);
        // query metadata retains logical paths; SqlBuilder resolves them against the schema.
        assertTrue(queries.contains("Predicate.of(\"status\", Operator.EQ, \"status\", Connector.AND, false)"),
                "logical predicate:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("ResultKind.LIST,"), "list result kind:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("ResultKind.PAGE,"), "paged result kind:"
                + System.lineSeparator() + queries);
        assertTrue(queries.contains("PagingMode.OFFSET,"), "paged mode:"
                + System.lineSeparator() + queries);
        assertTrue(queries.contains("PagingMode.NONE,"), "plain mode:" + System.lineSeparator() + queries);
        assertTrue(queries.contains("new ParamBinding(0, \"status\", LikePattern.NONE)"), "param binding:"
                + System.lineSeparator() + queries);
        // the paged model declares no ORDER BY sorts of its own here, and its seek terms are the
        // id-ascending default the runtime cursor uses
        assertTrue(queries.contains("new SeekTerm(")
                        && queries.contains("new ColumnRef.Simple(\"id\", \"widget\"), true)"),
                "default cursor seek term:" + System.lineSeparator() + queries);
        // the impl keeps its own constants regardless of the published query definitions
        String impl = Files.readString(generatedDir.resolve("qmd/WidgetsImpl.java"));
        assertTrue(impl.contains("SQL_FIND_BY_STATUS"), "impl constants survive:"
                + System.lineSeparator() + impl);
    }

    @Test
    void overriddenInheritedFinderProducesOneQueryDefinition() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/query-override"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-query-override"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-query-override"));

        Files.writeString(sourceDir.resolve("Widget.java"), """
                package queryoverride;
                public class Widget {
                    public Long id;
                    public String status;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                }
                """);
        Files.writeString(sourceDir.resolve("WidgetFinder.java"), """
                package queryoverride;
                import java.util.List;
                public interface WidgetFinder {
                    List<Widget> findByStatus(String status);
                }
                """);
        Files.writeString(sourceDir.resolve("Widgets.java"), """
                package queryoverride;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface Widgets extends CrudRepository<Widget, Long>, WidgetFinder {
                    @Override
                    List<Widget> findByStatus(String status);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Widget.java", "WidgetFinder.java", "Widgets.java");

        String queries = Files.readString(generatedDir.resolve("queryoverride/WidgetsQueries.java"))
                .replaceAll("\\s+", " ");
        String impl = Files.readString(generatedDir.resolve("queryoverride/WidgetsImpl.java"))
                .replaceAll("\\s+", " ");
        assertTrue(queries.contains("QueryDefinition FIND_BY_STATUS_QUERY = new QueryDefinition.Read("), queries);
        assertTrue(!queries.contains("FIND_BY_STATUS_QUERY2"), "override must not look like an overload: " + queries);
        assertTrue(occurrences(queries, "QueryDefinition FIND_BY_STATUS_QUERY = new QueryDefinition.Read(") == 1,
                "one query definition per effective method: " + queries);
        assertTrue(occurrences(impl, "List<Widget> findByStatus(String status)") == 1,
                "one implementation method per effective method: " + impl);
    }

    @Test
    void postgreSqlDialectSelectionGeneratesPostgreSqlDialectField() throws Exception {
        // -Afluda.dialect=postgresql selects the PostgreSQL dialect: the generated impl's SQL_DIALECT
        // field references PostgreSqlDialect.INSTANCE (which today renders identically to H2).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-pg"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-pg"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-pg"));
        Files.writeString(sourceDir.resolve("Item.java"), """
                package rppg;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Item {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Items.java"), """
                package rppg;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Items extends CrudRepository<Item, Long> { }
                """);

        compileWithDialect(sourceDir, classesDir, generatedDir, "postgresql", "Item.java", "Items.java");

        String impl = sqlFlat(generatedDir.resolve("rppg/ItemsImpl.java"));
        // The seam is the field DECLARATION plus its WIRING into the builder. A bare
        // contains("PostgreSqlDialect.INSTANCE") is satisfied by the declaration alone — including by
        // one that is never read — while every statement still renders with whatever the default is.
        assertTrue(impl.contains("private static final SqlDialect SQL_DIALECT = PostgreSqlDialect.INSTANCE;"),
                "the generated impl must declare the selected PostgreSQL dialect:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("new SqlBuilder(ItemMetadata.INSTANCE).dialect(SQL_DIALECT)"),
                "every statement must render through the declared dialect, not a hard-coded one:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void withoutADialectOptionTheGeneratedImplStillWiresTheDefaultDialect() throws Exception {
        // -Afluda.dialect is absent: the field falls back to H2 and, more importantly, every statement
        // still reads SQL_DIALECT — the default is a value of the seam, not a second code path.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-default"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-default"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-default"));
        Files.writeString(sourceDir.resolve("Item.java"), """
                package rpdefault;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Item {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Items.java"), """
                package rpdefault;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Items extends CrudRepository<Item, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Item.java", "Items.java");

        String impl = sqlFlat(generatedDir.resolve("rpdefault/ItemsImpl.java"));
        assertTrue(impl.contains("private static final SqlDialect SQL_DIALECT = H2Dialect.INSTANCE;"),
                "no -Afluda.dialect option must fall back to the H2 dialect:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("new SqlBuilder(ItemMetadata.INSTANCE).dialect(SQL_DIALECT)"),
                "the default dialect must reach the builder through the same field:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void associationFieldMaterializesIdOnlyPartialWhileCompositionKeepsLoader() throws Exception {
        // An @Association field (composition=false) materializes at mapper-read time as an
        // ID-ONLY partial instance through a generated partial_<Target> helper, with NO two-phase
        // loader. A bare entity field on an @AggregateRoot (composition=true) keeps its two-phase
        // hydrate_<field> loader exactly as before. The write path binds only the association id, so a
        // partial (id set, other leaves null) round-trips without reading non-id leaves.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/assoc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-assoc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-assoc"));

        Files.writeString(sourceDir.resolve("Customer.java"), """
                package assoc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Customer {
                    @Identity Long id;
                    String name;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        Files.writeString(sourceDir.resolve("Payment.java"), """
                package assoc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Payment {
                    @Identity Long id;
                    long amount;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public long getAmount() { return amount; }
                    public void setAmount(long amount) { this.amount = amount; }
                }
                """);
        Files.writeString(sourceDir.resolve("Order.java"), """
                package assoc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Order {
                    @Identity Long id;
                    String status;
                    @Association Customer customer;
                    Payment payment;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }
                    public Customer getCustomer() { return customer; }
                    public void setCustomer(Customer customer) { this.customer = customer; }
                    public Payment getPayment() { return payment; }
                    public void setPayment(Payment payment) { this.payment = payment; }
                }
                """);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package assoc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Orders extends CrudRepository<Order, Long> { }
                """);
        // Payment has its own repository → it is a MANAGED target, so the composition (Order.payment)
        // keeps its two-phase hydrate loader. Customer has no repository, which is irrelevant to the
        // association partial (it only needs a no-arg ctor + setId).
        Files.writeString(sourceDir.resolve("Payments.java"), """
                package assoc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Payments extends CrudRepository<Payment, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Payment.java", "Order.java", "Orders.java", "Payments.java");

        String mapper = Files.readString(generatedDir.resolve("assoc/OrderRowMapper.java"));
        assertTrue(mapper.contains("Long customer_id = rs.getObject(OrderMetadata.CUSTOMER, Long.class);"),
                "the association fk must be read into a local by the owner metadata constant:"
                        + System.lineSeparator() + mapper);
        assertTrue(mapper.contains("entity.setCustomer(customer_id == null ? null : partial_Customer(customer_id));"),
                "the association must materialize as an id-only partial through the ternary:"
                        + System.lineSeparator() + mapper);
        assertTrue(mapper.contains("private static Customer partial_Customer(Long id)"),
                "missing the generated partial_Customer helper:" + System.lineSeparator() + mapper);
        assertTrue(mapper.contains("Customer c = new Customer();") && mapper.contains("c.setId(id);")
                        && mapper.contains("return c;"),
                "the partial helper must be new + setId(id) + return, reflection-free:" + System.lineSeparator() + mapper);
        // The composition field (payment) is NOT read by the mapper — the loader fills it.
        assertTrue(!mapper.contains("partial_Payment") && !mapper.contains("setPayment"),
                "a composition reference is never read by the row mapper (loader-fills):" + System.lineSeparator() + mapper);

        String impl = Files.readString(generatedDir.resolve("assoc/OrdersImpl.java")).replace("\\\"", "\"");
        // A composing to-one is JOIN-projected onto the owner's own row, so the composition gets NO
        // two-phase loader: the joined constant carries the target's labelled leaves and the shared
        // joined mapper materializes the target from them. Order has no to-many, so no umbrella either.
        assertTrue(!impl.contains("hydratePayment") && !impl.contains("hydrateAll"),
                "a composing many-to-one must not get a loader:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("private static final String SQL_FIND_ALL_JOINED = "
                        + "\"SELECT \"order\".\"id\", \"order\".\"status\", \"order\".\"customer_id\", "
                        + "\"order\".\"payment_id\", \"payment\".\"id\" AS \"payment_join_id\", "
                        + "\"payment\".\"amount\" AS \"payment_join_amount\" FROM \"order\" "
                        + "LEFT JOIN \"payment\" AS \"payment\" ON \"order\".\"payment_id\" = \"payment\".\"id\""),
                "the composition read must JOIN-project the target's scalar leaves:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("private static Order mapRow_full_crud(ResultSet rs, int rowNum)"),
                "missing the JOIN-projection row mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("entity.setPayment(paymentFull);"),
                "the joined mapper must overwrite the composition reference:" + System.lineSeparator() + impl);
        // The ASSOCIATION is untouched by the projection: composition=false still materializes as an
        // id-only partial read by the base row mapper, and gets no loader and no join label.
        assertTrue(!impl.contains("hydrateCustomer"),
                "an @Association must NOT get a two-phase loader:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("customer_join"),
                "an @Association must not be JOIN-projected:" + System.lineSeparator() + impl);
        // Write path binds ONLY the association id (never a non-id leaf), so partials round-trip.
        assertTrue(impl.contains(".param(\"customer\", entity.getCustomer() == null ? null : entity.getCustomer().getId())"),
                "the write path must bind the association id through boundValue:" + System.lineSeparator() + impl);
        assertTrue(!impl.contains("entity.getCustomer().get") || impl.contains("entity.getCustomer().getId()"),
                "the generated save path must bind only the association id, never a non-id leaf:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void associationTargetWithoutPartialConstructorDegradesToNullWithNote() throws Exception {
        // NOTE-degrade: if the association target lacks a no-arg ctor or setId, the
        // mapper leaves the field null (no partial, no loader) and a NOTE is recorded — never an abort.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/assocn"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-assocn"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-assocn"));

        Files.writeString(sourceDir.resolve("Locked.java"), """
                package assocn;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Locked {
                    @Identity Long id;
                    public Locked(Long id) { this.id = id; }
                    public Long getId() { return id; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package assocn;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Association;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Holder {
                    @Identity Long id;
                    @Association Locked locked;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Locked getLocked() { return locked; }
                    public void setLocked(Locked locked) { this.locked = locked; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holders.java"), """
                package assocn;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Holders extends CrudRepository<Holder, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir, "Locked.java", "Holder.java", "Holders.java");

        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("association 'locked' on assocn.Holder"),
                "the missing-partial-ctor NOTE must name the association:" + System.lineSeparator() + notes);
        String mapper = Files.readString(generatedDir.resolve("assocn/HolderRowMapper.java"));
        assertTrue(!mapper.contains("partial_Locked") && !mapper.contains("setLocked"),
                "a NOTE-degraded association must stay null (no partial, no setter call):"
                        + System.lineSeparator() + mapper);
    }

    @Test
    void associationPredicateTraversalMaterializesFullJoinedInstance() throws Exception {
        // Join-returns-all-fields: findByCustomerName traverses the @Association customer Reference at
        // compile time, so the SELECT carries the target's flattened columns aliased to
        // customer_join_*, and the method uses a generated mapRow_full_<method> mapper that sets
        // customer to a FULLY populated instance read from the aliased join columns.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-full"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-full"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-full"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("FullOrd.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface FullOrd extends CrudRepository<Order, Long> {
                    List<Order> findByCustomerName(String name);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "FullOrd.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/FullOrdImpl.java"));
        // Root columns qualified by the root table, joined target columns qualified + aliased.
        assertTrue(impl.contains("SELECT \"order\".\"id\", \"order\".\"status\", \"order\".\"customer_id\", "
                        + "\"customer\".\"id\" AS \"customer_join_id\", \"customer\".\"name\" AS \"customer_join_name\" "
                        + "FROM \"order\""),
                "the joined find must project the target's columns under <alias>_join_<col>:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("mapRow_full_find_by_customer_name"),
                "the method must carry a generated per-method full mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".query((rs, rowNum) -> mapRow_full_find_by_customer_name(rs, rowNum))"),
                "the query call-site must use the full mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("Customer customerFull = new Customer();")
                        && impl.contains("customerFull.setId(customer_join_id)")
                        && impl.contains("customerFull.setName(rs.getObject(\"customer_join_name\", String.class))")
                        && impl.contains("entity.setCustomer(customerFull)"),
                "the full mapper must build a fully-populated target from the aliased labels:"
                        + System.lineSeparator() + impl);
    }

    @Test
    void everyJoinLabelTheFullMapperReadsIsTheLabelTheProjectionPublishes() throws Exception {
        // The <field>_join_<column> contract has no compiler behind it: the projection writes the SELECT
        // list and the per-method row mapper reads it, and the two agree on a string. Pin the pairing
        // structurally — walk every joined label the generated mapper READS and require the generated
        // constant to publish it as an AS alias. Renaming a label on either side now fails here instead
        // of shipping a repository that reads a column the statement never selected.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-labels"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-labels"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-labels"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("LabelOrd.java"), """
                package relpath;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                import java.util.List;
                @Repository
                public interface LabelOrd extends CrudRepository<Order, Long> {
                    List<Order> findByCustomerName(String name);
                }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "LabelOrd.java");

        String impl = sqlFlat(generatedDir.resolve("relpath/LabelOrdImpl.java"));
        String mapper = bodyOf(impl,
                "private static Order mapRow_full_find_by_customer_name(ResultSet rs, int rowNum)");
        Set<String> read = new LinkedHashSet<>();
        Matcher labels = Pattern.compile("\"([a-z0-9_]+_join_[a-z0-9_]+)\"").matcher(mapper);
        while (labels.find()) {
            read.add(labels.group(1));
        }
        assertTrue(read.size() >= 2,
                "the full mapper must read more than one joined label:" + System.lineSeparator() + mapper);
        for (String label : read) {
            assertTrue(impl.contains("AS \"" + label + "\""),
                    "the mapper reads " + label + " but no generated constant publishes it as an AS alias:"
                            + System.lineSeparator() + impl);
        }
    }

    @Test
    void pagedFinderWithAssociationTraversalDegradesWithNote() throws Exception {
        // Degrade: a Page-returning finder whose method-name predicate path traverses the
        // @Association customer Reference at compile time CANNOT carry the full joined materialisation
        // (the runtime sort/seek wiring appends its own join chain to the base constant), so it is
        // degraded to the normal base row mapper — association fields stay id-only partials — and the
        // processor records a NOTE naming the degrade. Pin the NOTE text and the absence of the
        // per-method full mapper for the paged shape.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/rp-pgfull"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-rp-pgfull"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-rp-pgfull"));
        writeRelationFixture(sourceDir);
        Files.writeString(sourceDir.resolve("PagedOrd.java"), """
                package relpath;
                import jakarta.data.page.Page;
                import jakarta.data.page.PageRequest;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface PagedOrd extends CrudRepository<Order, Long> {
                    Page<Order> findByCustomerName(PageRequest pageRequest, String name);
                }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(sourceDir,
                classesDir, generatedDir,
                "Customer.java", "Country.java", "Region.java", "OrderItem.java", "Order.java", "PagedOrd.java");

        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("full materialisation through joins is not supported for paged finders"),
                "the paged joined-full degrade must record its NOTE:" + System.lineSeparator() + notes);

        String impl = sqlFlat(generatedDir.resolve("relpath/PagedOrdImpl.java"));
        assertTrue(!impl.contains("mapRow_full_find_by_customer_name"),
                "the degraded paged finder must NOT carry a generated full mapper:" + System.lineSeparator() + impl);
    }

    @Test
    void bareEntityFieldOnEntityOwnerIsNonComposingAssociation() throws Exception {
        // Classification Table: a bare entity-typed field inside an @Entity owner is treated as an
        // ASSOCIATION (Prop.Reference composition=false), NOT a composition — so no two-phase loader is
        // emitted even though the target is managed. estore/team fixtures stay @AggregateRoot, so this
        // is the first behavior change and must not move their generated output.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/sbr"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-sbr"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-sbr"));

        Files.writeString(sourceDir.resolve("Owner.java"), """
                package sbr;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Owner {
                    @Identity Long id;
                    String label;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
        Files.writeString(sourceDir.resolve("TeamWithBareRef.java"), """
                package sbr;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class TeamWithBareRef {
                    @Identity Long id;
                    Owner owner;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Owner getOwner() { return owner; }
                    public void setOwner(Owner owner) { this.owner = owner; }
                }
                """);
        // Owner is a MANAGED target (has its own repository) so the pre-change composing classification
        // would emit a loader; only composition=false (this task) suppresses it.
        Files.writeString(sourceDir.resolve("OwnerRepository.java"), """
                package sbr;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface OwnerRepository extends CrudRepository<Owner, Long> { }
                """);
        Files.writeString(sourceDir.resolve("TeamWithBareRefRepository.java"), """
                package sbr;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface TeamWithBareRefRepository extends CrudRepository<TeamWithBareRef, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir,
                "Owner.java", "TeamWithBareRef.java", "OwnerRepository.java", "TeamWithBareRefRepository.java");

        String impl = Files.readString(generatedDir.resolve("sbr/TeamWithBareRefRepositoryImpl.java"))
                .replace("\\\"", "\"");
        assertTrue(!impl.contains("hydrateOwner"),
                "a bare entity field on an @Entity owner must NOT get a two-phase Owner loader:"
                        + System.lineSeparator() + impl);
        assertTrue(!impl.contains("hydrateAll"),
                "no hydration umbrella when the only relationship is a non-composing association:"
                        + System.lineSeparator() + impl);

        // The metamodel still classifies owner as a foreign-key Reference (an association, not
        // embedded): a navigable member class implementing the EntityAttribute contract with the
        // managed target's leaf fields.
        String mm = Files.readString(generatedDir.resolve("sbr/_TeamWithBareRef.java")).replaceAll("\\s+", "");
        assertTrue(mm.contains("_TeamWithBareRefOwnerowner=new_TeamWithBareRefOwner();"),
                "a bare entity field on @Entity must classify as an FK Reference (association):"
                        + System.lineSeparator() + mm);
        String ownerNav = Files.readString(generatedDir.resolve("sbr/_TeamWithBareRefOwner.java")).replaceAll("\\s+", "");
        assertTrue(ownerNav.contains(
                "publicfinalclass_TeamWithBareRefOwnerimplementsEntityAttribute<TeamWithBareRef,Owner>"), ownerNav);
        assertTrue(ownerNav.contains("TextScalarAttribute<TeamWithBareRef>label="
                        + "newTextScalarAttributeRecord<>(\"label\",\"owner.label\");"), ownerNav);

        // The composition-gated managed-target NOTE is only emitted for composing references; an
        // association is skipped entirely, so no relationship NOTE appears for owner.
        String notes = notesOf(diagnostics);
        assertTrue(!notes.contains("relationship 'owner' on sbr.TeamWithBareRef"),
                "a non-composing association must not emit a relationship NOTE:" + System.lineSeparator() + notes);
    }

    @Test
    void valueObjectEntityCollectionIsOneToManyNotElementCollection() throws Exception {
        // A @ValueObject may hold entity collections; a List<@Entity> inside a
        // VO classifies as a one-to-many (Prop.EntityCollection), never an element collection. Driven through
        // an aggregate-root owner (the same idiom the embedded VO tests use to force VO model generation).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/voel"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-voel"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-voel"));

        Files.writeString(sourceDir.resolve("Thing.java"), """
                package voel;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Thing {
                    @Identity Long id;
                    String label;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package voel;
                import io.github.fludakit.data.annotation.ValueObject;
                import java.util.ArrayList;
                import java.util.List;
                @ValueObject
                public class Holder {
                    List<Thing> things = new ArrayList<>();
                    public List<Thing> getThings() { return things; }
                    public void setThings(List<Thing> things) { this.things = things; }
                }
                """);
        Files.writeString(sourceDir.resolve("Widget.java"), """
                package voel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Widget {
                    @Identity Long id;
                    Holder holder;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Holder getHolder() { return holder; }
                    public void setHolder(Holder holder) { this.holder = holder; }
                }
                """);
        Files.writeString(sourceDir.resolve("ThingRepository.java"), """
                package voel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ThingRepository extends CrudRepository<Thing, Long> { }
                """);
        Files.writeString(sourceDir.resolve("WidgetRepository.java"), """
                package voel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface WidgetRepository extends CrudRepository<Widget, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Thing.java", "Holder.java", "Widget.java", "ThingRepository.java", "WidgetRepository.java");

        Path holderMetadata = generatedDir.resolve("voel/HolderMetadata.java");
        assertTrue(Files.exists(holderMetadata), "Expected generated " + holderMetadata);
        String vmd = Files.readString(holderMetadata).replaceAll("\\s+", " ");
        // ListAttribute unifies one-to-many and element collections: the attribute NAME
        // can no longer separate the two classifications at the descriptor surface. The
        // discriminating fact is pinned instead in the owner descriptors' columnRefs join
        // shapes (navleaf pins the element-collection child-table/parent-fk join; rel and mmrel
        // pin the one-to-many inverse-fk join). What stays pinned here: a List<@Entity> in a
        // value object exposes the navigable list attribute with the ENTITY element type and
        // never contributes a column of its own.
        assertTrue(vmd.contains("new TypeMapping.Prop.EntityCollection(\"things\", null, null, null)"),
                "a List<@Entity> in a value object must expose a navigable entity-typed list attribute:"
                        + System.lineSeparator() + vmd);
        assertTrue(!vmd.contains("new ColumnRef(\"things"),
                "an entity list contributes no descriptor-level column:" + System.lineSeparator() + vmd);
    }

    @Test
    void generatedReadsUseRowMapperButNativeQueryUsesClass() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/demo3"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes3"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated3"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package demo3;
                public class Book {
                    public Long id;
                    public String title;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        Files.writeString(sourceDir.resolve("Shelf.java"), """
                package demo3;
                import jakarta.data.repository.By;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Find;
                import jakarta.data.repository.Query;
                import jakarta.data.repository.Repository;
                import java.util.List;
                import java.util.Optional;

                @Repository
                public interface Shelf extends CrudRepository<Book, Long> {
                    @Find
                    List<Book> findByTitle(@By("title") String title);

                    @Query("SELECT id, title FROM book WHERE title = ?1")
                    Optional<Book> nativeByTitle(String title);

                    @Query("SELECT id, title FROM book WHERE title = :title AND note <> '?1'")
                    Optional<Book> nativeByNamedTitle(String title);

                    @Query("SELECT id, title FROM book WHERE title = ?1")
                    Book[] nativeBooksByTitle(String title);
                }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        String impl = Files.readString(generatedDir.resolve("demo3/ShelfImpl.java"));
        assertTrue(impl.contains("SqlDialect SQL_DIALECT = H2Dialect.INSTANCE"),
                "generated SQL hooks must be centralized behind the dialect field:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains(".query(new BookRowMapper())"),
                "convention find must use the generated mapper:" + System.lineSeparator() + impl);
        assertTrue(impl.contains(".query(Book.class)"),
                "@Query native SQL must keep class-based mapping:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("SELECT id, title FROM book WHERE title = ?1"),
                "@Query native SQL must pass through without dialect rewriting:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_NATIVE_BY_TITLE).param(title)"),
                "positional @Query parameters must bind in declaration order:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_NATIVE_BY_NAMED_TITLE).param(\"title\", title)"),
                "named @Query parameters may use the source parameter name:" + System.lineSeparator() + impl);
        assertTrue(impl.contains("jdbcClient.sql(SQL_NATIVE_BOOKS_BY_TITLE).param(title)")
                        && impl.contains(".list().toArray(Book[]::new)"),
                "array @Query results must bind positional arguments and return the declared array:"
                        + System.lineSeparator() + impl);
        assertTrue(impl.contains("findById") && impl.contains(".query(new BookRowMapper()).optional()"),
                "findById must use the mapper:" + System.lineSeparator() + impl);
    }
}
