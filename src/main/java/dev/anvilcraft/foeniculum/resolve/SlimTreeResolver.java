package dev.anvilcraft.foeniculum.resolve;

import dev.anvilcraft.foeniculum.model.SlimDeclaration;
import dev.anvilcraft.foeniculum.model.SlimDependency;
import dev.anvilcraft.foeniculum.model.SlimMetadata;
import dev.anvilcraft.foeniculum.model.SlimModule;
import dev.anvilcraft.foeniculum.model.SlimRepository;
import dev.anvilcraft.foeniculum.model.SlimTree;
import dev.anvilcraft.foeniculum.version.VersionRanges;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@code slimJarJar} 声明的依赖树压平成一层。
 *
 * <p>每个中间库发布时只记录自己直接声明的瘦身依赖，下游要知道完整清单必须逐个读取描述文件并递归。
 * 递归得到的是树，但最终内嵌进 jar 的必须是一层：同一模块在不同分支里可能以不同版本区间出现，
 * 必须合并区间并裁决出唯一版本，否则同一个库会被重复打包，而这正是本插件要解决的问题。
 *
 * <p>裁决与展开必须交替进行，但交替方式对正确性很关键。一个模块的版本可能在后续轮次被别的约束改掉，
 * 若把旧版本描述文件里的依赖一直留在约束集合里，那些「已经不再被选中」的版本就会继续收窄下游区间，
 * 甚至凭空造出一个区间为空的冲突。因此每一轮都从当前决策重新推导完整约束集合：
 * 只把「当前选中的那些版本」所声明的依赖算进来。这样约束集合与版本选择始终互相自洽。
 */
public final class SlimTreeResolver {
    /** 迭代上限。正常依赖树的深度远小于此值，超出说明版本选择在若干取值之间来回摆动。 */
    private static final int MAX_ROUNDS = 64;

    /** 描述文件引用了未声明的仓库时最多提示这么多条，避免一次构建刷出上百行警告。 */
    private static final int MAX_REPOSITORY_WARNINGS = 10;

    private final SlimArtifactSource source;
    private final Set<String> declaredRepositories;
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> warnedRepositories = new LinkedHashSet<>();
    private final Map<String, List<String>> availableVersions = new LinkedHashMap<>();
    /** 已读取过的描述文件，键为 {@code group:artifact:version}；值为 {@code null} 表示该版本没有描述文件。 */
    private final Map<String, SlimMetadata> metadataCache = new LinkedHashMap<>();
    private final Set<String> inspected = new LinkedHashSet<>();

    /**
     * @param source 描述文件与构件的来源
     * @param declaredRepositories 当前项目已声明仓库的规范化地址，用于校验下游描述文件
     */
    public SlimTreeResolver(SlimArtifactSource source, Set<String> declaredRepositories) {
        this.source = source;
        this.declaredRepositories = Set.copyOf(declaredRepositories);
    }

    /**
     * 解析整棵瘦身依赖树。
     *
     * @param declarations 当前项目直接声明的依赖
     * @param downloadDirectory 构件下载目录
     */
    public SlimResolution resolve(List<SlimDeclaration> declarations, Path downloadDirectory) {
        if (declarations.isEmpty()) {
            return new SlimResolution(SlimTree.empty(), List.of());
        }

        Map<String, String> decisions = Map.of();
        Map<String, List<Constraint>> constraints = Map.of();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            constraints = constraintsOf(declarations, decisions);
            Map<String, String> next = decideAll(constraints);
            inspect(next);

            if (next.equals(decisions)) {
                return new SlimResolution(collect(next, constraints, downloadDirectory), List.copyOf(warnings));
            }
            decisions = next;
        }

        throw new IllegalStateException("瘦身依赖的版本选择在 " + MAX_ROUNDS
                + " 轮内没有收敛，说明各库的版本约束互相牵制；请检查是否存在互相要求对方升级的环形依赖");
    }

    /** 一条版本约束及其来源；来源用于在冲突时给出可定位的错误信息。 */
    private record Constraint(String versionRange, String preferredVersion, String origin) {}

    /**
     * 从直接声明与当前选中的版本出发，重新推导完整的约束集合。
     *
     * <p>刻意每轮重建而不是累加：累加会把已不再被选中的版本所声明的依赖留在集合里，
     * 让下游区间被过期约束收窄。
     */
    private Map<String, List<Constraint>> constraintsOf(
            List<SlimDeclaration> declarations, Map<String, String> decisions) {
        Map<String, List<Constraint>> constraints = new LinkedHashMap<>();
        for (SlimDeclaration declaration : declarations) {
            addConstraint(
                    constraints,
                    declaration.group(),
                    declaration.artifact(),
                    declaration.versionRange(),
                    declaration.resolvedVersion(),
                    "本项目直接声明");
        }

        for (Map.Entry<String, String> decision : decisions.entrySet()) {
            SlimMetadata metadata = metadataCache.get(decision.getKey() + ":" + decision.getValue());
            if (metadata == null) {
                continue;
            }
            for (SlimDependency dependency : metadata.dependencies()) {
                addConstraint(
                        constraints,
                        dependency.group(),
                        dependency.artifact(),
                        dependency.versionRange(),
                        dependency.preferredVersion(),
                        "经由 " + decision.getKey() + " " + decision.getValue());
            }
        }
        return constraints;
    }

    private void addConstraint(
            Map<String, List<Constraint>> constraints,
            String group,
            String artifact,
            String versionRange,
            String preferredVersion,
            String origin) {
        String moduleId = group + ":" + artifact;
        // 解析一次做早期校验：非法写法在这里就暴露，而不是等到裁决阶段。
        VersionRanges.parse(versionRange, "模块 " + moduleId + " 的版本约束（来源：" + origin + "）");
        constraints
                .computeIfAbsent(moduleId, key -> new ArrayList<>())
                .add(new Constraint(versionRange, preferredVersion, origin));
    }

    /** 读取当前决策中尚未读取过的描述文件，并校验其声明的仓库。 */
    private void inspect(Map<String, String> decisions) {
        for (Map.Entry<String, String> decision : decisions.entrySet()) {
            String key = decision.getKey() + ":" + decision.getValue();
            if (!inspected.add(key)) {
                continue;
            }
            String[] parts = decision.getKey().split(":", 2);
            SlimMetadata metadata = source.findMetadata(parts[0], parts[1], decision.getValue());
            metadataCache.put(key, metadata);
            if (metadata != null) {
                checkRepositories(decision.getKey(), metadata);
            }
        }
    }

    private Map<String, String> decideAll(Map<String, List<Constraint>> constraints) {
        Map<String, String> decided = new LinkedHashMap<>();
        for (Map.Entry<String, List<Constraint>> entry : constraints.entrySet()) {
            decided.put(entry.getKey(), decide(entry.getKey(), entry.getValue()));
        }
        return decided;
    }

    private String decide(String moduleId, List<Constraint> moduleConstraints) {
        VersionRanges.Range intersection = intersect(moduleId, moduleConstraints);

        List<String> candidates = new ArrayList<>();
        for (Constraint constraint : moduleConstraints) {
            if (constraint.preferredVersion() != null) {
                candidates.add(constraint.preferredVersion());
            }
        }
        String best = highest(candidates, intersection);
        if (best != null) {
            return best;
        }

        // 各分支推荐版本互不兼容，但仓库里可能仍有同时满足两端约束的版本。
        List<String> available = availableVersions.computeIfAbsent(moduleId, key -> {
            String[] parts = moduleId.split(":", 2);
            return source.findVersions(parts[0], parts[1]);
        });
        best = highest(available, intersection);
        if (best == null) {
            throw conflict(moduleId, moduleConstraints);
        }

        warnings.add("模块 " + moduleId + " 在各库中声明的版本都不满足合并后的区间 " + intersection.format()
                + "，已改用仓库中的 " + best + "；建议统一各库的版本声明");
        return best;
    }

    private VersionRanges.Range intersect(String moduleId, List<Constraint> moduleConstraints) {
        List<VersionRanges.Range> ranges = new ArrayList<>();
        for (Constraint constraint : moduleConstraints) {
            ranges.add(VersionRanges.parse(constraint.versionRange(), "模块 " + moduleId + " 的版本约束"));
        }
        try {
            return VersionRanges.intersect(ranges);
        } catch (IllegalArgumentException e) {
            throw conflict(moduleId, moduleConstraints);
        }
    }

    private String highest(List<String> candidates, VersionRanges.Range range) {
        String best = null;
        for (String candidate : candidates) {
            if (candidate == null || !VersionRanges.contains(range, candidate)) {
                continue;
            }
            if (best == null || VersionRanges.compare(candidate, best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private ModuleConflictException conflict(String moduleId, List<Constraint> moduleConstraints) {
        List<ModuleConflictException.Constraint> details = new ArrayList<>();
        for (Constraint constraint : moduleConstraints) {
            details.add(new ModuleConflictException.Constraint(constraint.versionRange(), constraint.origin()));
        }
        return new ModuleConflictException(moduleId, details);
    }

    private void checkRepositories(String moduleId, SlimMetadata metadata) {
        for (String repository : metadata.repositories()) {
            String normalized = SlimRepository.normalize(repository);
            if (declaredRepositories.contains(normalized)) {
                continue;
            }
            if (warnedRepositories.size() >= MAX_REPOSITORY_WARNINGS) {
                continue;
            }
            if (warnedRepositories.add(normalized)) {
                warnings.add("模块 " + moduleId + " 的瘦身描述文件引用了当前项目未声明的仓库 " + normalized
                        + "；茴香不会自动添加该仓库，若构件解析失败请手动在 repositories 中声明");
            }
        }
    }

    /** 下载每个模块最终选定的构件，整理成压平后的依赖树。 */
    private SlimTree collect(
            Map<String, String> decided, Map<String, List<Constraint>> constraints, Path downloadDirectory) {
        List<SlimModule> modules = new ArrayList<>();
        Set<String> usedFileNames = new LinkedHashSet<>();

        List<Map.Entry<String, String>> ordered = new ArrayList<>(decided.entrySet());
        ordered.sort(Comparator.comparing(Map.Entry::getKey));

        for (Map.Entry<String, String> entry : ordered) {
            String moduleId = entry.getKey();
            String version = entry.getValue();
            String[] parts = moduleId.split(":", 2);
            String group = parts[0];
            String artifact = parts[1];

            Path directory = downloadDirectory.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
            Path slimJar = directory.resolve(artifact + "-" + version + "-slim.jar");
            Path standardJar = directory.resolve(artifact + "-" + version + ".jar");

            boolean isSlim = source.download(group, artifact, version, "slim", slimJar);
            Path artifactFile = slimJar;
            if (!isSlim) {
                if (!source.download(group, artifact, version, null, standardJar)) {
                    throw new ModuleConflictException(
                            moduleId + " " + version,
                            List.of(new ModuleConflictException.Constraint(
                                    "[" + version + "]", "构件下载失败，仓库中既没有瘦身包也没有普通 jar")));
                }
                artifactFile = standardJar;
                warnings.add("模块 " + moduleId + " " + version
                        + " 没有发布瘦身包，已改用普通 jar 内嵌；建议该库使用茴香插件发布瘦身包以减小产物体积");
            }

            String fileName = pickFileName(artifact, version, usedFileNames);
            modules.add(new SlimModule(
                    group,
                    artifact,
                    version,
                    intersect(moduleId, constraints.get(moduleId)).format(),
                    isSlim,
                    JarContents.isObfuscated(artifactFile),
                    relativePath(downloadDirectory, artifactFile),
                    fileName));
        }
        return new SlimTree(modules);
    }

    /** 内嵌文件名优先使用 {@code artifact-version.jar}，与 ModDevGradle 原生命名习惯保持一致。 */
    private String pickFileName(String artifact, String version, Set<String> used) {
        String candidate = artifact + "-" + version + ".jar";
        if (used.add(candidate)) {
            return candidate;
        }
        throw new ModuleConflictException(
                artifact + ":" + version,
                List.of(new ModuleConflictException.Constraint("[" + version + "]", "内嵌文件名与其他模块重复")));
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
