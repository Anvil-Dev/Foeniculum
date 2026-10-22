package dev.anvilcraft.foeniculum;

import org.gradle.api.provider.Property;

/**
 * 茴香插件的配置项，通过 {@code foeniculum { }} 扩展块设置。
 */
public abstract class FoeniculumExtension {
    /**
     * 是否自动把瘦身包与描述文件挂到 {@code maven-publish} 的发布上。
     *
     * <p>默认开启，因为发布这两个构件正是本插件让下游项目能够递归解析瘦身依赖的前提；
     * 关闭后可以自行用 {@code artifact(...)} 手工挂载。
     */
    public abstract Property<Boolean> getAutoAttachPublishing();
}
