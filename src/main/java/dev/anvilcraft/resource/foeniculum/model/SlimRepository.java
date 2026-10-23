package dev.anvilcraft.resource.foeniculum.model;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 参与瘦身依赖解析的一个 Maven 仓库。
 *
 * <p>凭据单独存放且不进入任务指纹，避免令牌被写进构建缓存；仓库地址本身是任务输入，
 * 因此切换仓库能够正确触发重新解析。
 *
 * @param url 仓库根地址
 * @param username 访问用户名，匿名访问时为 {@code null}
 * @param password 访问密码，匿名访问时为 {@code null}
 */
public record SlimRepository(String url, String username, String password) implements Serializable {
    public SlimRepository {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("仓库地址不能为空");
        }
    }

    public boolean hasCredentials() {
        return username != null && !username.isEmpty();
    }

    /**
     * 比较与输出共用的规范化地址：协议与主机名小写、去掉末尾斜杠，{@code file:} 地址统一为绝对路径形式。
     *
     * <p>同一仓库由不同写法声明时（例如 {@code file:/C:/repo} 与 {@code file:///C:/repo}），
     * 只有统一形式下游才能正确比对，否则会误报「未声明的仓库」。路径部分保持原样，
     * 因为 Maven 仓库路径区分大小写，统一转小写会造成误判。
     */
    public static String normalize(String rawUrl) {
        String trimmed = rawUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return trimmed;
        }

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            // file:C:/repo 这类非层级写法无法直接转成 Path；退回到原始字符串，
            // 让差异提示仍能工作，而不是在比对仓库时抛出一个与真实原因无关的异常。
            try {
                Path path = Path.of(uri).toAbsolutePath().normalize();
                String normalized = path.toUri().toString();
                while (normalized.endsWith("/")) {
                    normalized = normalized.substring(0, normalized.length() - 1);
                }
                return normalized;
            } catch (IllegalArgumentException e) {
                return trimmed;
            }
        }

        if (uri.getScheme() == null || uri.getHost() == null) {
            return trimmed;
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return scheme + "://" + host + (port == -1 ? "" : ":" + port) + path;
    }
}
