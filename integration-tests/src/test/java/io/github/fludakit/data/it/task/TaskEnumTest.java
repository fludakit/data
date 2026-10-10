package io.github.fludakit.data.it.task;

import io.github.fludakit.data.it.DataSourceProducer;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@EnableAutoWeld
@AddPackages(Task.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class TaskEnumTest {

    @Inject TaskRepository tasks;

    @Test
    void enumFieldRoundTripsThroughGeneratedMapper() {
        Task t = new Task();
        t.setTitle("Ship it");
        t.setStatus(Status.IN_PROGRESS);
        Task saved = tasks.insert(t);
        assertThat(saved.getId()).isNotNull();

        Task reloaded = tasks.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Ship it");
        assertThat(reloaded.getStatus()).isEqualTo(Status.IN_PROGRESS);
    }
}
