package io.saasforge.iam.infrastructure.persistence.type;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** PostgreSQL text[] 与受限 Scope 集合之间的显式映射。 */
public final class StringArrayTypeHandler extends BaseTypeHandler<String[]> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, String[] parameter, JdbcType jdbcType)
            throws SQLException {
        Connection connection = statement.getConnection();
        statement.setArray(index, connection.createArrayOf("text", parameter));
    }

    @Override
    public String[] getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return value(resultSet.getArray(columnName));
    }

    @Override
    public String[] getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return value(resultSet.getArray(columnIndex));
    }

    @Override
    public String[] getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return value(statement.getArray(columnIndex));
    }

    private String[] value(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            return (String[]) array.getArray();
        } finally {
            array.free();
        }
    }
}
