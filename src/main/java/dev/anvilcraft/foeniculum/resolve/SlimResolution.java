package dev.anvilcraft.foeniculum.resolve;

import dev.anvilcraft.foeniculum.model.SlimTree;
import java.util.List;

/**
 * 一次瘦身依赖解析的完整结果。
 *
 * <p>警告与结果一起返回而不是直接打印，是为了让解析逻辑不依赖日志设施：任务负责把警告输出到 Gradle 控制台，
 * 测试则可以直接断言警告内容。
 *
 * @param tree 压平后的依赖树
 * @param warnings 需要提示给构建者的警告，每条对应一个具体问题
 */
public record SlimResolution(SlimTree tree, List<String> warnings) {
    public SlimResolution {
        warnings = List.copyOf(warnings);
    }
}
