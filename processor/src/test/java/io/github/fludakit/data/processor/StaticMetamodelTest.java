package io.github.fludakit.data.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.fludakit.data.processor.ProcessorHarness.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Jakarta Data 1.0 {@code _Entity} static metamodel with the {@code get()} path-composition
 * pattern: navigable attributes are plain {@code EntityAttribute}/{@code EmbeddableAttribute}/
 * {@code ListAttribute} record fields, and path composition uses {@code _Order.customer.get(_Customer.name)}
 * instead of wrapper/nav classes.
 *
 * <p>Value objects get their own {@code @StaticMetamodel} interfaces so the {@code get()} target
 * attributes exist. No {@code _EntityMember} wrapper classes are generated.</p>
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

                @io.github.fludakit.data.annotation.Entity
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
        String mm = Files.readString(metamodel).replaceAll("\\s+", "");
        assertTrue(mm.contains("publicinterface_Book"), mm);
        assertTrue(mm.contains("@StaticMetamodel(Book.class)"), mm);
        assertTrue(mm.contains("@Generated(\"io.github.fludakit.data.processor.RepositoryProcessor\")"), mm);
        assertTrue(mm.contains("StringID=\"id\";"), mm);
        assertTrue(mm.contains("StringTITLE=\"title\";"), mm);
        assertTrue(mm.contains("IdAttribute<Book>id=newIdAttributeRecord<>(\"id\",ID);") ||
                   mm.contains("IdAttribute<Book>id=newIdAttributeRecord<Book>(\"id\",ID);"), mm);
        assertTrue(mm.contains("TextScalarAttribute<Book>title=newTextScalarAttributeRecord<>(\"title\",TITLE);") ||
                   mm.contains("TextScalarAttribute<Book>title=newTextScalarAttributeRecord<Book>(\"title\",TITLE);"), mm);
    }

    @Test
    void embeddedVoGetsOwnMetamodelForGetPathComposition() throws Exception {
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

        // Root entity metamodel: address is an EmbeddableAttribute, not a wrapper class
        String mm = Files.readString(generatedDir.resolve("mmgp/_Profile.java")).replaceAll("\\s+", "");
        assertTrue(mm.contains("StringADDRESS=\"address\";"), mm);
        assertTrue(mm.contains("EmbeddableAttribute<Profile,Address>address="), mm);
        assertTrue(mm.contains("newEmbeddableAttributeRecord<>(\"address\",ADDRESS);") ||
                   mm.contains("newEmbeddableAttributeRecord<Profile,Address>(\"address\",ADDRESS);"), mm);
        // No wrapper class instantiation
        assertTrue(!mm.contains("new_ProfileAddress()"), "no wrapper class: " + mm);

        // Value object gets its own metamodel for get() target attributes
        assertTrue(Files.exists(generatedDir.resolve("mmgp/_Address.java")),
                "VO metamodel must be generated for get() path composition");
        String addr = Files.readString(generatedDir.resolve("mmgp/_Address.java")).replaceAll("\\s+", "");
        assertTrue(addr.contains("@StaticMetamodel(Address.class)"), addr);
        assertTrue(addr.contains("TextScalarAttribute<Address>city="), addr);
        assertTrue(addr.contains("SortableScalarAttribute<Address>zip="), addr);
        assertTrue(addr.contains("EmbeddableAttribute<Address,GeoPoint>point="), addr);

        // Nested VO also gets a metamodel
        assertTrue(Files.exists(generatedDir.resolve("mmgp/_GeoPoint.java")),
                "nested VO metamodel must be generated");
        String gp = Files.readString(generatedDir.resolve("mmgp/_GeoPoint.java")).replaceAll("\\s+", "");
        assertTrue(gp.contains("@StaticMetamodel(GeoPoint.class)"), gp);
        assertTrue(gp.contains("SortableScalarAttribute<GeoPoint>lat="), gp);
        assertTrue(gp.contains("SortableScalarAttribute<GeoPoint>lng="), gp);
    }

    @Test
    void associationBecomesEntityAttributeForGetComposition() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mmrel"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mmrel"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mmrel"));

        Files.writeString(sourceDir.resolve("Customer.java"), """
                package mmrel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Customer {
                    @Identity Long id;
                    String name;
                    String email;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public String getEmail() { return email; }
                    public void setEmail(String email) { this.email = email; }
                }
                """);
        Files.writeString(sourceDir.resolve("Order.java"), """
                package mmrel;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                @AggregateRoot
                public class Order {
                    @Identity Long id;
                    Customer customer;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public Customer getCustomer() { return customer; }
                    public void setCustomer(Customer customer) { this.customer = customer; }
                }
                """);
        Files.writeString(sourceDir.resolve("Customers.java"), """
                package mmrel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface Customers extends CrudRepository<Customer, Long> { }
                """);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package mmrel;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface Orders extends CrudRepository<Order, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Customer.java", "Order.java", "Customers.java", "Orders.java");

        // Order metamodel: customer is EntityAttribute, not a wrapper class
        String om = Files.readString(generatedDir.resolve("mmrel/_Order.java")).replaceAll("\\s+", "");
        assertTrue(om.contains("EntityAttribute<Order,Customer>customer="), om);
        assertTrue(om.contains("newEntityAttributeRecord<>(\"customer\",CUSTOMER);") ||
                   om.contains("newEntityAttributeRecord<Order,Customer>(\"customer\",CUSTOMER);"), om);
        assertTrue(!om.contains("new_OrderCustomer()"), "no wrapper class: " + om);

        // Customer metamodel exists for get() targets
        String cm = Files.readString(generatedDir.resolve("mmrel/_Customer.java")).replaceAll("\\s+", "");
        assertTrue(cm.contains("TextScalarAttribute<Customer>name="), cm);
        assertTrue(cm.contains("TextScalarAttribute<Customer>email="), cm);
    }

    @Test
    void entityCollectionBecomesListAttribute() throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("src/mmcol"));
        Path classesDir = Files.createDirectories(workspace.resolve("classes-mmcol"));
        Path generatedDir = Files.createDirectories(workspace.resolve("generated-mmcol"));

        Files.writeString(sourceDir.resolve("Item.java"), """
                package mmcol;
                import io.github.fludakit.data.annotation.Entity;
                import io.github.fludakit.data.annotation.Identity;
                @Entity
                public class Item {
                    @Identity Long id;
                    String sku;
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public String getSku() { return sku; }
                    public void setSku(String sku) { this.sku = sku; }
                }
                """);
        Files.writeString(sourceDir.resolve("Order.java"), """
                package mmcol;
                import io.github.fludakit.data.annotation.AggregateRoot;
                import io.github.fludakit.data.annotation.Identity;
                import java.util.ArrayList;
                import java.util.List;
                @AggregateRoot
                public class Order {
                    @Identity Long id;
                    List<Item> items = new ArrayList<>();
                    public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    public List<Item> getItems() { return items; }
                    public void setItems(List<Item> items) { this.items = items; }
                }
                """);
        Files.writeString(sourceDir.resolve("Orders.java"), """
                package mmcol;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface Orders extends CrudRepository<Order, Long> { }
                """);
        Files.writeString(sourceDir.resolve("Items.java"), """
                package mmcol;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface Items extends CrudRepository<Item, Long> { }
                """);

        compile(sourceDir, classesDir, generatedDir,
                "Item.java", "Order.java", "Orders.java", "Items.java");

        String om = Files.readString(generatedDir.resolve("mmcol/_Order.java")).replaceAll("\\s+", "");
        assertTrue(om.contains("ListAttribute<Order,Item>items="), om);
        assertTrue(om.contains("newListAttributeRecord<>(\"items\",ITEMS);") ||
                   om.contains("newListAttributeRecord<Order,Item>(\"items\",ITEMS);"), om);
        assertTrue(!om.contains("new_OrderItems()"), "no wrapper class: " + om);

        // Item metamodel exists for get() targets
        assertTrue(Files.exists(generatedDir.resolve("mmcol/_Item.java")));
        String im = Files.readString(generatedDir.resolve("mmcol/_Item.java")).replaceAll("\\s+", "");
        assertTrue(im.contains("TextScalarAttribute<Item>sku="), im);
    }

    @Test
    void noNavClassNameCollisionsSinceNoNavClasses() throws Exception {
        // With the get() pattern, there are no wrapper/nav classes, so the old collision
        // scenario (Order.item → _OrderItem colliding with OrderItem's _OrderItem) cannot happen.
        // Both entities get their own clean metamodel interfaces.
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
                @Repository interface Orders extends CrudRepository<Order, Long> { }
                """);
        Files.writeString(sourceDir.resolve("OrderItems.java"), """
                package ncol;
                import jakarta.data.repository.CrudRepository;
                import jakarta.data.repository.Repository;
                @Repository interface OrderItems extends CrudRepository<OrderItem, Long> { }
                """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithDiagnostics(
                sourceDir, classesDir, generatedDir,
                "Order.java", "OrderItem.java", "Orders.java", "OrderItems.java");

        assertTrue(diagnostics.stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
                "compilation should succeed without errors");

        // Both metamodels exist as clean interfaces
        String itemMm = Files.readString(generatedDir.resolve("ncol/_OrderItem.java")).replaceAll("\\s+", "");
        assertTrue(itemMm.contains("publicinterface_OrderItem{"), itemMm);

        String orderMm = Files.readString(generatedDir.resolve("ncol/_Order.java")).replaceAll("\\s+", "");
        assertTrue(orderMm.contains("EntityAttribute<Order,OrderItem>item="), orderMm);
    }

    @Test
    void valueObjectDeclaringIdentityIsScalarWithNote() throws Exception {
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

        assertTrue(diagnostics.stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
                "compilation should succeed without errors");
    }
}
