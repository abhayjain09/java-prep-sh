package com.interviewprep.orders.io;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Bundles a batch of exported files (e.g. the CSV + JSON inventory snapshots
 * from this module) into a single ZIP archive using {@code java.util.zip}.
 *
 * WHY ZIP A BATCH OF EXPORTS: a common real pattern — a nightly job produces
 * several output files (a CSV for one downstream system, a JSON for
 * another, a log/manifest file) and ships them as one archive to an SFTP
 * drop, an S3 bucket (Module 6, AWS), or an email attachment, rather than
 * transferring files individually.
 */
public final class ExportZipper {

    private ExportZipper() {
        // no instances — pure static utility
    }

    /**
     * Writes {@code filesToZip} into a new ZIP archive at {@code zipFile},
     * one {@link ZipEntry} per input file (named by its filename only, no
     * directory structure preserved — a deliberate simplification for this
     * demo; a real archiver would let the caller choose the entry name to
     * control the folder layout inside the archive).
     *
     * Streams each file's bytes directly into the zip entry via
     * {@link InputStream#transferTo(OutputStream)} rather than reading the
     * whole file into a {@code byte[]} first — the same "don't materialize
     * more than you have to" principle as {@code CsvOrderImporter}'s
     * streaming import, applied here to potentially large export files.
     */
    public static void zip(List<Path> filesToZip, Path zipFile) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(zipFile);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            for (Path file : filesToZip) {
                zipOut.putNextEntry(new ZipEntry(file.getFileName().toString()));
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    in.transferTo(zipOut);
                }
                // closeEntry() finalizes THIS entry's data (writes its CRC/
                // size to the archive's central directory bookkeeping) — the
                // next putNextEntry() call implicitly does this too, but
                // being explicit makes the one-entry-at-a-time loop shape
                // clearer, and is required after the LAST entry since there
                // is no following putNextEntry() to do it implicitly.
                zipOut.closeEntry();
            }
        }
    }

    /**
     * Lists entry names inside an existing ZIP — used here just to prove
     * {@link #zip} worked, but this is also the shape you'd use to read an
     * archive someone else produced (e.g. validating an uploaded ZIP before
     * extracting it — see the README's note on "zip slip" path-traversal
     * attacks when extracting archives from an untrusted source).
     */
    public static List<String> listEntries(Path zipFile) throws IOException {
        try (ZipFile zf = new ZipFile(zipFile.toFile())) {
            return zf.stream().map(ZipEntry::getName).toList();
        }
    }
}
