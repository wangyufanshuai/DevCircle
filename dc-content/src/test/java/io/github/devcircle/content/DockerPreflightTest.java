package io.github.devcircle.content;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DockerPreflightTest {
    @Container
    static final GenericContainer<?> alpine = new GenericContainer<>("alpine:3.22")
            .withCommand("sh", "-c", "while true; do sleep 1; done");

    @Test
    void dockerDaemonCanStartMinimalContainer() {
        System.out.println("Testcontainers Docker host is available; alpine container id=" + alpine.getContainerId());
        assertThat(alpine.isRunning()).isTrue();
    }
}
