package com.springclaw.service.skill.bundle;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * skill 目录的统一扫描入口。
 */
@Service
public class SkillCatalogService {

    private final boolean enabled;
    private final Path rootPath;
    private final List<Path> externalRootPaths;

    public SkillCatalogService(boolean enabled, String root) {
        this(enabled, root, "");
    }

    @Autowired
    public SkillCatalogService(@Value("${springclaw.skills.enabled:true}") boolean enabled,
                                      @Value("${springclaw.skills.root:${user.dir}/skills}") String root,
                                      @Value("${springclaw.skills.external-roots:}") String externalRoots) {
        this.enabled = enabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.externalRootPaths = parseExternalRoots(externalRoots);
    }

    public boolean enabled() {
        return enabled;
    }

    public Path rootPath() {
        return rootPath;
    }

    public List<SkillBundleDefinition> listBundles() {
        if (!enabled) {
            return List.of();
        }
        Map<String, SkillBundleDefinition> bundles = new LinkedHashMap<>();
        for (Path root : scanRoots()) {
            for (SkillBundleDefinition definition : listBundlesFromRoot(root)) {
                bundles.putIfAbsent(definition.skillId(), definition);
            }
        }
        return bundles.values().stream()
                .sorted(Comparator.comparingInt(SkillBundleDefinition::priority)
                        .thenComparing(definition -> definition.skillId().toLowerCase()))
                .toList();
    }

    public List<SkillBundleDefinition> reloadBundles() {
        return listBundles();
    }

    public List<Path> scanRoots() {
        if (!enabled) {
            return List.of();
        }
        List<Path> roots = new ArrayList<>();
        roots.add(rootPath);
        roots.addAll(externalRootPaths);
        return roots.stream()
                .filter(Files::isDirectory)
                .distinct()
                .toList();
    }

    private List<SkillBundleDefinition> listBundlesFromRoot(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .map(SkillBundleSupport::parseBundle)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private List<Path> parseExternalRoots(String externalRoots) {
        if (!StringUtils.hasText(externalRoots)) {
            return List.of();
        }
        List<Path> roots = new ArrayList<>();
        for (String token : externalRoots.split(",")) {
            if (StringUtils.hasText(token)) {
                roots.add(Path.of(token.trim()).toAbsolutePath().normalize());
            }
        }
        return List.copyOf(roots);
    }
}
