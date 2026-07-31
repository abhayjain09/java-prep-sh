# All 23 GoF Patterns, Categorized

Extracted from [../README.md](../README.md) section "All 23 GoF Patterns, Categorized" so this module has a `diagrams/` folder consistent with every other module in the repo. Read the README section for the full "how to read this in an interview" discussion — this file is the diagram alone, for quick reference.

```mermaid
flowchart TB
    subgraph Creational["CREATIONAL — how objects get created"]
        direction TB
        Singleton["Singleton<br/>InventoryRegistry"]
        FactoryMethod["Factory Method<br/>PaymentProcessorCreator"]
        AbstractFactory["Abstract Factory<br/>OrderDocumentFactory (US/EU)"]
        Builder["Builder<br/>OrderRequestBuilder"]
        Prototype["Prototype<br/>OrderTemplate.copy()"]
    end

    subgraph Structural["STRUCTURAL — how objects are composed"]
        direction TB
        Adapter["Adapter<br/>PaymentGatewayAdapter"]
        Bridge["Bridge<br/>OrderNotification x NotificationSender"]
        Composite["Composite<br/>ProductLeaf / ProductBundle"]
        Decorator["Decorator<br/>GiftWrap / ExpressShipping pricers"]
        Facade["Facade<br/>CheckoutFacade"]
        Flyweight["Flyweight<br/>ProductFlyweightFactory"]
        Proxy["Proxy<br/>SecuredInventoryProxy"]
    end

    subgraph Behavioral["BEHAVIORAL — how objects communicate"]
        direction TB
        Strategy["Strategy<br/>DiscountStrategy"]
        Observer["Observer<br/>OrderStatusPublisher"]
        Command["Command<br/>PlaceOrderCommand / CancelOrderCommand"]
        State["State<br/>OrderState (Pending..Cancelled)"]
        TemplateMethod["Template Method<br/>OrderProcessorTemplate"]
        ChainOfResponsibility["Chain of Responsibility<br/>Stock -> Fraud -> Credit"]
        Iterator["Iterator<br/>InventoryCatalog (in-stock only)"]
        Mediator["Mediator<br/>CheckoutMediator"]
        Memento["Memento<br/>OrderMemento / OrderHistoryCaretaker"]
        Visitor["Visitor<br/>TaxCalculationVisitor / ShippingWeightVisitor"]
        Interpreter["Interpreter<br/>DiscountRuleExpression tree"]
    end

    Creational --> Structural --> Behavioral
```

**How to read this diagram in an interview:** if asked "what are the three GoF categories," the useful answer isn't just naming them — it's explaining the organizing question each category answers: Creational = "how does this object come into existence?", Structural = "how do these objects fit together into larger structures?", Behavioral = "how do these objects communicate and share responsibility for a task?" A pattern's category tells you what kind of problem it addresses before you even recall its mechanics.
