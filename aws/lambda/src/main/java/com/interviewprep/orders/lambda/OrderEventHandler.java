package com.interviewprep.orders.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * TEACHING ARTIFACT — illustrative only. This class is NOT wired into a real
 * build in this repo (no pom.xml dependency resolution, no deployment
 * package, nothing invoked against a real AWS account). It exists to show
 * the realistic shape of a Java Lambda that consumes SQS-triggered order
 * events, as described in ../../../../../../../../README.md section 6 and
 * the fulfillment sequence diagram in ../../../../../../../../diagrams/
 * event-driven-fulfillment-sequence.md.
 *
 * TO ACTUALLY COMPILE THIS FOR REAL, a project would need (illustrative
 * Maven coordinates — no pom.xml is provided in this teaching module):
 *
 *   <dependency>
 *     <groupId>com.amazonaws</groupId>
 *     <artifactId>aws-lambda-java-core</artifactId>
 *     <version>1.2.3</version>
 *   </dependency>
 *   <dependency>
 *     <groupId>com.amazonaws</groupId>
 *     <artifactId>aws-lambda-java-events</artifactId>
 *     <version>3.11.4</version>
 *   </dependency>
 *
 * aws-lambda-java-core supplies RequestHandler/Context; aws-lambda-java-events
 * supplies the SQSEvent/SQSBatchResponse POJOs that mirror the JSON shape
 * Lambda actually delivers when it invokes this handler in response to an
 * SQS trigger (configured as an "event source mapping" between the
 * orders-fulfillment-queue from ../../terraform/sqs.tf and this function —
 * that wiring is infrastructure configuration, not code, and is not
 * modeled as a .tf resource in this teaching module to keep sqs.tf focused).
 *
 * WHY RequestHandler<SQSEvent, SQSBatchResponse> AND NOT <SQSEvent, Void>:
 * returning Void tells Lambda "the whole batch either succeeded or it
 * didn't" — if ONE message in a batch of 10 fails, Lambda (by default,
 * without partial-batch-response support) treats the ENTIRE batch as failed
 * and redelivers all 10, including the 9 that already succeeded. Returning
 * an SQSBatchResponse listing only the specific message IDs that failed
 * (with the queue's "ReportBatchItemFailures" event-source-mapping setting
 * enabled) lets Lambda redeliver ONLY the failed ones. This directly matters
 * for the order-fulfillment queue: redelivering a message that already
 * succeeded would re-trigger the Step Functions saga for an order that was
 * already fulfilled, unless every downstream step is perfectly idempotent —
 * partial-batch-response is the cheaper fix to get right first. (A simpler
 * RequestHandler<SQSEvent, Void> signature, as named in the module brief, is
 * also shown further below as SimpleOrderEventHandler for comparison —
 * see EXPLANATION.md for when each shape is the right choice.)
 */
public class OrderEventHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    /**
     * Invoked once per batch of messages Lambda pulls from the
     * orders-fulfillment-queue (see ../../terraform/sqs.tf). Each message
     * body is the JSON-serialized OrderPlaced event published by the
     * Spring Boot API after OrderService.placeOrder() (Module 1) commits.
     *
     * PRODUCTION NOTE: this handler is deliberately thin — it deserializes
     * the event and starts a Step Functions execution (README.md section 7)
     * rather than performing the full reserve/charge/ship/notify saga
     * in-line. Putting the whole saga's branching and compensation logic
     * directly in a Lambda handler would recreate, by hand, exactly the
     * orchestration/retry/compensation machinery Step Functions already
     * provides — see README.md section 7 for why that's the wrong layer
     * for this logic to live in.
     */
    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processOrderPlacedEvent(message, context);
            } catch (Exception e) {
                // WHY CATCH BROADLY HERE (unusually, versus the narrow-catch
                // guidance elsewhere in this repo): this is a batch-processing
                // boundary. An unexpected exception from ONE message must not
                // crash the whole invocation and lose visibility into which
                // specific message failed — record it as a per-item failure
                // and let SQS's redrive policy (maxReceiveCount in sqs.tf)
                // handle retries, eventually routing a truly poison message
                // to the DLQ. This is the one place in the fulfillment path
                // where "catch Exception broadly" is the correct call,
                // specifically because every failure is individually logged
                // and individually retried — nothing is silently swallowed.
                context.getLogger().log(String.format(
                        "Failed to process message %s: %s%n",
                        message.getMessageId(), e.getMessage()));
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }

        return new SQSBatchResponse(failures);
    }

    /**
     * Parses one SQS message body (an OrderPlaced event) and starts the
     * fulfillment saga for it.
     *
     * In a real implementation, this would:
     *   1. Deserialize message.getBody() (JSON) into an OrderPlacedEvent DTO
     *      — a real project would depend on a shared module (or duplicate a
     *      minimal DTO) mirroring the domain/Order shape from java-basics/,
     *      NOT the JPA entity or web DTO from spring/ directly, to keep this
     *      Lambda's deployment package small and decoupled from the API's
     *      full dependency tree.
     *   2. Call AWS Step Functions' StartExecution API (via the AWS SDK for
     *      Java v2's software.amazon.awssdk:sfn module — another dependency
     *      not declared here, kept out of scope for this illustrative file)
     *      passing the order ID and line items as the execution input.
     *   3. Return normally (implicit success) so this message is deleted
     *      from the queue; on ANY failure above, an exception propagates up
     *      to handleRequest's catch block, which reports it as a per-item
     *      batch failure instead of deleting the message — leaving it for
     *      SQS to redeliver per the queue's visibility timeout and
     *      redrive policy.
     *
     * IDEMPOTENCY NOTE: SQS is at-least-once delivery (see README.md
     * section 6) — this method (or, better, the Step Functions execution it
     * starts) MUST tolerate being invoked twice for the same order without
     * double-reserving inventory or double-charging payment. A common real
     * fix: use the order ID as the Step Functions execution NAME (not just
     * input) — Step Functions rejects starting a second execution with a
     * name that's already running/succeeded within the same state machine,
     * turning "redelivered message" into a safe no-op rather than a
     * duplicate saga run.
     */
    private void processOrderPlacedEvent(SQSEvent.SQSMessage message, Context context) {
        context.getLogger().log("Processing OrderPlaced event: " + message.getBody());

        // Illustrative only — no real SDK calls are made from this teaching
        // module. A real implementation replaces this comment with:
        //   OrderPlacedEvent orderEvent = objectMapper.readValue(message.getBody(), OrderPlacedEvent.class);
        //   sfnClient.startExecution(StartExecutionRequest.builder()
        //       .stateMachineArn(FULFILLMENT_SAGA_STATE_MACHINE_ARN)
        //       .name(orderEvent.orderId())   // idempotency key — see note above
        //       .input(message.getBody())
        //       .build());
    }

    /**
     * The simpler signature named in this module's brief —
     * {@code RequestHandler<SQSEvent, Void>}. Kept here, unused by the
     * event-source mapping, purely so both shapes are visible side by side
     * for comparison; see EXPLANATION.md for a full discussion of when the
     * simpler Void-returning form is an acceptable choice (small, low-
     * volume queues where an all-or-nothing batch retry is an acceptable
     * cost) versus when the SQSBatchResponse partial-failure form above is
     * worth the extra ceremony (higher-volume batches, or any batch where
     * downstream side effects aren't cheaply idempotent).
     */
    static class SimpleOrderEventHandler implements RequestHandler<SQSEvent, Void> {
        private final OrderEventHandler delegate = new OrderEventHandler();

        @Override
        public Void handleRequest(SQSEvent event, Context context) {
            // Throwing here (rather than returning a partial-failure list)
            // tells Lambda/SQS "the WHOLE batch failed" if even one message
            // throws — every message in the batch becomes visible again and
            // is redelivered, including ones that already succeeded. Simpler
            // to write, coarser in behavior — the trade-off named above.
            for (SQSEvent.SQSMessage message : event.getRecords()) {
                delegate.processOrderPlacedEvent(message, context);
            }
            return null;
        }
    }
}
