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
import org.gradle.api.GradleException;

/**
 * 把 {@code slimJarJar} 声明的依赖树压平成一层。
 *
 * <p>每个中间库发布时只记录自己直接声明的瘦身依赖，下游要知道完整清单必须逐个读取描述文件并递归。
 * 递归得到的是树，但最终内嵌进 jar 的必须是一层：同一模块在不同分支里可能以不同版本区间出现，
 * 必须合并区间并裁决出唯一版本，否则同一个库会被重复打包，而这正是本插件要解决的问题。
 *
 * <p>裁决与展开必须交替进行，而且「交集为空」不能立刻判定为冲突。一个模块的版本被抬高后，
 * 旧版本声称的依赖就不再成立；但抬高之前，旧版本声明的约束已经把下游模块的区间压成空集。
 * 这种空集只是中间状态，强行报错会让一个本来有解的依赖图失败。因此迭代期间遇到空交集时先沿用上一轮的决策，
 * 让其他模块继续演进；只有在决策真正稳定下来之后仍然存在空交集，才判定为不可调和的版本冲突。
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
    private final Set<String> warnedFallbacks = new LinkedHashSet<>();
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
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Map<String, List<Constraint>> constraints = constraintsOf(declarations, decisions);
            Map<String, List<Constraint>> conflicts = new LinkedHashMap<>();
            Map<String, String> next = new LinkedHashMap<>();

            for (Map.Entry<String, List<Constraint>> entry : constraints.entrySet()) {
                String decided = decide(entry.getKey(), entry.getValue(), decisions.get(entry.getKey()), conflicts);
                if (decided != null) {
                    next.put(entry.getKey(), decided);
                }
            }
            inspect(next);

            if (next.equals(decisions)) {
                if (!conflicts.isEmpty()) {
                    Map.Entry<String, List<Constraint>> first = conflicts.entrySet().iterator().next();
                    throw conflict(first.getKey(), first.getValue());
                }
                return new SlimResolution(collect(next, constraints, downloadDirectory), List.copyOf(warnings));
            }
            decisions = next;
        }

        throw new GradleException("瘦身依赖的版本选择在 " + MAX_ROUNDS
                + " 轮内没有收敛，说明各库的版本约束互相牵制；请检查是否存在互相要求对方升级的环形依赖");
    }

    /** 一条版本约束及其来源；来源用于在冲突时给出可定位的错误信息。 */
    private record Constraint(String versionRange, String preferredVersion, String origin) {}

    /**
     * 从直接声明与当前选中的版本出发，重新推导完整的约束集合。
     *
     * <p>刻意每轮重建而不是累加：累加会把已不再被选中的版本所声明的依赖留在集合里，
     * 让下游区间被过期约束收窄，甚至凭空造出一个空集。
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

        List<Map.Entry<String, String>> ordered = new ArrayList<>(decisions.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, String> decision : ordered) {
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

    /**
     * 裁决一个模块的版本。
     *
     * <p>交集为空时返回上一轮决策并把该模块登记为待定，而不是立刻抛错：空集很可能只是因为
     * 某个上游模块还没被抬高到不声明这条约束的版本。返回 {@code null} 表示该模块暂时无法裁决。
     */
    private String decide(
            String moduleId,
            List<Constraint> moduleConstraints,
            String previousDecision,
            Map<String, List<Constraint>> conflicts) {
        VersionRanges.Range intersection;
        try {
            intersection = intersect(moduleId, moduleConstraints);
        } catch (IllegalArgumentException e) {
            conflicts.put(moduleId, moduleConstraints);
            return previousDecision;
        }

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
            conflicts.put(moduleId, moduleConstraints);
            return previousDecision;
        }

        if (warnedFallbacks.add(moduleId + "@" + best)) {
            warnings.add("模块 " + moduleId + " 在各库中声明的版本都不满足合并后的区间 " + intersection.format()
                    + "，已改用仓库中的 " + best + "；建议统一各库的版本声明");
        }
        return best;
    }

    private VersionRanges.Range intersect(String moduleId, List<Constraint> moduleConstraints) {
        List<VersionRanges.Range> ranges = new ArrayList<>();
        for (Constraint constraint : moduleConstraints) {
            ranges.add(VersionRanges.parse(constraint.versionRange(), "模块 " + moduleId + " 的版本约束"));
        }
        return VersionRanges.intersect(ranges);
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
        Map<String, String> usedFileNames = new LinkedHashMap<>();

        List<Map.Entry<String, String>> ordered = new ArrayList<>(decided.entrySet());
        ordered.sort(Map.Entry.comparingByKey());

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
                    throw new GradleException("模块 " + moduleId + " " + version
                            + " 在任何已声明的仓库中都不存在，既没有瘦身包也没有普通 jar");
                }
                artifactFile = standardJar;
                warnings.add("模块 " + moduleId + " " + version
                        + " 没有发布瘦身包，已改用普通 jar 内嵌；建议该库使用茴香插件发布瘦身包以减小产物体积");
            }

            modules.add(new SlimModule(
                    group,
                    artifact,
                    version,
                    intersect(moduleId, constraints.get(moduleId)).format(),
                    isSlim,
                    JarContents.isObfuscated(artifactFile),
                    relativePath(downloadDirectory, artifactFile),
                    embeddedFileName(group, artifact, version, isSlim, moduleId, usedFileNames)));
        }
        return new SlimTree(modules);
    }

    /**
     * 决定模块在 {@code META-INF/jarjar/} 下的文件名。
     *
     * <p>带上 {@code -slim} 后缀以表明这是瘦身构件，让产物里能直接看出某个内嵌包是「分支」还是普通 jar；
     * 与 GroupId 冲突时再加上 group 前缀。两份都占用说明存在同名同版本的不同 group，只有再加限定才能区分。
     */
    private String embeddedFileName(
            String group,
            String artifact,
            String version,
            boolean isSlim,
            String moduleId,
            Map<String, String> used) {
        String suffix = isSlim ? "-slim.jar" : ".jar";
        String simple = artifact + "-" + version + suffix;
        if (!used.containsKey(simple)) {
            used.put(simple, moduleId);
            return simple;
        }
        String qualified = group + "-" + simple;
        if (!used.containsKey(qualified)) {
            used.put(qualified, moduleId);
            return qualified;
        }
        throw new GradleException("内嵌文件名 " + simple + " 与 " + qualified + " 都已被占用，"
                + "模块 " + moduleId + " " + version + " 无法内嵌；"
                + "已占用者：" + used.get(simple) + "、" + used.get(qualified));
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
