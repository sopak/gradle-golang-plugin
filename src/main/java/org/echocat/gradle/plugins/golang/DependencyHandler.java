package org.echocat.gradle.plugins.golang;

import org.apache.commons.lang3.StringUtils;
import org.echocat.gradle.plugins.golang.model.DependenciesSettings;
import org.echocat.gradle.plugins.golang.model.GolangDependency;
import org.echocat.gradle.plugins.golang.model.Settings;
import org.echocat.gradle.plugins.golang.utils.FileUtils;
import org.echocat.gradle.plugins.golang.vcs.*;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import static java.io.File.separatorChar;
import static java.lang.Boolean.TRUE;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.Files.*;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.echocat.gradle.plugins.golang.DependencyHandler.DependencyDirType.*;
import static org.echocat.gradle.plugins.golang.DependencyHandler.GetResult.alreadyExists;
import static org.echocat.gradle.plugins.golang.DependencyHandler.GetResult.downloaded;
import static org.echocat.gradle.plugins.golang.model.GolangDependency.Type.*;
import static org.echocat.gradle.plugins.golang.model.GolangDependency.newDependency;
import static org.echocat.gradle.plugins.golang.utils.Executor.executor;

public class DependencyHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyHandler.class);

    protected static final Pattern IS_EXTERNAL_DEPENDENCY_PATTERN = Pattern.compile("^([a-zA-Z0-9\\-]+\\.[a-zA-Z0-9\\-.]+/[a-zA-Z0-9\\-_.$]+[^ ]*)");
    protected static final Filter<Path> GO_FILENAME_FILTER = new Filter<Path>() {
        @Override
        public boolean accept(Path path) {
            return path.getFileName().toString().endsWith(".go");
        }
    };

    @Nonnull
    private final VcsRepositoryProvider _vcsRepositoryProvider = new CombinedVcsRepositoryProvider();

    @Nonnull
    private final Settings _settings;

    public DependencyHandler(@Nonnull Settings settings) {
        _settings = settings;
    }

    @Nonnull
    public Map<GolangDependency, GetResult> get(@Nullable String configuration, @Nullable GolangDependency... optionalRequiredPackages) throws Exception {
        return get(configuration, optionalRequiredPackages != null ? asList(optionalRequiredPackages) : null);
    }

    @Nonnull
    public Map<GolangDependency, GetResult> get(@Nullable String configuration, @Nullable Collection<GolangDependency> optionalRequiredPackages) throws Exception {
        final DependenciesSettings dependencies = _settings.getDependencies();
        final Map<GolangDependency, GetResult> result = new TreeMap<>();
        final Queue<GolangDependency> toHandle = new LinkedList<>();
        toHandle.addAll(dependencies(configuration));
        if (optionalRequiredPackages != null) {
            toHandle.addAll(optionalRequiredPackages);
        }

        GolangDependency dependency;
        while ((dependency = toHandle.poll()) != null) {
            if (!result.containsKey(dependency)) {
                if (dependency.getType() != source) {
                    final RawVcsReference reference = dependency.toRawVcsReference();
                    final VcsRepository repository = _vcsRepositoryProvider.tryProvideFor(reference);
                    if (repository == null) {
                        throw new RuntimeException("Could not download dependency: " + reference);
                    }
                    LOGGER.debug("Update dependency {} (if required)...", reference);
                    if (TRUE.equals(dependencies.getForceUpdate())) {
                        repository.forceUpdate(selectTargetDirectoryFor(configuration));
                        LOGGER.info("Dependency {} updated.", reference);
                    } else {
                        final VcsFullReference fullReference = repository.updateIfRequired(selectTargetDirectoryFor(configuration));
                        if (fullReference != null) {
                            LOGGER.info("Dependency {} updated.", reference);
                            result.put(dependency, downloaded);
                        } else {
                            LOGGER.debug("No update required for dependency {}.", reference);
                            result.put(dependency, alreadyExists);
                        }
                    }
                } else {
                    result.put(dependency, alreadyExists);
                }
                toHandle.addAll(resolveDependenciesOf(dependency));
            }
        }
        if (LOGGER.isInfoEnabled() && !result.isEmpty()) {
            final StringBuilder sb = new StringBuilder(capitalize(configuration) + " dependencies:");
            for (final GolangDependency handledDependecy : result.keySet()) {
                sb.append("\n\t* ").append(handledDependecy);
            }
            LOGGER.info(sb.toString());
        }
        return result;
    }

    protected boolean isPartOfProjectSources(@Nonnull String packageName) throws Exception {
        final String projectPackageName = _settings.getGolang().getPackageName();
        return packageName.equals(projectPackageName) || packageName.startsWith(projectPackageName + "/");
    }

    @Nonnull
    protected Path selectTargetDirectoryFor(@Nullable String configuration) throws Exception {
        if ("tool".equals(configuration)) {
            return _settings.getBuild().getGopathSourceRoot();
        }
        return _settings.getDependencies().getDependencyCache();
    }

    @Nonnull
    protected Set<GolangDependency> resolveDependenciesOf(@Nonnull GolangDependency dependency) throws Exception {
        final Set<GolangDependency> result = new TreeSet<>();
        for (final Path file : filesFor(dependency)) {
            final String plainPackages = executor(_settings.getToolchain().toolchainBinary("importsExtractor"))
                .arguments(file)
                .execute()
                .getStdoutAsString();
            for (final String plainPackage : StringUtils.split(plainPackages, '\n')) {
                final String trimmed = plainPackage.trim();
                if (!trimmed.isEmpty() && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    final String candidate = trimmed.substring(1, trimmed.length() - 1);
                    if (IS_EXTERNAL_DEPENDENCY_PATTERN.matcher(candidate).matches()) {
                        final GolangDependency childDependency = resolvePackage(dependency, candidate);
                        result.add(childDependency);
                    }
                }
            }
        }

        return result;
    }

    @Nonnull
    protected GolangDependency resolvePackage(@Nonnull GolangDependency demandedBy, @Nonnull String packageName) throws Exception {
        GolangDependency candidate = null;
        if (candidate == null) {
            candidate = resolveVendorPackage(demandedBy, packageName);
        }
        if (candidate == null) {
            candidate = resolveDependenciesPackage(packageName);
        }
        if (candidate == null) {
            candidate = resolveGopathPackage(packageName);
        }
        if (candidate == null) {
            candidate = resolveGorootPackage(packageName);
        }
        if (candidate == null) {
            candidate = newDependency(packageName)
                .setType(implicit);
        }
        return candidate;
    }

    @Nullable
    protected GolangDependency resolveVendorPackage(@Nonnull GolangDependency demandedBy, @Nonnull String packageName) throws Exception {
        final Path root = _settings.getBuild().getGopathSourceRoot().toAbsolutePath();
        final Path demandedByLocation = demandedBy.getLocation();
        if (demandedByLocation != null) {
            Path current = demandedByLocation.toAbsolutePath();
            while (current.startsWith(root)) {
                final Path vendorCandidate = current.resolve("vendor");
                if (isDirectory(vendorCandidate)) {
                    final Path packagePathCandidate = vendorCandidate.resolve(packageName);
                    if (containsGoSources(packagePathCandidate)) {
                        return newDependency(packageName)
                            .setType(implicit)
                            .setParent(demandedBy)
                            .setLocation(packagePathCandidate);
                    }
                }
                current = current.getParent();
            }
        }
        return null;
    }

    @Nullable
    protected GolangDependency resolveDependenciesPackage(@Nonnull String packageName) throws Exception {
        final Path location = _settings.getDependencies().getDependencyCache().resolve(packageName);
        if (!containsGoSources(location)) {
            return null;
        }
        return newDependency(packageName)
            .setType(implicit)
            .setLocation(location);
    }

    @Nullable
    protected GolangDependency resolveGopathPackage(@Nonnull String packageName) throws Exception {
        final Path location = _settings.getBuild().getGopathSourceRoot().resolve(packageName);
        if (!containsGoSources(location)) {
            return null;
        }
        return newDependency(packageName)
            .setType(isPartOfProjectSources(packageName) ? source : implicit)
            .setLocation(location);
    }

    @Nullable
    protected GolangDependency resolveGorootPackage(@Nonnull String packageName) throws Exception {
        final Path location = _settings.getToolchain().getGorootSourceRoot().resolve(packageName);
        if (!containsGoSources(location)) {
            return null;
        }
        return newDependency(packageName)
            .setType(system)
            .setLocation(location);
    }

    protected boolean containsGoSources(@Nonnull Path candidate) throws Exception {
        return isDirectory(candidate) && newDirectoryStream(candidate, GO_FILENAME_FILTER).iterator().hasNext();
    }

    @Nonnull
    protected Set<Path> filesFor(@Nonnull GolangDependency dependency) throws Exception {
        final Set<Path> result = new TreeSet<>();
        //final GolangDependency parent = dependency.getParent();

        appendFilesFor(_settings.getDependencies().getDependencyCache(), dependency, result);
        appendFilesFor(_settings.getBuild().getGopathSourceRoot(), dependency, result);
        return result;
    }

    protected void appendFilesFor(@Nonnull Path root, @Nonnull GolangDependency dependency, @Nonnull Set<Path> to) throws Exception {
        final Path directory = root.resolve(dependency.getGroup());
        if (isDirectory(directory)) {
            for (final Path path : newDirectoryStream(directory, GO_FILENAME_FILTER)) {
                to.add(path);
            }
        }
    }

    @Nonnull
    public Collection<Path> deleteUnknownDependenciesIfRequired() throws Exception {
        final DependenciesSettings dependencies = _settings.getDependencies();
        final Path dependencyCacheDirectory = dependencies.getDependencyCache();
        final Set<String> knownDependencyIds = new HashSet<>();
        for (final GolangDependency dependency : dependencies(null)) {
            knownDependencyIds.add(dependency.getGroup());
        }
        return doDeleteUnknownDependenciesIfRequired(dependencyCacheDirectory, knownDependencyIds);
    }

    @Nonnull
    public Collection<Path> deleteAllCachedDependenciesIfRequired() throws Exception {
        final DependenciesSettings dependencies = _settings.getDependencies();
        final Path dependencyCacheDirectory = dependencies.getDependencyCache();
        return doDeleteAllCachedDependenciesIfRequired(dependencyCacheDirectory);
    }

    @Nonnull
    protected Collection<GolangDependency> dependencies(@Nullable String configurationName) {
        final List<GolangDependency> result = new ArrayList<>();
        final Project project = _settings.getProject();
        final ConfigurationContainer configurations = project.getConfigurations();
        if (configurationName != null) {
            final Configuration configuration = configurations.getByName(configurationName);
            for (final Dependency dependency : configuration.getDependencies()) {
                if (dependency instanceof GolangDependency) {
                    result.add((GolangDependency) dependency);
                } else {
                    result.add(new GolangDependency(dependency));
                }
            }
        } else {
            for (final Configuration configuration : project.getConfigurations()) {
                for (final Dependency dependency : configuration.getDependencies()) {
                    if (dependency instanceof GolangDependency) {
                        result.add((GolangDependency) dependency);
                    } else {
                        result.add(new GolangDependency(dependency));
                    }
                }
            }
        }
        return result;
    }

    @Nonnull
    protected Collection<Path> doDeleteUnknownDependenciesIfRequired(@Nonnull Path root, @Nonnull Set<String> knownDependencyIds) throws IOException {
        if (!TRUE.equals(_settings.getDependencies().getDeleteUnknownDependencies())) {
            return emptyList();
        }
        return doDeleteUnknownDependencies(root, knownDependencyIds);
    }

    @Nonnull
    protected Collection<Path> doDeleteAllCachedDependenciesIfRequired(@Nonnull Path root) throws IOException {
        if (!TRUE.equals(_settings.getDependencies().getDeleteAllCachedDependenciesOnClean())) {
            return emptyList();
        }
        LOGGER.debug("Deleting cached dependency in {}...", root);
        final Set<Path> deleted = FileUtils.deleteWithLogging(root);
        LOGGER.info("Unknown cached in {} deleted.", root);
        return deleted;
    }

    @Nonnull
    protected Collection<Path> doDeleteUnknownDependencies(@Nonnull Path root, @Nonnull Set<String> knownDependencyIds) throws IOException {
        final Collection<Path> paths = collectUnknownDependencyDirectories(root, knownDependencyIds);
        for (final Path path : paths) {
            LOGGER.debug("Deleting unknown dependency in {}...", path);
            FileUtils.deleteWithLogging(path);
            LOGGER.info("Unknown dependency in {} deleted.", path);
        }
        return paths;
    }

    @Nonnull
    protected Collection<Path> collectUnknownDependencyDirectories(@Nonnull Path root, @Nonnull Set<String> knownDependencyIds) {
        final Set<Path> result = new TreeSet<>(Collections.<Path>reverseOrder());
        final Map<Path, DependencyDirType> candidates = collectDirectoriesToKnownDependencyOf(root, knownDependencyIds);
        for (final Entry<Path, DependencyDirType> candidate : candidates.entrySet()) {
            if (candidate.getValue() == unknown) {
                result.add(candidate.getKey());
            }
        }
        return result;
    }

    @Nonnull
    protected Map<Path, DependencyDirType> collectDirectoriesToKnownDependencyOf(@Nonnull final Path root, @Nonnull final Set<String> knownDependencyIds) {
        final Map<Path, DependencyDirType> directories = new TreeMap<>();
        if (isDirectory(root)) {
            try {
                walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (dir.equals(root)) {
                            return CONTINUE;
                        }

                        if (isKnownDependencyDirectory(dir, root, knownDependencyIds)) {
                            directories.put(dir, containsInfoFile);
                            Path parent = dir.getParent();
                            while (parent != null && !parent.equals(root)) {
                                final DependencyDirType type = directories.get(parent);
                                if (type == null || type == unknown) {
                                    directories.put(parent, parentOfContainsInfoFile);
                                }
                                parent = parent.getParent();
                            }
                            return CONTINUE;
                        }

                        if (isChildOfWithInfoFile(dir, root, directories)) {
                            directories.put(dir, hasContainsInfoFileParent);
                            return CONTINUE;
                        }

                        if (!directories.containsKey(dir)) {
                            directories.put(dir, unknown);
                        }
                        return CONTINUE;
                    }
                });
            } catch (final IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
        return directories;
    }

    protected boolean isChildOfWithInfoFile(@Nonnull Path directory, @Nonnull Path root, @Nonnull Map<Path, DependencyDirType> directories) {
        Path parent = directory.getParent();
        while (parent != null && !parent.equals(root)) {
            if (directories.get(parent) == containsInfoFile) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    protected boolean isKnownDependencyDirectory(@Nonnull Path directory, @Nonnull Path root, @Nonnull Set<String> knownDependencyIds) throws IOException {
        final int dirCount = directory.getNameCount();
        final int rootCount = root.getNameCount();
        final Path subPath = directory.subpath(rootCount, dirCount);
        final String idToTest = subPath.toString().replace(separatorChar, '/');
        return knownDependencyIds.contains(idToTest);
    }

    protected enum DependencyDirType {
        unknown,
        containsInfoFile,
        hasContainsInfoFileParent,
        parentOfContainsInfoFile
    }

    public enum GetResult {
        downloaded,
        alreadyExists
    }
}
