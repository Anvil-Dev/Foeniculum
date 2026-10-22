package dev.anvilcraft.foeniculum.model;

import java.io.Serializable;

/**
 * 项目通过 {@code slimJarJar} 直接声明的一条依赖，携带当前构建实际解析出的版本。
 *
 * <p>与描述文件中的 {@link SlimDependency} 分开建模，是因为两者职责不同：描述文件记录的是“某个库对外声明了什么”，
 * 允许没有解析版本；而本类型描述的是“当前项目实际解析到了哪个版本”，解析版本必须存在，否则无法定位构件。
 * 混用同一个类型会让缺失版本这种非法状态变得可以表示。
 *
 * @param group 依赖的 group
 * @param artifact 依赖的 artifact
 * @param versionRange 声明的原始版本写法，例如 {@code [2.0.0,3.0.0)} 或 {@code 1.4.2}
 * @param resolvedVersion 当前构建为该声明解析出的具体版本
 */
public record SlimDeclaration(String group, String artifact, String versionRange, String resolvedVersion)
        implements Serializable {
    public SlimDeclaration {
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("依赖 group 不能为空");
        }
        if (artifact == null || artifact.isBlank()) {
            throw new IllegalArgumentException("依赖 artifact 不能为空");
        }
        if (versionRange == null || versionRange.isBlank()) {
            throw new IllegalArgumentException("依赖版本写法不能为空：" + group + ":" + artifact);
        }
        if (resolvedVersion == null || resolvedVersion.isBlank()) {
            throw new IllegalArgumentException("依赖解析版本不能为空：" + group + ":" + artifact);
        }
    }

    public String moduleId() {
        return group + ":" + artifact;
    }
}