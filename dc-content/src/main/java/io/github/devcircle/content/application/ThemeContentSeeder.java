package io.github.devcircle.content.application;

import io.github.devcircle.common.seed.ThemeContentLibrary;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ThemeContentSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ThemeContentSeeder.class);

    private final PostRepository postRepository;
    private final boolean enabled;

    public ThemeContentSeeder(PostRepository postRepository,
                              @Value("${devcircle.seed.theme-content.enabled:false}") boolean enabled) {
        this.postRepository = postRepository;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int imported = postRepository.importSeedsIfEmpty(ThemeContentLibrary.posts());
        if (imported > 0) {
            log.info("Imported {} MysticVerse themed seed posts into MySQL", imported);
        }
    }
}
