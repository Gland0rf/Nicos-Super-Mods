package com.nico.client.utils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small helpers for replacing persisted files without exposing partially-written contents. */
public final class AtomicFiles {
    private AtomicFiles() { }

    @FunctionalInterface
    public interface PathWriter {
        void write(Path path) throws IOException;
    }

    public static void writeAtomically(Path target, PathWriter writer) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            writer.write(temporary);
            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void writeStringAtomically(Path target, CharSequence contents, Charset charset) throws IOException {
        writeAtomically(target, temporary -> Files.writeString(temporary, contents, charset));
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
