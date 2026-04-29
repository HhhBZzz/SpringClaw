package com.springclaw.service.skill.bundle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * package skill 的统一扫描入口。
 */
@Service
public class SkillPackageCatalogService {

    private final boolean enabled;
    private final Path rootPath;

    public SkillPackageCatalogService(@Value("${springclaw.skills.enabled:true}") boolean enabled,
                                      @Value("${springclaw.skills.root:${user.dir}/skills/packages}") String root) {
        this.enabled = enabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
    }

    public boolean enabled() {
        return enabled;
    }

    public Path rootPath() {
        return rootPath;
    }

    public List<SkillBundleDefinition> listBundles() {
        if (!enabled || !Files.isDirectory(rootPath)) {
            return List.of();
        }
        try (var stream = Files.list(rootPath)) {
            return stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .map(SkillBundleSupport::parseBundle)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    public List<SkillBundleDefinition> reloadBundles() {
        return listBundles();
    }
}
