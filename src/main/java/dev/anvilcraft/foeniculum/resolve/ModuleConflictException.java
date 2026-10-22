package dev.anvilcraft.foeniculum.resolve;

import java.util.List;
import org.gradle.api.GradleException;

/**
 * 瘦身依赖树无法压平成一个版本时抛出。
 *
 * <p>冲突必须让构建直接失败：任意挑一个版本继续打包，都会在运行时表现为某个库缺方法或行为不符，
 * 比构建期报错更难定位。异常信息因此要带上每个约束的来源链。
 */
public class ModuleConflictException extends GradleException {
    private final String moduleId;

    public ModuleConflictException(String moduleId, List<Constraint> constraints) {
        super(buildMessage(moduleId, constraints));
        this.moduleId = moduleId;
    }

    public String moduleId() {
        return moduleId;
    }

    private static String buildMessage(String moduleId, List<Constraint> constraints) {
        StringBuilder message = new StringBuilder()
                .append("无法为模块 ").append(moduleId).append(" 裁决出唯一版本：没有版本能同时满足所有约束。");
        if (constraints.isEmpty()) {
            message.append("\n该模块也不存在可用的构件。");
            return message.toString();
        }
        for (Constraint constraint : constraints) {
            message.append("\n  - 约束 ").append(constraint.versionRange())
                    .append("，来源：").append(constraint.origin());
        }
        message.append("\n请统一各库声明的版本区间，或直接在顶层项目中声明一个满足全部区间的版本。");
        return message.toString();
    }

    /**
     * 一条版本约束及其来源。
     *
     * @param versionRange 声明的版本区间
     * @param origin 该约束出现在依赖树的哪条路径上
     */
    public record Constraint(String versionRange, String origin) {}
}
