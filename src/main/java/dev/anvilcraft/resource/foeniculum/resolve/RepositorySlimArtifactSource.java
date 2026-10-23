package dev.anvilcraft.resource.foeniculum.resolve;

import dev.anvilcraft.resource.foeniculum.model.SlimMetadata;
import dev.anvilcraft.resource.foeniculum.model.SlimRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /** 单个仓库出错时最多提示一次，避免依赖树里每个坐标都刷出一条同样的警告。 */
    private static final int MAX_ACCESS_WARNINGS = 5;

    private final List<SlimRepository> repositories;
    private final HttpClient httpClient;
    private final List<String> warnings;
    private final Set<String> warnedRepositories = new LinkedHashSet<>();

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
        Lookup lookup = new Lookup("描述文件 " + group + ":" + artifact);
        for (SlimRepository repository : repositories) {
            byte[] content = lookup.readFrom(this, repository, relative);
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
        lookup.failIfAllErrored(group + ":" + artifact, relative);
        return null;
    }

    @Override
    public List<String> findVersions(String group, String artifact) {
        String relative = group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
        Lookup lookup = new Lookup("版本列表 " + group + ":" + artifact);
        for (SlimRepository repository : repositories) {
            byte[] content = lookup.readFrom(this, repository, relative);
            if (content == null) {
                continue;
            }
            List<String> versions = parseVersions(content, repository.url() + "/" + relative);
            if (!versions.isEmpty()) {
                return versions;
            }
        }
        lookup.failIfAllErrored(group + ":" + artifact, relative);
        return List.of();
    }

    /**
     * 依次查询各仓库时累积的错误，以及「是否有仓库明确回答不存在」。
     *
     * <p>「某个仓库出错就换下一个」是必要的：真实仓库对不存在的构件常返回 401、403 或直接断开连接，
     * 而这些仓库在这个坐标上根本没有内容。但如果全部仓库都以错误收场，就完全无法判断该模块是否存在，
     * 此时把「读不到」当成「不存在」会让解析器把该模块误判为叶子，产物静默缺少它的传递依赖。
     * 因此只有「至少一个仓库明确回答不存在」时才可以安全地当作叶子，否则把各仓库的错误一并报告出来。
     */
    private final class Lookup {
        private final String what;
        private final List<String> failures = new ArrayList<>();
        private boolean definitelyAbsent;

        private Lookup(String what) {
            this.what = what;
        }

        private byte[] readFrom(
                RepositorySlimArtifactSource source, SlimRepository repository, String relative) {
            try {
                byte[] content = source.read(repository, relative);
                if (content == null) {
                    definitelyAbsent = true;
                }
                return content;
            } catch (RuntimeException e) {
                failures.add(repository.url() + "：" + e.getMessage());
                source.warnAccessFailure(repository, what, e.getMessage());
                return null;
            }
        }

        private void failIfAllErrored(String moduleId, String relative) {
            if (definitelyAbsent || failures.isEmpty()) {
                return;
            }
            throw new IllegalStateException("无法从任何已声明的仓库读取模块 " + moduleId + " 的 " + relative + "：\n  - "
                    + String.join("\n  - ", failures));
        }
    }

    /**
     * 记录一次仓库访问失败，但同一个仓库只提示一次。
     *
     * <p>依赖树里的每个坐标都会遍历全部仓库，若每次都提示，一个有几十个模块的项目会刷出上百行重复内容，
     * 反而淹没真正需要关注的告警。
     */
    private void warnAccessFailure(SlimRepository repository, String what, String reason) {
        if (warnedRepositories.size() >= MAX_ACCESS_WARNINGS || !warnedRepositories.add(repository.url())) {
            return;
        }
        warnings.add("读取 " + what + " 时仓库 " + repository.url() + " 返回错误，已跳过该仓库："
                + reason + "；若解析结果缺少内容，请检查该仓库的可用性");
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
