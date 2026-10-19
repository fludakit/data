package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.fludakit.data.processor.ProcessorHarness.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the Jakarta Data 1.0 {@code _Entity} static metamodel: its spec shape, the
 * {@code _<Entity><Field>} navigable member classes hanging off it, and the two ways a nav class
 * degrades — a name collision with another entity's metamodel, and a value-object cycle.
 *
 * <p>Split out of {@code RepositoryProcessorTest}; the shared compile-and-assert plumbing
 * lives in {@link ProcessorHarness}.</p>
 */
class StaticMetamodelTest {

    @TempDir
    Path workspace;

    @Test
    void staticMetamodelHasSpecShapeForEntityAttributes() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mmbook"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mmbook"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mmbook"));

        Files.writeString(sourceDir.resolve("Book.java"), """
                package mmbook;

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
                package mmbook;

                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;

                @Repository
                public interface Shelf extends CrudRepository<Book, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Book.java", "Shelf.java");

        Path metamodel = generatedDir.resolve("mmbook/_Book.java");
        assertTrue(Files.exists(metamodel), "Expected generated " + metamodel);
        // Whitespace-stripped so JavaPoet's soft wraps cannot break the positional pins.
        String mm = Files.readString(metamodel).replaceAll("\\s+", "");
        assertTrue(mm.contains("publicinterface_Book"), mm);
        assertTrue(mm.contains("@StaticMetamodel(Book.class)"), mm);
        assertTrue(mm.contains("@Generated(\"io.github.fludakit.data.processor.RepositoryProcessor\")"), mm);
        assertTrue(mm.contains("StringID=\"id\";"), mm);
        assertTrue(mm.contains("StringTITLE=\"title\";"), mm);
        // Slim record constructors take the name only; column placement is descriptor-owned.
        assertTrue(mm.contains("IdAttribute<Book>id=newIdAttributeRecord<>(\"id\",ID);") ||
                   mm.contains("IdAttribute<Book>id=newIdAttributeRecord<Book>(\"id\",ID);"), mm);
        assertTrue(mm.contains("TextScalarAttribute<Book>title=newTextScalarAttributeRecord<>(\"title\",TITLE);") ||
                   mm.contains("TextScalarAttribute<Book>title=newTextScalarAttributeRecord<Book>(\"title\",TITLE);"), mm);
    }

    @Test
    void staticMetamodelEmbeddedLeavesBecomeDotPathAttributes() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mmgp"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mmgp"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mmgp"));

        Files.writeString(sourceDir.resolve("Profile.java"), """
                package mmgp;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Profile {
                    @Identity Long id;
                    Address address;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Address getAddress() { return address; }
                    public void setAddress(Address address) { this.address = address; }
                }
                """);
        Files.writeString(sourceDir.resolve("Address.java"), """
                package mmgp;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Address {
                    String city;
                    Integer zip;
                    GeoPoint point;
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public Integer getZip() { return zip; }
                    public void setZip(Integer zip) { this.zip = zip; }
                    public GeoPoint getPoint() { return point; }
                    public void setPoint(GeoPoint point) { this.point = point; }
                }
                """);
        Files.writeString(sourceDir.resolve("GeoPoint.java"), """
                package mmgp;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class GeoPoint {
                    Double lat;
                    Double lng;
                    public Double getLat() { return lat; }
                    public void setLat(Double lat) { this.lat = lat; }
                    public Double getLng() { return lng; }
                    public void setLng(Double lng) { this.lng = lng; }
                }
                """);
        Files.writeString(sourceDir.resolve("ProfileRepository.java"), """
                package mmgp;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface ProfileRepository extends CrudRepository<Profile, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Profile.java", "Address.java", "GeoPoint.java", "ProfileRepository.java");

        String mm = Files.readString(generatedDir.resolve("mmgp/_Profile.java")).replaceAll("\\s+", "");
        assertTrue(mm.contains("StringID=\"id\";"), mm);
        assertTrue(mm.contains("StringADDRESS=\"address\";"), mm);
        // The embedded chain no longer flattens into the root interface: no dotted constants and no
        // leaf fields survive there — the leaves live in the navigable member classes.
        assertTrue(!mm.contains("StringADDRESS_CITY"), mm);
        assertTrue(!mm.contains("StringADDRESS_POINT_LAT"), mm);
        assertTrue(mm.contains("_ProfileAddressaddress=new_ProfileAddress();"), mm);
        assertTrue(mm.contains("IdAttribute<Profile>id=newIdAttributeRecord") && mm.contains("(\"id\",ID);"), mm);

        String addr = Files.readString(generatedDir.resolve("mmgp/_ProfileAddress.java")).replaceAll("\\s+", "");
        assertTrue(addr.contains("publicfinalclass_ProfileAddressimplementsEmbeddableAttribute<Profile,Address>"), addr);
        assertTrue(addr.contains("TextScalarAttribute<Profile>city=") && 
                   (addr.contains("newTextScalarAttributeRecord<>(\"city\",\"address.city\");") ||
                    addr.contains("newTextScalarAttributeRecord<Profile>(\"city\",\"address.city\");")),
                "embedded leaf keeps the dot-path name (column placement is descriptor-owned):"
                        + System.lineSeparator() + addr);
        assertTrue(addr.contains("SortableScalarAttribute<Profile>zip=") &&
                   (addr.contains("newSortableScalarAttributeRecord<>(\"zip\",\"address.zip\");") ||
                    addr.contains("newSortableScalarAttributeRecord<Profile>(\"zip\",\"address.zip\");")), addr);
        assertTrue(addr.contains("_ProfileAddressPointpoint=new_ProfileAddressPoint();"),
                "nested embedded recurses into its own nav class instead of flat leaves:" + System.lineSeparator() + addr);
        assertTrue(addr.contains("publicStringname(){return\"address\";}"), addr);
        assertTrue(addr.contains("publicStringpath(){return\"address\";}"), addr);
        assertTrue(!addr.contains("embedded()"),
                "the slim nav class must not emit the removed embedded() override:"
                        + System.lineSeparator() + addr);

        String point = Files.readString(generatedDir.resolve("mmgp/_ProfileAddressPoint.java")).replaceAll("\\s+", "");
        assertTrue(point.contains("publicfinalclass_ProfileAddressPointimplementsEmbeddableAttribute<Profile,GeoPoint>"),
                point);
        assertTrue(point.contains("SortableScalarAttribute<Profile>lat="
                        + "newSortableScalarAttributeRecord<>(\"lat\",\"address.point.lat\");"), point);
        assertTrue(point.contains("SortableScalarAttribute<Profile>lng="
                        + "newSortableScalarAttributeRecord<>(\"lng\",\"address.point.lng\");"), point);
        assertTrue(point.contains("publicStringname(){return\"point\";}"), point);
        assertTrue(point.contains("publicStringpath(){return\"address.point\";}"), point);
        // Value objects never get their own metamodel classes (aggregate roots only).
        assertTrue(!Files.exists(generatedDir.resolve("mmgp/_Address.java")),
                "no _VO class for a value object");
        assertTrue(!Files.exists(generatedDir.resolve("mmgp/_GeoPoint.java")),
                "no _VO class for a nested value object");
    }

    @Test
    void nestedValueObjectNavReferencesTopLevelMetadataSimpleName() throws Exception {
        // Re-check of the parked embeddedMetadataName bug: a @ValueObject declared as a nested
        // class has canonical FQN "pkg.Cfg.Geo", but its metadata is emitted as the TOP-LEVEL
        // <SimpleName>Metadata (pkg.GeoMetadata). The naive last-dot FQN split rendered
        // pkg.Cfg.GeoMetadata — a member class that never exists, breaking compilation of the nav.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/nestvo"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-nestvo"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-nestvo"));

        Files.writeString(sourceDir.resolve("Cfg.java"), """
                package nestvo;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Cfg {
                    @ValueObject
                    public static class Geo {
                        String tag;
                        public String getTag() { return tag; }
                        public void setTag(String tag) { this.tag = tag; }
                    }
                    Geo geo;
                    public Geo getGeo() { return geo; }
                    public void setGeo(Geo geo) { this.geo = geo; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package nestvo;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Holder {
                    @Identity Long id;
                    Cfg cfg;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Cfg getCfg() { return cfg; }
                    public void setCfg(Cfg cfg) { this.cfg = cfg; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holders.java"), """
                package nestvo;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface Holders extends CrudRepository<Holder, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Cfg.java", "Holder.java", "Holders.java");

        String cfgNav = Files.readString(generatedDir.resolve("nestvo/_HolderCfg.java")).replaceAll("\\s+", "");
        assertTrue(cfgNav.contains("_HolderCfgGeogeo=new_HolderCfgGeo();"), cfgNav);
        String geoNav = Files.readString(generatedDir.resolve("nestvo/_HolderCfgGeo.java")).replaceAll("\\s+", "");
        assertTrue(geoNav.contains("publicfinalclass_HolderCfgGeoimplementsEmbeddableAttribute<Holder,Cfg.Geo>"), geoNav);
        assertTrue(geoNav.contains("TextScalarAttribute<Holder>tag=newTextScalarAttributeRecord") && geoNav.contains("(\"tag\",\"cfg.geo.tag\");"),
                "the nav must expose the nested leaf via its owner-rooted dot path:" + System.lineSeparator() + geoNav);
    }

    @Test
    void crossEntityNavClassNameCollisionDegradesToNote() throws Exception {
        // Fix review: the nav-class collision guard must be GLOBAL across the
        // processing session, not per entity. Order.item (a relation member) names its nav class
        // pkg._OrderItem — the exact FQN of the OrderItem root's own static metamodel. The old
        // per-entity `claimed` set never saw the other entity's name, so the second
        // Filer.createSourceFile threw (printed as ERROR) and aborted the user build instead of
        // degrading. The metamodel wins the name (pre-claimed in the round pre-scan); the nav
        // degrades to the plain attribute record with a NOTE — and the whole build still compiles.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/ncol"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-ncol"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-ncol"));

        Files.writeString(sourceDir.resolve("OrderItem.java"), """
                package ncol;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class OrderItem {
                    @Identity Long id;
                    String sku;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getSku() { return sku; }
                    public void setSku(String sku) { this.sku = sku; }
                }
                """);
        Files.writeString(sourceDir.resolve("Order.java"), """
                package ncol;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Order {
                    @Identity Long id;
                    OrderItem item;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public OrderItem getItem() { return item; }
                    public void setItem(OrderItem item) { this.item = item; }
                }
                """);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package ncol;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Orders extends CrudRepository<Order, Long> { }
                """);
        Files.writeString(sourceDir.resolve("OrderItems.java"), """
                package ncol;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface OrderItems extends CrudRepository<OrderItem, Long> { }
                """);

        // compileWithDiagnostics asserts the whole round succeeds: before the fix (per-entity claimed) the
        // second createSourceFile of ncol._OrderItem printed an ERROR and failed this.
        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir,
                "Order.java", "OrderItem.java", "Orders.java", "OrderItems.java");

        // The root entity's OWN metamodel wins the name: _OrderItem is the interface, not a nav class.
        String itemMm = Files.readString(generatedDir.resolve("ncol/_OrderItem.java")).replaceAll("\\s+", "");
        assertTrue(itemMm.contains("publicinterface_OrderItem{"),
                "_OrderItem must be the OrderItem metamodel interface:" + System.lineSeparator() + itemMm);

        // The colliding nav degrades to the plain attribute record inside _Order.
        String orderMm = Files.readString(generatedDir.resolve("ncol/_Order.java")).replaceAll("\\s+", "");
        assertTrue(!orderMm.contains("new _OrderItem()"),
                "the colliding nav must NOT be instantiated:" + System.lineSeparator() + orderMm);
        assertTrue(orderMm.contains(
                        "EntityAttribute<Order,OrderItem>item=newEntityAttributeRecord<>(\"item\",ITEM);"),
                "the member must fall back to the plain entity attribute record:" + System.lineSeparator() + orderMm);

        // The degradation is recorded as a NOTE (never an ERROR — asserted by the success above).
        String notes = notesOf(diagnostics);
        assertTrue(notes.contains("static metamodel collision on navigable member 'item': type 'ncol._OrderItem' already claimed"),
                "the nav-collision NOTE must be recorded:" + System.lineSeparator() + notes);
    }

    @Test
    void valueObjectCycleStopsAtContainerOnlyNav() throws Exception {
        // A @ValueObject cycle (Root.head:Node, Node.tail:Tail, Tail.node:Node
        // — Java-legal) must bound at the descriptor layer and stop nav recursion: the back-reference
        // nav is a CONTAINER-ONLY class — it implements EmbeddableAttribute with NO leaf fields — and
        // the whole build still compiles (the null/cycle-definition degrade at RepositoryProcessor's
        // embeddedNav, previously unpinned).
        Path sourceDir = Files.createDirectories(workspace.resolve("src/cyc"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-cyc"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-cyc"));

        Files.writeString(sourceDir.resolve("Node.java"), """
                package cyc;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Node {
                    String label;
                    Tail tail;
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public Tail getTail() { return tail; }
                    public void setTail(Tail tail) { this.tail = tail; }
                }
                """);
        Files.writeString(sourceDir.resolve("Tail.java"), """
                package cyc;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Tail {
                    String name;
                    Node node;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Node getNode() { return node; }
                    public void setNode(Node node) { this.node = node; }
                }
                """);
        Files.writeString(sourceDir.resolve("Root.java"), """
                package cyc;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Root {
                    @Identity Long id;
                    Node head;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Node getHead() { return head; }
                    public void setHead(Node head) { this.head = head; }
                }
                """);
        Files.writeString(sourceDir.resolve("Roots.java"), """
                package cyc;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Roots extends CrudRepository<Root, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir, "Node.java", "Tail.java", "Root.java", "Roots.java");

        // The chain recurses one cycle lap: _RootHead → _RootHeadTail → the back-reference nav.
        String headNav = Files.readString(generatedDir.resolve("cyc/_RootHead.java")).replaceAll("\\s+", "");
        assertTrue(headNav.contains("TextScalarAttribute<Root>label=newTextScalarAttributeRecord<>(\"label\",\"head.label\");"),
                headNav);
        assertTrue(headNav.contains("_RootHeadTailtail=new_RootHeadTail();"), headNav);

        // The cycle-closing nav is CONTAINER-ONLY: no leaf records, no nested navs, just the
        // EmbeddableAttribute name() contract.
        String backNav = Files.readString(generatedDir.resolve("cyc/_RootHeadTailNode.java")).replaceAll("\\s+", "");
        assertTrue(backNav.contains("publicfinalclass_RootHeadTailNodeimplementsEmbeddableAttribute<Root,Node>"),
                "the back-reference must still be a typed container nav:" + System.lineSeparator() + backNav);
        assertTrue(!backNav.contains("AttributeRecord"),
                "the cycle nav must carry NO leaf fields:" + System.lineSeparator() + backNav);
        assertTrue(!backNav.contains("new _"),
                "the cycle nav must not recurse into further navs:" + System.lineSeparator() + backNav);
        assertTrue(backNav.contains("publicStringname(){return\"node\";}"), backNav);
        assertTrue(backNav.contains("publicStringpath(){return\"head.tail.node\";}"), backNav);
        // The cycle guard (descriptorFor) bounds the recursion with an EMPTY placeholder model that
        // keeps the type name — so the observable degrade is: no leaves, just the typed container
        // name() (the embedded() INSTANCE plumbing was removed entirely).
        assertTrue(!backNav.contains("embedded()"),
                "the slim back-reference nav must not emit the removed embedded() override:" + System.lineSeparator() + backNav);
    }

    @Test
    void relationAndElementCollectionNavLeavesPinFlattenedNaming() throws Exception {
        // Pins the two leaf-naming paths that were only inspected —
        // (a) the FK nav flattening the TARGET's nested embedded VO into definition-local chained
        // columns (depot.addr.street → "addr_street"), and (b) the EC nav leaves rebuilt from the
        // element VO's props (tags.sku → "sku"), verifying the "identical by construction" claim
        // for the elementColumns mirror.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/navleaf"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-navleaf"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-navleaf"));

        Files.writeString(sourceDir.resolve("Addr.java"), """
                package navleaf;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Addr {
                    String street;
                    Integer no;
                    public String getStreet() { return street; }
                    public void setStreet(String street) { this.street = street; }
                    public Integer getNo() { return no; }
                    public void setNo(Integer no) { this.no = no; }
                }
                """);
        Files.writeString(sourceDir.resolve("Depot.java"), """
                package navleaf;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Depot {
                    @Identity Long id;
                    String city;
                    Addr addr;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public Addr getAddr() { return addr; }
                    public void setAddr(Addr addr) { this.addr = addr; }
                }
                """);
        Files.writeString(sourceDir.resolve("Tags.java"), """
                package navleaf;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Tags {
                    String sku;
                    int qty;
                    public String getSku() { return sku; }
                    public void setSku(String sku) { this.sku = sku; }
                    public int getQty() { return qty; }
                    public void setQty(int qty) { this.qty = qty; }
                }
                """);
        Files.writeString(sourceDir.resolve("Load.java"), """
                package navleaf;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Load {
                    @Identity Long id;
                    Depot depot;
                    List<Tags> tags = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Depot getDepot() { return depot; }
                    public void setDepot(Depot depot) { this.depot = depot; }
                    public List<Tags> getTags() { return tags; }
                    public void setTags(List<Tags> tags) { this.tags = tags; }
                }
                """);
        Files.writeString(sourceDir.resolve("Loads.java"), """
                package navleaf;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Loads extends CrudRepository<Load, Long> { }
                """);
        Files.writeString(sourceDir.resolve("Depots.java"), """
                package navleaf;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Depots extends CrudRepository<Depot, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Addr.java", "Tags.java", "Depot.java", "Load.java", "Loads.java", "Depots.java");

        // (a) FK nav: owner-typed leaves over the definition's flattened LOCAL paths; the definition's
        // nested embedded VO flattens into definition-local chained columns, dotted in the path.
        String fkNav = Files.readString(generatedDir.resolve("navleaf/_LoadDepot.java")).replaceAll("\\s+", "");
        assertTrue(fkNav.contains("publicfinalclass_LoadDepotimplementsEntityAttribute<Load,Depot>"), fkNav);
        assertTrue(fkNav.contains("IdAttribute<Load>id=newIdAttributeRecord<>(\"id\",\"depot.id\");"), fkNav);
        assertTrue(fkNav.contains("TextScalarAttribute<Load>city=newTextScalarAttributeRecord<>(\"city\",\"depot.city\");"), fkNav);
        assertTrue(fkNav.contains("TextScalarAttribute<Load>street=newTextScalarAttributeRecord<>(\"street\",\"depot.addr.street\");"),
                "the definition's nested embedded leaf keeps the definition-local dotted path:"
                        + System.lineSeparator() + fkNav);
        assertTrue(fkNav.contains("SortableScalarAttribute<Load>no=newSortableScalarAttributeRecord<>(\"no\",\"depot.addr.no\");"), fkNav);

        // (b) EC nav: leaves rebuilt from the element VO props must carry the elementColumns
        // paths/columns exactly (child table contributes no table prefix to the leaf columns).
        String ecNav = Files.readString(generatedDir.resolve("navleaf/_LoadTags.java")).replaceAll("\\s+", "");
        // ListAttribute covers both one-to-many and element collections — the interface name
        // no longer separates them; the columnRefs join shape does (child table + parent fk).
        assertTrue(ecNav.contains("publicfinalclass_LoadTagsimplementsListAttribute<Load,Tags>"), ecNav);
        assertTrue(ecNav.contains("TextScalarAttribute<Load>sku=newTextScalarAttributeRecord<>(\"sku\",\"tags.sku\");"), ecNav);
        assertTrue(ecNav.contains("SortableScalarAttribute<Load>qty=newSortableScalarAttributeRecord<>(\"qty\",\"tags.qty\");"), ecNav);
    }

    @Test
    void staticMetamodelCoversRelationshipsAndSortPathsResolveThroughDescriptor() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mmrel"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mmrel"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mmrel"));

        Files.writeString(sourceDir.resolve("Parent.java"), """
                package mmrel;
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
        Files.writeString(sourceDir.resolve("Child.java"), """
                package mmrel;
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
        Files.writeString(sourceDir.resolve("ParentRepository.java"), """
                package mmrel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ParentRepository extends CrudRepository<Parent, Long> { }
                """);
        Files.writeString(sourceDir.resolve("ChildRepository.java"), """
                package mmrel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface ChildRepository extends CrudRepository<Child, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Parent.java", "Child.java", "ParentRepository.java", "ChildRepository.java");

        // Relationship properties surface as navigable member classes; the managed targets carry
        // owner-typed leaf fields for their flattened local paths plus the container contract.
        String pm = Files.readString(generatedDir.resolve("mmrel/_Parent.java")).replaceAll("\\s+", "");
        assertTrue(pm.contains("StringCHILDREN=\"children\";"), pm);
        assertTrue(pm.contains("_ParentChildrenchildren=new_ParentChildren();"), pm);
        assertTrue(pm.contains("StringPRIMARY=\"primary\";"), pm);
        assertTrue(pm.contains("_ParentPrimaryprimary=new_ParentPrimary();"), pm);
        assertTrue(pm.contains("TextScalarAttribute<Parent>name=newTextScalarAttributeRecord<>(\"name\",NAME);"), pm);
        String fkNav = Files.readString(generatedDir.resolve("mmrel/_ParentPrimary.java")).replaceAll("\\s+", "");
        assertTrue(fkNav.contains("publicfinalclass_ParentPrimaryimplementsEntityAttribute<Parent,Child>"), fkNav);
        assertTrue(fkNav.contains("IdAttribute<Parent>id=newIdAttributeRecord<>(\"id\",\"primary.id\");"),
                "the child's id must be a nav leaf named by its owner-rooted path:" + System.lineSeparator() + fkNav);
        String otmNav = Files.readString(generatedDir.resolve("mmrel/_ParentChildren.java")).replaceAll("\\s+", "");
        assertTrue(otmNav.contains("publicfinalclass_ParentChildrenimplementsListAttribute<Parent,Child>"), otmNav);
        assertTrue(otmNav.contains("ScalarAttribute<Parent>owner=newScalarAttributeRecord<>(\"owner\",\"children.owner\");"),
                "the child's own FK column path must flatten into the collection nav:" + System.lineSeparator() + otmNav);
        String cm = Files.readString(generatedDir.resolve("mmrel/_Child.java")).replaceAll("\\s+", "");
        assertTrue(cm.contains("_ChildOwnerowner=new_ChildOwner();"), cm);
        String ownerNav = Files.readString(generatedDir.resolve("mmrel/_ChildOwner.java")).replaceAll("\\s+", "");
        assertTrue(ownerNav.contains("publicfinalclass_ChildOwnerimplementsEntityAttribute<Child,Parent>"), ownerNav);
        assertTrue(ownerNav.contains("TextScalarAttribute<Child>name="
                        + "newTextScalarAttributeRecord<>(\"name\",\"owner.name\");"), ownerNav);
        assertTrue(ownerNav.contains("ScalarAttribute<Child>primary="
                        + "newScalarAttributeRecord<>(\"primary\",\"owner.primary\");"), ownerNav);

        // Sort dot paths now resolve through the descriptor: the flattened embedded leaves live in
        // the generated columns(), the paging impl calls <Entity>Metadata.columnRef, and an
        // EMBEDDED container contributes no column of its own.
        Files.writeString(sourceDir.resolve("Loc.java"), """
                package mmrel;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Loc {
                    String city;
                    Integer zip;
                    public String getCity() { return city; }
                    public void setCity(String city) { this.city = city; }
                    public Integer getZip() { return zip; }
                    public void setZip(Integer zip) { this.zip = zip; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holder.java"), """
                package mmrel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Holder {
                    @Identity Long id;
                    Loc loc;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Loc getLoc() { return loc; }
                    public void setLoc(Loc loc) { this.loc = loc; }
                }
                """);
        Files.writeString(sourceDir.resolve("Holders.java"), """
                package mmrel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface Holders extends CrudRepository<Holder, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Parent.java", "Child.java", "ParentRepository.java", "ChildRepository.java",
                "Loc.java", "Holder.java", "Holders.java");

        String impl = Files.readString(generatedDir.resolve("mmrel/Holders_.java"));
    }

    @Test
    void valueObjectDeclaringIdentityIsScalarWithNote() throws Exception {
        // Value object identity rule: a @ValueObject must have NO id. An @Identity field on a VO is a
        // scalar and emits an exact NOTE. Reached through an aggregate root that embeds the VO.
        Path sourceDir = Files.createDirectories(workspace.resolve("src/void1"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-void1"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-void1"));

        Files.writeString(sourceDir.resolve("Point.java"), """
                package void1;
                import io.github.fludakit.data.annotation.Identity;
                import io.github.fludakit.data.annotation.ValueObject;
                @ValueObject
                public class Point {
                    @Identity Long id;
                    double x;
                    double y;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public double getX() { return x; }
                    public void setX(double x) { this.x = x; }
                    public double getY() { return y; }
                    public void setY(double y) { this.y = y; }
                }
                """);
        Files.writeString(sourceDir.resolve("Line.java"), """
                package void1;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Line {
                    @Identity Long id;
                    Point start;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Point getStart() { return start; }
                    public void setStart(Point start) { this.start = start; }
                }
                """);
        Files.writeString(sourceDir.resolve("LineRepository.java"), """
                package void1;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository
                public interface LineRepository extends CrudRepository<Line, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir, "Point.java", "Line.java", "LineRepository.java");

        String notes = notesOf(diagnostics);
        assertTrue(notes.contains(
                        "value object 'Point' declares 'id'; ignored — value objects have no identity"),
                "the exact value-object identity NOTE must be emitted:" + System.lineSeparator() + notes);

        // The id is treated as a scalar: it flattens into the owner table as start_id, and the VO
        // metadata reports a null id (value objects have no identity accessor).
    }
}
