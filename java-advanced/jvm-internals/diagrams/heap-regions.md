# JVM Memory Layout — Heap, Stack, Metaspace, Off-Heap

Where does an `Order` actually live while `OrderService.placeOrder(...)` is
running? This diagram is the answer key referenced throughout
[README.md](../README.md) section 1.

```mermaid
flowchart TB
    subgraph JVMProcess["JVM Process Memory"]
        direction TB

        subgraph Heap["Heap (managed, garbage collected)"]
            direction TB
            subgraph Young["Young Generation"]
                direction LR
                Eden["Eden\n(new Order / OrderLine\nobjects allocated here first,\nvia a per-thread TLAB)"]
                S0["Survivor S0"]
                S1["Survivor S1"]
                Eden --> S0
                Eden --> S1
            end
            Young -- "promoted after\nsurviving N minor GCs\n(-XX:MaxTenuringThreshold)" --> Old
            Old["Old / Tenured Generation\n(long-lived objects: e.g. an\nin-memory Inventory map that\nlives for the app's lifetime)"]
        end

        Metaspace["Metaspace (native memory, NOT heap)\nClass metadata: Order.class, OrderLine.class,\nmethod bytecode, the constant pool.\nReplaced PermGen in Java 8.\nBounded by -XX:MaxMetaspaceSize (unbounded by default)."]

        subgraph Stacks["Per-Thread Stacks (native memory, NOT heap)"]
            direction LR
            StackA["Thread 'http-nio-1' stack\nlocal var: Order order (a REFERENCE)\nlocal var: int quantity (a primitive VALUE)\none frame per method call"]
            StackB["Thread 'http-nio-2' stack\n(own frames, own locals —\nstacks are never shared\nbetween threads)"]
        end

        Direct["Direct / Off-Heap Memory (native memory, NOT heap)\nByteBuffer.allocateDirect(...) — e.g. Module 2's\nNIO channel buffers for bulk order CSV import/export.\nNot scanned by GC; freed via Cleaner/PhantomReference\nwhen the ByteBuffer object itself is collected.\nBounded by -XX:MaxDirectMemorySize."]
    end

    StackA -. "order variable POINTS INTO" .-> Eden
    Heap -. "class metadata looked up via" .-> Metaspace
```

## Reading this diagram

- **Heap** is the only region the classic `-Xms`/`-Xmx` flags size, and the
  only region GC algorithms (Serial/Parallel/G1/ZGC/Shenandoah) collect.
- **Stack** is per-thread, sized by `-Xss`, and holds *frames* — local
  variables (both primitives and object *references*, never the objects
  themselves) and the call chain. A reference on the stack points into the
  heap; the referenced `Order` object itself is never on the stack.
- **Metaspace** replaced PermGen (Java 8+) specifically so that class
  metadata volume is no longer capped by a heap-adjacent, hard-to-size
  region — see README section 1 for the historical `OutOfMemoryError:
  PermGen space` incidents this fixed, and the new failure mode
  (`OutOfMemoryError: Metaspace`, or unbounded native memory growth) it
  introduced instead.
- **Direct/off-heap memory** is invisible to `-Xmx` and to most heap
  histograms — a service that creates large direct `ByteBuffer`s (Module 2:
  bulk CSV/ZIP import of orders) without bounding `-XX:MaxDirectMemorySize`
  can exhaust native memory while the heap dashboard looks perfectly healthy.

See [gc-cycle-flow.md](gc-cycle-flow.md) for how objects move Eden → Survivor
→ Old, and [jit-tiered-compilation.md](jit-tiered-compilation.md) for how the
JIT can sometimes avoid putting an object in Eden at all (escape analysis /
scalar replacement).
