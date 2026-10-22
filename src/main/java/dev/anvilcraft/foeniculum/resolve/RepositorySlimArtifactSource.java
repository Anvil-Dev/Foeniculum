package dev.anvilcraft.foeniculum.resolve;

import dev.anvilcraft.foeniculum.model.SlimMetadata;
import dev.anvilcraft.foeniculum.model.SlimRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 通过 Maven 仓库读取瘦身构件与描述文件的实现。
 *
 * <p>同一个坐标可能存在于多个仓库中，因此每次读取都按仓库声明顺序依次尝试。单个仓库的失败不会立即中断：
 * 真实仓库对不存在的构件常常返回 401、403 或直接断开连接，而不是规范要求的 404，
 * 而这些仓库在这个坐标上根本没有内容。因此这里把「出错」和「不存在」一样当作“换下一个仓库试试”，
 * 只有在所有仓库都没能提供内容时才把累积的错误一并报告出来，既不会因无关仓库的异常而失败，
 * 也不会把真正的网络故障静默吞掉。
 */
public final class RepositorySlimArtifactSource implements SlimArtifactSource {
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String METADATA_SUFFIX = "-slim-metadata.json";

    private final List<SlimRepository> repositories;
    private final HttpClient httpClient;
    private final List<String> warnings;

    /**
     * @param repositories 按声明顺序尝试的仓库
     * @param warnings 用于收集访问过程中值得提示的情况
     */
    public RepositorySlimArtifactSource(List<SlimRepository> repositories, List<String> warnings) {
        this.repositories = List.copyOf(repositories);
        this.warnings = warnings;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public SlimMetadata findMetadata(String group, String artifact, String version) {
        String relative = coordinatePath(group, artifact, version)
                + "/" + artifact + "-" + version + METADATA_SUFFIX;
        for (SlimRepository repository : repositories) {
            byte[] content = readLeniently(repository, relative, "描述文件 " + group + ":" + artifact);
            if (content == null) {
                continue;
            }
            try {
                return SlimMetadata.read(new StringReader(new String(content, StandardCharsets.UTF_8)));
            } catch (RuntimeException e) {
                throw new IllegalStateException("无法解析来自 " + repository.url() + " 的描述文件 "
                        + relative + "：" + e.getMessage(), e);
            }
        }
        return null;
    }

    @Override
    public List<String> findVersions(String group, String artifact) {
        String relative = group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
        for (SlimRepository repository : repositories) {
            byte[] content = readLeniently(repository, relative, "版本列表 " + group + ":" + artifact);
            if (content == null) {
                continue;
            }
            List<String> versions = parseVersions(content, repository.url() + "/" + relative);
            if (!versions.isEmpty()) {
                return versions;
            }
        }
        return List.of();
    }

    @Override
    public boolean download(String group, String artifact, String version, String classifier, Path target) {
        String fileName = classifier == null
                ? artifact + "-" + version + ".jar"
                : artifact + "-" + version + "-" + classifier + ".jar";
        String relative = coordinatePath(group, artifact, version) + "/" + fileName;

        List<String> failures = new ArrayList<>();
        for (SlimRepository repository : repositories) {
            byte[] content;
            try {
                content = read(repository, relative);
            } catch (RuntimeException e) {
                failures.add(e.getMessage());
                continue;
            }
            if (content == null) {
                continue;
            }
            write(target, content);
            return true;
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("无法从任何已声明的仓库获取构件 " + group + ":" + artifact
                    + ":" + version + "（" + fileName + "）：\n  - " + String.join("\n  - ", failures));
        }
        return false;
    }

    private static void write(Path target, byte[] content) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入构件文件 " + target, e);
        }
    }

    private static String coordinatePath(String group, String artifact, String version) {
        return group.replace('.', '/') + "/" + artifact + "/" + version;
    }

    /**
     * 探测一个可选的仓库文件。
     *
     * @return 文件内容；该仓库中不存在或访问失败时返回 {@code null}，后者会记录到警告中
     */
    private byte[] readLeniently(SlimRepository repository, String relative, String what) {
        try {
            return read(repository, relative);
        } catch (RuntimeException e) {
            warnings.add("读取 " + what + " 时仓库 " + repository.url() + " 返回错误，已跳过该仓库；"
                    + "若解析结果缺少内容，请检查该仓库的可用性：" + e.getMessage());
            return null;
        }
    }

    /**
     * 读取仓库中的相对路径。
     *
     * @return 文件内容；仓库中不存在时返回 {@code null}
     */
    private byte[] read(SlimRepository repository, String relative) {
        URI uri = URI.create(repositoryUrl(repository) + "/" + relative);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            Path file = Path.of(uri);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                throw new IllegalStateException("无法读取本地仓库文件 " + file, e);
            }
        }
        return readHttp(repository, uri, relative);
    }

    private byte[] readHttp(SlimRepository repository, URI uri, String relative) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "foeniculum")
                .GET();
        if (repository.hasCredentials()) {
            String raw = repository.username() + ":"
                    + (repository.password() == null ? "" : repository.password());
            builder.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("访问失败：" + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("访问被中断", e);
        }

        int status = response.statusCode();
        if (status == 404 || status == 410) {
            return null;
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + "：" + relative);
        }
        return response.body();
    }

    private List<String> parseVersions(byte[] content, String location) {
        Document document;
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            try (InputStream stream = new ByteArrayInputStream(content)) {
                document = builder.parse(stream);
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("无法解析版本列表 " + location, e);
        }

        List<String> versions = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("version");
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent();
            if (value != null && !value.isBlank()) {
                versions.add(value.trim());
            }
        }
        if (versions.isEmpty()) {
            warnings.add("仓库中的 " + location + " 没有列出任何版本，无法用它回落选择兼容版本");
        }
        return versions;
    }

    private static String repositoryUrl(SlimRepository repository) {
        String url = repository.url().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
