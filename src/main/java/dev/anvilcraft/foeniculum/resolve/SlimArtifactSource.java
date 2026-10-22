package dev.anvilcraft.foeniculum.resolve;

import dev.anvilcraft.foeniculum.model.SlimMetadata;
import java.nio.file.Path;
import java.util.List;

/**
 * 瘦身构件与描述文件的来源。
 *
 * <p>解析依赖树时需要按坐标反复读取描述文件、下载构件、查询可用版本，这些操作都经由本接口，
 * 从而让扁平化逻辑可以脱离网络单独验证。
 */
public interface SlimArtifactSource {
    /**
     * 读取指定版本模块的描述文件。
     *
     * @return 描述文件；仓库中不存在该文件时返回 {@code null}
     */
    SlimMetadata findMetadata(String group, String artifact, String version);

    /**
     * 列出仓库中该模块的全部可用版本，用于在已有候选都无法满足版本区间时回落选择。
     *
     * @return 版本号列表；无法获取时返回空列表
     */
    List<String> findVersions(String group, String artifact);

    /**
     * 下载指定模块的构件。
     *
     * @param classifier 需要瘦身包时传入 {@code slim}，需要普通 jar 时传入 {@code null}
     * @param target 下载目标文件
     * @return 构件存在且下载成功时返回 {@code true}
     */
    boolean download(String group, String artifact, String version, String classifier, Path target);
}
