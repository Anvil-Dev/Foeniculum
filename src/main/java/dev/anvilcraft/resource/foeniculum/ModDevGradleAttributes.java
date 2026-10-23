package dev.anvilcraft.resource.foeniculum;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmVersion;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/**
 * 为 {@code slimJarJar} 配置设置变体选择属性。
 *
 * <p>这些属性必须与 ModDevGradle 为原生 {@code jarJar} 设置的一致，否则同一个库在两个配置里可能选中不同变体，
 * 出现“内嵌的是 A 变体、记录的却是 B 版本”的不一致。因此这里先给出与 ModDevGradle 等价的默认值，
 * 等 ModDevGradle 应用后再同步它实际写入的属性，覆盖同一变体的其他自定义设置。
 */
final class ModDevGradleAttributes {
    private ModDevGradleAttributes() {}

    static void configure(Project project, Configuration slimConfiguration) {
        applyDefaults(project, slimConfiguration);
        project.getPluginManager().withPlugin("net.neoforged.moddev", plugin -> {
            Configuration nativeConfiguration = project.getConfigurations().findByName(FoeniculumPlugin.NATIVE_NAME);
            if (nativeConfiguration != null) {
                copyAll(nativeConfiguration, slimConfiguration);
            }
        });
    }

    private static void applyDefaults(Project project, Configuration slimConfiguration) {
        JavaPluginExtension javaPlugin = project.getExtensions().getByType(JavaPluginExtension.class);
        slimConfiguration.attributes(attributes -> {
            attributes.attributeProvider(
                    TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
                    javaPlugin.getToolchain().getLanguageVersion()
                            .orElse(JavaLanguageVersion.current())
                            .map(JavaLanguageVersion::asInt));
            attributes.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
            attributes.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
            attributes.attribute(
                    Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.LIBRARY));
            attributes.attribute(
                    Bundling.BUNDLING_ATTRIBUTE, project.getObjects().named(Bundling.class, Bundling.EXTERNAL));
        });
    }

    private static void copyAll(Configuration source, Configuration target) {
        var sourceAttributes = source.getAttributes();
        target.attributes(attributes -> {
            for (Attribute<?> attribute : sourceAttributes.keySet()) {
                copyAttribute(sourceAttributes, attribute, attributes);
            }
        });
    }

    private static <T> void copyAttribute(
            org.gradle.api.attributes.AttributeContainer source,
            Attribute<T> attribute,
            org.gradle.api.attributes.AttributeContainer target) {
        T value = source.getAttribute(attribute);
        if (value != null) {
            target.attribute(attribute, value);
        }
    }
}
