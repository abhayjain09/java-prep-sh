package com.interviewprep.orders.jvm;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;

import java.math.BigDecimal;
import java.nio.ByteBuffer;

/**
 * Walks through where each piece of a running Order/Inventory workload
 * physically lives: heap, per-thread stack, metaspace, or off-heap/direct
 * memory. See diagrams/heap-regions.md for the picture this code narrates.
 *
 * IMPORTANT HONESTY NOTE: a running Java program cannot directly introspect
 * "is this specific object currently in Eden, a Survivor space, or Old
 * Gen?" — the JVM deliberately doesn't expose that as an API, because an
 * object's region is an implementation detail that can change on every GC.
 * What CAN be inspected (outside this sandbox, since it has no `java`
 * binary): aggregate region sizes/occupancy via `jcmd <pid> GC.heap_info`,
 * `jconsole`/`jvisualvm`'s Memory tab, or a `-Xlog:gc+heap=info` log line
 * after each collection. This class instead uses comments to point out,
 * line by line, which memory region *conceptually* owns each value — that
 * mapping is deterministic and doesn't need a profiler to explain.
 */
public class MemoryRegionsDemo {

    public static void main(String[] args) {
        // ---------------------------------------------------------------
        // STACK: 'productCount' is a local primitive. It lives entirely in
        // this thread's stack frame for main() — no heap allocation at all,
        // no GC involvement, reclaimed the instant main() returns (or this
        // block's scope ends, once escape-analysis-unfriendly bytecode is
        // ignored). This is the cheapest possible storage in the JVM.
        // ---------------------------------------------------------------
        int productCount = 3;

        // ---------------------------------------------------------------
        // HEAP (young gen / Eden): every 'new Product(...)' call allocates
        // an actual Product object in Eden via this thread's Thread-Local
        // Allocation Buffer (TLAB) — a chunk of Eden pre-claimed by this
        // thread so the allocation itself is a lock-free pointer bump, not
        // a synchronized operation. The reference returned sits on the
        // STACK (local variable 'laptop'); the OBJECT it points to is on
        // the HEAP. This distinction — reference vs. referent — is the
        // single most common conceptual gap interviewers probe for.
        // ---------------------------------------------------------------
        Product laptop = new Product("SKU-LAPTOP", "Laptop", new BigDecimal("1200.00"));
        Product mouse = new Product("SKU-MOUSE", "Wireless Mouse", new BigDecimal("25.00"));
        Product keyboard = new Product("SKU-KEYBOARD", "Mechanical Keyboard", new BigDecimal("80.00"));
        Product[] catalog = {laptop, mouse, keyboard}; // the ARRAY object is also heap-allocated

        System.out.println("Built a catalog of " + productCount + " products (array length: "
                + catalog.length + ")");

        Customer customer = new Customer("CUST-1", "Ada Lovelace", "ada@example.com");

        // Order/OrderLine: same story as Product above. In a real
        // high-throughput OrderService, THIS is the allocation pattern
        // that determines young-gen GC frequency — see
        // AllocationPatternsDemo.java for a version that simulates volume.
        Order order = new Order("ORD-1", customer);
        order.addLine(new OrderLine(laptop, 1));
        order.addLine(new OrderLine(mouse, 2));
        System.out.println("Built: " + order);

        // ---------------------------------------------------------------
        // METASPACE: 'order.getClass()' doesn't allocate a new Order — it
        // returns the single, JVM-wide Class<Order> metadata object, whose
        // backing data (method bytecode, field layout, constant pool,
        // vtable) lives in METASPACE (native memory, outside the heap
        // entirely). Every instance of Order shares this ONE piece of
        // metadata, no matter how many million Order objects exist on the
        // heap at once. This is why creating orders doesn't inflate
        // metaspace — only LOADING NEW CLASSES does (e.g. a badly designed
        // plugin system that generates and loads a fresh class per
        // request would leak metaspace, not heap).
        // ---------------------------------------------------------------
        Class<?> orderClass = order.getClass();
        System.out.println("Order's class metadata object: " + orderClass
                + " (lives in metaspace, shared by every Order instance)");

        // ---------------------------------------------------------------
        // OFF-HEAP / DIRECT MEMORY: allocateDirect() asks the OS for a
        // block of NATIVE memory outside the Java heap entirely — the
        // ByteBuffer OBJECT itself (a small wrapper with a pointer field)
        // is on the heap, but the actual byte storage it wraps is not.
        // This is exactly what Module 2's java.nio channel-based I/O uses
        // under the hood for bulk order CSV import/export: writing to a
        // direct buffer lets the OS write straight from that native memory
        // during a syscall, skipping an extra copy through a heap byte[].
        // COST: direct buffers are NOT tracked by -Xmx or a normal heap
        // histogram; only -XX:MaxDirectMemorySize bounds them, and their
        // native memory is only freed once the wrapping ByteBuffer object
        // is garbage collected and its Cleaner runs — meaning direct
        // buffers can outlive their "logical" usefulness for a full GC
        // cycle, which is why direct-buffer-heavy code should reuse
        // buffers (a pool) rather than allocate-and-discard per request.
        // ---------------------------------------------------------------
        ByteBuffer bulkImportBuffer = ByteBuffer.allocateDirect(64 * 1024); // 64 KB, off-heap
        bulkImportBuffer.putInt(catalog.length);
        bulkImportBuffer.flip();
        System.out.println("Allocated a 64KB DIRECT (off-heap) buffer for bulk order I/O; "
                + "first int written: " + bulkImportBuffer.getInt());

        System.out.println();
        System.out.println("Summary of what you just built and where it lives:");
        System.out.println("  STACK      : productCount (int), every object reference variable above");
        System.out.println("  HEAP       : every Product/Customer/Order/OrderLine/array/ByteBuffer OBJECT");
        System.out.println("  METASPACE  : Order.class / Product.class / ... metadata (one copy, ever)");
        System.out.println("  OFF-HEAP   : the 64KB backing store behind bulkImportBuffer");
    }
}
