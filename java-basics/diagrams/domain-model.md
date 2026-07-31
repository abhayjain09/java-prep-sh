# Domain Model — Order/Inventory System

This is the domain used across every module in this repo. Module 1 implements exactly what's shown here as plain Java objects (no framework). Later modules add persistence (JPA annotations), REST controllers, security constraints, and eventually a `Payment` aggregate — without changing this core shape.

```mermaid
classDiagram
    class Customer {
        -String id
        -String name
        -String email
        +Customer(id, name, email)
    }

    class Product {
        -String sku
        -String name
        -BigDecimal price
        +Product(sku, name, price)
    }

    class OrderLine {
        -Product product
        -int quantity
        +OrderLine(product, quantity)
        +lineTotal() BigDecimal
    }

    class Order {
        -String id
        -Customer customer
        -List~OrderLine~ lines
        -OrderStatus status
        +addLine(OrderLine)
        +getLines() List~OrderLine~
        +totalAmount() BigDecimal
        +transitionTo(OrderStatus)
    }

    class OrderStatus {
        <<enumeration>>
        PENDING
        CONFIRMED
        SHIPPED
        DELIVERED
        CANCELLED
        +canTransitionTo(OrderStatus) boolean
    }

    class Inventory {
        -Map~String, Integer~ stockBySku
        +reserve(sku, quantity)
        +release(sku, quantity)
        +restock(sku, quantity)
        +stockOf(sku) int
    }

    class InsufficientStockException {
        +InsufficientStockException(sku, requested, available)
    }

    class OrderService {
        -Inventory inventory
        +placeOrder(Customer, List~OrderLine~) Order
        +totalSpentBy(Customer, List~Order~) BigDecimal
        +ordersByStatus(List~Order~) Map~OrderStatus, List~Order~~
    }

    Order "1" *-- "many" OrderLine : composed of
    OrderLine "many" --> "1" Product : references
    Order "many" --> "1" Customer : placed by
    Order --> OrderStatus : has a
    OrderService --> Inventory : uses
    OrderService ..> InsufficientStockException : throws
    Inventory ..> InsufficientStockException : throws
```

**Key relationships to notice:**
- `Order` *composes* `OrderLine` (filled diamond) — an `OrderLine` cannot outlive or exist independently of its `Order` in this model; this is the "part-whole, part cannot exist without whole" flavor of composition.
- `OrderLine` *references* `Product`, it does not extend it (open arrow) — see the README's OOP section for why inheritance would be wrong here.
- `Inventory` is intentionally decoupled from `Order`/`OrderLine` — it only knows about SKUs and quantities, not the order domain. `OrderService` is what ties them together. This separation is what makes Module 2 (bulk import) and Module 5 (REST layer) able to evolve `Order` and `Inventory` independently.
