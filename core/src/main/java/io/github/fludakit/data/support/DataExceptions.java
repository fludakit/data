package io.github.fludakit.data.support;

import io.github.fludakit.jdbc.JdbcClientException;
import jakarta.data.exceptions.DataConnectionException;
import jakarta.data.exceptions.DataException;
import jakarta.data.exceptions.EmptyResultException;
import jakarta.data.exceptions.MappingException;
import jakarta.data.exceptions.NonUniqueResultException;

/**
 * Translates the runtime failures raised by {@code JdbcClient} into the exception hierarchy that
 * Jakarta Data defines, so repository callers only ever observe {@link DataException} subtypes.
 */
public final class DataExceptions {

    private DataExceptions() {}

    public static DataException translate(RuntimeException e) {
        if (e instanceof DataException d) return d;
        if (e instanceof JdbcClientException j) {
            return switch (j.getCode()) {
                case JDBC -> new DataConnectionException(j.getMessage(), j);
                case MAPPING_FAILURE -> new MappingException(j.getMessage(), j);
                case NO_RESULT -> new EmptyResultException(j.getMessage(), j);
                case TOO_MANY_RESULTS -> new NonUniqueResultException(j.getMessage(), j);
                default -> new DataException(j.getMessage(), j);
            };
        }
        return new DataException(e.getMessage(), e);
    }
}
