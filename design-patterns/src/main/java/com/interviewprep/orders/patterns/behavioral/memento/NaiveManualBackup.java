package com.interviewprep.orders.patterns.behavioral.memento;

import com.interviewprep.orders.domain.OrderLine;

import java.util.ArrayList;
import java.util.List;

/**
 * WRONG — the CALLER manually copies an OrderEditor's fields into loose
 * local variables to "back them up," instead of the editor itself owning
 * how it's snapshotted.
 *
 * WHY THIS IS A PROBLEM:
 * 1. BREAKS ENCAPSULATION: this code needs to know EXACTLY which fields
 *    {@code OrderEditor} has ({@code lines}, {@code notes}) and how to copy
 *    each one correctly (note the deliberate bug below — see #2). If
 *    OrderEditor gains a new field (e.g. a "preferredDeliveryWindow"), this
 *    backup code silently keeps compiling while silently failing to back up
 *    the new field — the exact same "forgotten field" bug shown in the
 *    Prototype pattern's {@code NaiveOrderCopy}.
 * 2. THE BUG IN THIS FILE: {@code backupLines} below assigns the LIVE
 *    reference to the editor's internal list state instead of copying it
 *    (it calls {@code editor.lines()}, which DOES defensively copy in this
 *    specific case — but notice how easy it would be to instead reach for
 *    a hypothetical raw getter and get this wrong, exactly as the
 *    Prototype module's shallow-copy example demonstrates). Manual,
 *    ad hoc backup code invites exactly this class of mistake because
 *    there's no single, reviewed, tested place backup logic lives.
 * 3. NO STRUCTURED HISTORY: restoring "three edits ago" means the caller
 *    manually managing a stack of these loose variable bundles itself —
 *    reinventing (poorly) what {@link OrderHistoryCaretaker} already does
 *    cleanly with a {@code Deque<OrderMemento>}.
 *
 * See {@link OrderEditor#saveSnapshot()} / {@link OrderEditor#restore}: the
 * ONE class that owns the state is also the ONE class responsible for
 * snapshotting it, so adding a field only requires updating ONE class, and
 * {@link OrderHistoryCaretaker} provides a real, reusable undo history on
 * top for free.
 */
public class NaiveManualBackup {

    public void demonstrateManualBackupAndRestore(OrderEditor editor) {
        // Ad hoc "backup" — loose variables the caller must remember to
        // maintain in sync with OrderEditor's actual fields.
        List<OrderLine> backupLines = new ArrayList<>(editor.lines());
        String backupNotes = editor.notes();

        editor.setNotes("changed my mind"); // simulate an edit the user might want to undo

        // Manual "restore" — re-implemented at every call site that needs
        // undo, instead of living once inside OrderEditor.
        // (Not actually invoked here since OrderEditor has no bulk setter —
        // which is exactly the point: undo requires OrderEditor to expose
        // enough raw mutation surface for outside code to rebuild its state,
        // undermining the encapsulation OrderEditor.restore() preserves.)
        System.out.println("Would restore to: " + backupLines.size() + " lines, notes=" + backupNotes);
    }
}
