package dev.anvilcraft.resource.foeniculum.model;

import java.io.Serializable;

/**
 * 一条瘦身依赖：既表示项目通过 {@code slimJarJar} 直接声明的依赖，也表示独立描述文件
 * {@code -slim-metadata.json} 中记录的条目。
 *
 * <p>两者刻意共用同一个类型，因为描述文件的内容正是“本项目直接声明了什么”，
 * 共用类型可以保证写入与读回走同一套字段与校验，不会出现两侧语义漂移。
 *
 * @param group 依赖的 group
 * @param artifact 依赖的 artifact
 * @param versionRange 声明的版本写法，例如 {@code [2.0.0,3.0.0)} 或 {@code 1.4.2}
 * @param preferredVersion 当前构建解析出的具体版本；读取第三方描述文件时可能为空
 * @param isSlim 该依赖是否以瘦身包 {@code -slim.jar} 形式内嵌
 */
public record SlimDependency(
        String group,
        String artifact,
        String versionRange,
        String preferredVersion,
        boolean isSlim) implements Serializable {
    public SlimDependency {
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("依赖 group 不能为空");
        }
        if (artifact == null || artifact.isBlank()) {
            throw new IllegalArgumentException("依赖 artifact 不能为空");
        }
        if (versionRange == null || versionRange.isBlank()) {
            throw new IllegalArgumentException("依赖版本写法不能为空：" + group + ":" + artifact);
        }
    }

    /** Maven 坐标中的 {@code group:artifact} 形式，用于在依赖树中定位同一个模块。 */
    public String moduleId() {
        return group + ":" + artifact;
    }

    /** 换取一份解析版本已知、且内嵌形式确定的副本。 */
    public SlimDependency resolved(String resolvedVersion, boolean slim) {
        return new SlimDependency(group, artifact, versionRange, resolvedVersion, slim);
    }
}