package com.nico.client.memleak;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModClassIndex {
    private static final ModIdentity AMBIGUOUS = new ModIdentity("<ambiguous>", "Ambiguous", "");

    private final Map<String, ModIdentity> classOwners;
    private final Map<String, ModIdentity> modsById;
    private final int ambiguousClassCount;

    private ModClassIndex(Map<String, ModIdentity> classOwners, Map<String, ModIdentity> modsById, int ambiguousClassCount) {
        this.classOwners = Map.copyOf(classOwners);
        this.modsById = Map.copyOf(modsById);
        this.ambiguousClassCount = ambiguousClassCount;
    }

    public static ModClassIndex empty() {
        return new ModClassIndex(Map.of(), Map.of(), 0);
    }

    public static ModClassIndex build() {
        Map<String, ModIdentity> owners = new HashMap<>();
        Map<String, ModIdentity> mods = new HashMap<>();

        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            ModContainer ownerContainer = topLevelContainer(container);
            var metadata = ownerContainer.getMetadata();

            ModIdentity identity = new ModIdentity(
                    metadata.getId(),
                    metadata.getName(),
                    metadata.getVersion().getFriendlyString()
            );
            mods.put(identity.id(), identity);

            Collection<Path> rootPaths;
            try {
                rootPaths = container.getRootPaths();
            } catch (UnsupportedOperationException ignored) {
                continue;
            }

            for (Path path : rootPaths) {
                try {
                    if (Files.isDirectory(path)) {
                        indexDirectory(path, className -> mergeOwner(owners, className, identity));
                    }
                 } catch (IOException ignored) {
                    // Go on
                }
            }
        }

        int ambiguous = 0;
        for (ModIdentity owner : owners.values()) {
            if (owner == AMBIGUOUS) {
                ambiguous++;
            }
        }
        return new ModClassIndex(owners, mods, ambiguous);
    }

    private static ModContainer topLevelContainer(ModContainer container) {
        ModContainer current = container;
        Set<String> visited = new HashSet<>();

        while (visited.add(current.getMetadata().getId())) {
            Optional<ModContainer> containing = current.getContainingMod();
            if (containing.isEmpty()) {
                break;
            }
            current = containing.get();
        }

        return current;
    }

    private static void indexDirectory(Path root, Consumer<String> classConsumer) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace(path.getFileSystem().getSeparator(), "/"))
                    .filter(ModClassIndex::isIndexableClass)
                    .map(ModClassIndex::toClassName)
                    .forEach(classConsumer);
        }
    }

    private static boolean isIndexableClass(String path) {
        return path.endsWith(".class")
                && !path.startsWith("META-INF/")
                && !path.equals("module-info.class")
                && !path.endsWith("/module-info.class");
    }

    private static String toClassName(String path) {
        return path.substring(0, path.length() - ".class".length()).replace('/', '.');
    }

    private static void mergeOwner(Map<String, ModIdentity> owners, String className, ModIdentity identity) {
        owners.merge(className, identity, (existing, replacement) ->
                existing.id().equals(replacement.id()) ? existing : AMBIGUOUS);
    }

    public Optional<ModIdentity> ownerOf(String className) {
        ModIdentity owner = classOwners.get(className);
        return owner == null || owner == AMBIGUOUS ? Optional.empty() : Optional.of(owner);
    }

    public Optional<ModIdentity> mod(String id) {
        return Optional.ofNullable(modsById.get(id));
    }

    public Collection<ModIdentity> mods() {
        List<ModIdentity> sorted = new ArrayList<>(modsById.values());
        sorted.sort(Comparator.comparing(ModIdentity::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    public int indexedClassCount() {
        return classOwners.size();
    }

    public int ambiguousClassCount() {
        return ambiguousClassCount;
    }
}
