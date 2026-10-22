package io.github.fludakit.data.it.task;

import io.github.fludakit.jdbc.JdbcClient;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface TaskRepository extends CrudRepository<Task, Long> {

    JdbcClient getJdbcClient();

    default Task[] findByStatus(Status status){
        return getJdbcClient()
                .sql("SELECT \"id\", \"title\", \"status\" FROM \"tasks\" WHERE \"status\" = :status")
                .param("status", status == null ? null : status.name())
                .query(new TaskRowMapper())
                .list()
                .toArray(new Task[0]);
    }
}
