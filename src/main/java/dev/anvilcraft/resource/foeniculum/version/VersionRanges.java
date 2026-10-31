package dev.anvilcraft.resource.foeniculum.version;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.Restriction;
import org.apache.maven.artifact.versioning.VersionRange;

/**
 * Maven 版本区间的解析、求交与版本选择。
 *
 * <p>瘦身依赖树在扁平化时要把同一模块的所有版本约束合并成一个区间，再从该区间里选出唯一的兼容版本。
 * 区间语义必须与 NeoForge FML 的 Jar-in-Jar 元数据一致，因此这里直接复用 Maven 的版本比较与区间解析实现，
 * 而不是自行实现排序规则。
 *
 * <p>固定版本（如 {@code 1.2.3}）按 Jar-in-Jar 的既有约定解析为左闭右开的开放区间 {@code [1.2.3,)}，
 * 表示“至少该版本”，与 ModDevGradle 原生 {@code jarJar} 生成的元数据保持一致，避免同一库的不同版本在运行时被判为冲突。
 */
public final class VersionRanges {
    private VersionRanges() {}

    /** 版本区间；上下界为 {@code null} 表示该侧不设限。 */
    public record Range(ArtifactVersion lower, boolean lowerInclusive, ArtifactVersion upper, boolean upperInclusive) {
        public Range {
            if (lower == null && upper == null) {
                throw new IllegalArgumentException("版本区间不能两侧都不设限");
            }
        }

        public boolean contains(ArtifactVersion version) {
            if (lower != null) {
                int compare = version.compareTo(lower);
                if (compare < 0 || (compare == 0 && !lowerInclusive)) {
                    return false;
                }
            }
            if (upper != null) {
                int compare = version.compareTo(upper);
                if (compare > 0 || (compare == 0 && !upperInclusive)) {
                    return false;
                }
            }
            return true;
        }

        /** 上下界相同且均为闭区间时表示一个确定版本，例如 {@code [1.2.3]}。 */
        public boolean isExact() {
            return lower != null && upper != null && lowerInclusive && upperInclusive && lower.compareTo(upper) == 0;
        }

        public boolean isEmpty() {
            if (lower == null || upper == null) {
                return false;
            }
            int compare = lower.compareTo(upper);
            if (compare > 0) {
                return true;
            }
            return compare == 0 && !(lowerInclusive && upperInclusive);
        }

        /** 规范化为 Maven 区间写法，例如 {@code [2.0.0,3.0.0)}、{@code [1.0.0,)}、{@code [1.2.3]}。 */
        public String format() {
            if (isExact()) {
                return "[" + lower + "]";
            }
            if (upper == null) {
                return (lowerInclusive ? "[" : "(") + lower + ",)";
            }
            if (lower == null) {
                return "(," + upper + (upperInclusive ? "]" : ")");
            }
            return (lowerInclusive ? "[" : "(") + lower + "," + upper + (upperInclusive ? "]" : ")");
        }

        @Override
        public String toString() {
            return format();
        }
    }

    /**
     * 解析依赖声明或描述文件里的版本写法。
     *
     * @param spec 版本写法
     * @param context 出错时用于定位问题的上下文
     */
    public static Range parse(String spec, String context) {
        Objects.requireNonNull(spec, "spec");
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(context + "：版本写法为空");
        }
        if (isDynamic(trimmed)) {
            throw new IllegalArgumentException(context + "：不支持动态版本 '" + trimmed + "'，请改用固定版本或版本区间");
        }

        VersionRange versionRange;
        try {
            versionRange = VersionRange.createFromVersionSpec(trimmed);
        } catch (InvalidVersionSpecificationException e) {
            throw new IllegalArgumentException(context + "：无法解析版本 '" + trimmed + "'", e);
        }

        if (versionRange.getRecommendedVersion() != null) {
            return new Range(versionRange.getRecommendedVersion(), true, null, false);
        }

        var restrictions = versionRange.getRestrictions();
        if (restrictions.isEmpty()) {
            throw new IllegalArgumentException(context + "：版本 '" + trimmed + "' 没有可用的区间约束");
        }
        if (restrictions.size() > 1) {
            throw new IllegalArgumentException(context + "：暂不支持由多个子区间组成的版本写法 '" + trimmed + "'");
        }

        Restriction restriction = restrictions.get(0);
        Range range = new Range(
                restriction.getLowerBound(),
                restriction.isLowerBoundInclusive(),
                restriction.getUpperBound(),
                restriction.isUpperBoundInclusive());
        if (range.isEmpty()) {
            throw new IllegalArgumentException(context + "：版本区间 '" + trimmed + "' 为空");
        }
        return range;
    }

    /**
     * 解析当前构建的直接声明；动态选择器由 Gradle 先解析，再按固定版本的规则转为具体下界。
     *
     * <p>动态选择器只用于构建时选版本，不能原样写进 FML 或瘦身描述文件。
     * 显式区间仍保留原约束，也不允许在区间边界中混入动态选择器。
     */
    public static Range parseDeclaration(String spec, String resolvedVersion, String context) {
        Objects.requireNonNull(spec, "spec");
        String trimmed = spec.trim();
        if (!trimmed.startsWith("[") && !trimmed.startsWith("(") && isDynamic(trimmed)) {
            return parse(resolvedVersion, context + " 的解析版本");
        }
        return parse(spec, context);
    }

    /** 多个区间的交集。交集为空时抛出异常，来源链由调用方补充。 */
    public static Range intersect(Collection<Range> ranges) {
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个版本区间");
        }

        ArtifactVersion lower = null;
        ArtifactVersion upper = null;
        boolean lowerInclusive = true;
        boolean upperInclusive = true;

        for (Range range : ranges) {
            if (range.lower() != null) {
                if (lower == null) {
                    lower = range.lower();
                    lowerInclusive = range.lowerInclusive();
                } else {
                    int compare = range.lower().compareTo(lower);
                    if (compare > 0) {
                        lower = range.lower();
                        lowerInclusive = range.lowerInclusive();
                    } else if (compare == 0) {
                        lowerInclusive = lowerInclusive && range.lowerInclusive();
                    }
                }
            }
            if (range.upper() != null) {
                if (upper == null) {
                    upper = range.upper();
                    upperInclusive = range.upperInclusive();
                } else {
                    int compare = range.upper().compareTo(upper);
                    if (compare < 0) {
                        upper = range.upper();
                        upperInclusive = range.upperInclusive();
                    } else if (compare == 0) {
                        upperInclusive = upperInclusive && range.upperInclusive();
                    }
                }
            }
        }

        Range intersection = new Range(lower, lowerInclusive, upper, upperInclusive);
        if (intersection.isEmpty()) {
            throw new IllegalArgumentException("版本区间交集为空：" + ranges);
        }
        return intersection;
    }

    /** 判断给定版本是否落在区间内。 */
    public static boolean contains(Range range, String version) {
        return range.contains(new DefaultArtifactVersion(version));
    }

    /** 按 Maven 规则比较两个版本号。 */
    public static int compare(String left, String right) {
        return new DefaultArtifactVersion(left).compareTo(new DefaultArtifactVersion(right));
    }

    private static boolean isDynamic(String spec) {
        // 这里只识别动态写法；直接声明会先用 Gradle 的解析结果替换，持久化的区间则必须已有具体边界。
        String upper = spec.toUpperCase(Locale.ROOT);
        if (upper.equals("RELEASE") || upper.equals("LATEST") || spec.startsWith("latest.")) {
            return true;
        }
        for (String part : spec.split(",")) {
            String trimmed = part.replace("[", "").replace("]", "").replace("(", "").replace(")", "").trim();
            if (trimmed.endsWith("+") || trimmed.toUpperCase(Locale.ROOT).equals("RELEASE")
                    || trimmed.toUpperCase(Locale.ROOT).equals("LATEST")
                    || trimmed.startsWith("latest.")) {
                return true;
            }
        }
        return false;
    }
}
