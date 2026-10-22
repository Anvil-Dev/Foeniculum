package dev.anvilcraft.foeniculum.model;

import java.io.Serializable;

/**
 * 扁平化之后确定下来的一个内嵌模块。
 *
 * <p>瘦身依赖树里同一个模块可能出现多次，扁平化后只保留一个版本，因此每个实例都对应最终会被写进主 jar 的一个内嵌文件。
 *
 * @param group 模块的 group
 * @param artifact 模块的 artifact
 * @param version 裁决出的唯一版本
 * @param versionRange 该模块全部约束合并后的版本区间，写入 FML 元数据的 {@code version.range}
 * @param isSlim 构件是否来自瘦身包 {@code -slim.jar}
 * @param isObfuscated 构件是否被混淆过，由 jar 清单中的 {@code Obfuscated-By} 判定
 * @param sourcePath 构件相对解析任务输出目录的路径，使用正斜杠分隔
 * @param fileName 内嵌到 {@code META-INF/jarjar/} 下的文件名
 */
public record SlimModule(
        String group,
        String artifact,
        String version,
        String versionRange,
        boolean isSlim,
        boolean isObfuscated,
        String sourcePath,
        String fileName) implements Serializable {
    public SlimModule {
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("模块 group 不能为空");
        }
        if (artifact == null || artifact.isBlank()) {
            throw new IllegalArgumentException("模块 artifact 不能为空");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("模块版本不能为空：" + group + ":" + artifact);
        }
        if (versionRange == null || versionRange.isBlank()) {
            throw new IllegalArgumentException("模块版本区间不能为空：" + group + ":" + artifact);
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("模块构件路径不能为空：" + group + ":" + artifact);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("模块内嵌文件名不能为空：" + group + ":" + artifact);
        }
    }

    public String moduleId() {
        return group + ":" + artifact;
    }

    /** FML 元数据中记录的 {@code path} 字段取值，即模块在 jar 内部的实际位置。 */
    public String embeddedPath() {
        return "META-INF/jarjar/" + fileName;
    }

    /** 换一个内嵌文件名，用于避开与原生命中或其他模块的命名冲突。 */
    public SlimModule withFileName(String newFileName) {
        return new SlimModule(
                group, artifact, version, versionRange, isSlim, isObfuscated, sourcePath, newFileName);
    }
}
