package dev.anvilcraft.foeniculum.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionRangesTest {
    @Test
    void 固定版本被规范化为左闭右开的区间() {
        VersionRanges.Range range = VersionRanges.parse("1.2.3", "测试");

        assertEquals("[1.2.3,)", range.format());
        assertTrue(range.contains(new org.apache.maven.artifact.versioning.DefaultArtifactVersion("1.2.3")));
        assertTrue(range.contains(new org.apache.maven.artifact.versioning.DefaultArtifactVersion("1.3.0")));
        assertFalse(range.contains(new org.apache.maven.artifact.versioning.DefaultArtifactVersion("1.2.2")));
    }

    @Test
    void 区间写法被原样规范化() {
        assertEquals("[2.0.0,3.0.0)", VersionRanges.parse("[2.0.0,3.0.0)", "测试").format());
        assertEquals("[2.0.0,3.0.0]", VersionRanges.parse("[2.0.0,3.0.0]", "测试").format());
        assertEquals("(2.0.0,3.0.0)", VersionRanges.parse("(2.0.0,3.0.0)", "测试").format());
    }

    @Test
    void 不合法的版本写法被拒绝并带上上下文() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> VersionRanges.parse("1.+", "模块 a:b"));

        assertTrue(error.getMessage().contains("模块 a:b"), error.getMessage());
    }

    @Test
    void 动态版本被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> VersionRanges.parse("1.+", "测试"));
        assertThrows(IllegalArgumentException.class, () -> VersionRanges.parse("[1.0,2.0+)", "测试"));
        assertThrows(IllegalArgumentException.class, () -> VersionRanges.parse("latest.release", "测试"));
    }

    @Test
    void Maven的元版本写法被当作动态版本拒绝() {
        // RELEASE 与 LATEST 的解析结果取决于仓库当时的内容，写进元数据后下游无法得到确定的版本。
        assertThrows(IllegalArgumentException.class, () -> VersionRanges.parse("RELEASE", "测试"));
        assertThrows(IllegalArgumentException.class, () -> VersionRanges.parse("LATEST", "测试"));
        assertThrows(IllegalArgumentException.class, () -> VersionRanges.parse("release", "测试"));
    }

    @Test
    void 单侧无界的区间被正确格式化() {
        assertEquals("[1.2.3,)", VersionRanges.parse("[1.2.3,)", "测试").format());
        assertEquals("(,2.0]", VersionRanges.parse("(,2.0]", "测试").format());
        assertEquals("(,2.0)", VersionRanges.parse("(,2.0)", "测试").format());
        assertEquals("[1.0,)", VersionRanges.parse("[1.0,]", "测试").format());
    }

    @Test
    void 上下界相同的闭区间被识别为确定版本() {
        VersionRanges.Range range = VersionRanges.parse("[1.2.3]", "测试");

        assertTrue(range.isExact());
        assertEquals("[1.2.3]", range.format());
        assertFalse(range.isEmpty());
    }

    @Test
    void 区间在开区间上界处不含该版本() {
        VersionRanges.Range range = VersionRanges.parse("[2.0.0,3.0.0)", "测试");

        assertTrue(VersionRanges.contains(range, "2.0.0"));
        assertTrue(VersionRanges.contains(range, "2.5.0"));
        assertFalse(VersionRanges.contains(range, "3.0.0"));
    }

    @Test
    void 固定版本在求交时被当作至少该版本() {
        VersionRanges.Range intersection = VersionRanges.intersect(List.of(
                VersionRanges.parse("2.0", "测试"),
                VersionRanges.parse("[1.0,3.0)", "测试")));

        assertEquals("[2.0,3.0)", intersection.format());
    }

    @Test
    void 与单侧无界区间求交时保留有界的一侧() {
        VersionRanges.Range intersection = VersionRanges.intersect(List.of(
                VersionRanges.parse("[1.0,)", "测试"),
                VersionRanges.parse("[2.0,3.0)", "测试")));

        assertEquals("[2.0,3.0)", intersection.format());
    }

    @Test
    void 求交时取更严格的下界与上界() {
        VersionRanges.Range intersection = VersionRanges.intersect(List.of(
                VersionRanges.parse("[1.0,3.0)", "测试"),
                VersionRanges.parse("[2.0,4.0)", "测试")));

        assertEquals("[2.0,3.0)", intersection.format());
    }

    @Test
    void 求交时开区间优先于闭区间() {
        VersionRanges.Range intersection = VersionRanges.intersect(List.of(
                VersionRanges.parse("[2.0,3.0)", "测试"),
                VersionRanges.parse("[2.0,3.0]", "测试")));

        assertEquals("[2.0,3.0)", intersection.format());
    }

    @Test
    void 无交集的区间组合被拒绝() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VersionRanges.intersect(List.of(
                        VersionRanges.parse("[1.0,2.0)", "测试"),
                        VersionRanges.parse("[3.0,4.0)", "测试"))));
    }

    @Test
    void 重复版本上界相等但开区间也同样无交集() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VersionRanges.intersect(List.of(
                        VersionRanges.parse("[1.0,2.0)", "测试"),
                        VersionRanges.parse("[2.0,3.0)", "测试"))));
    }

    @Test
    void 版本比较遵循Maven排序规则() {
        assertTrue(VersionRanges.compare("1.10.0", "1.9.0") > 0);
        assertTrue(VersionRanges.compare("1.0.0-alpha", "1.0.0") < 0);
        assertEquals(0, VersionRanges.compare("1.0.0", "1.0.0"));
    }
}
